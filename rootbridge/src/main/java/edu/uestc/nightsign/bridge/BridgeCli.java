package edu.uestc.nightsign.bridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Root app_process entry point. No network listener or persistent code file. */
public final class BridgeCli {
    public static void main(String[] args) {
        JSONObject input = new JSONObject();
        try {
            if (Process.myUid() != 0) { System.exit(73); return; }
            if (args.length != 5 || !"login".equals(args[0]) || !"--app-id".equals(args[1]) ||
                !Protocol.APP_ID.equals(args[2]) || !"--nonce".equals(args[3]) || !args[4].matches("[a-f0-9]{32}")) {
                print(Protocol.response(input, "INVALID_REQUEST")); return;
            }
            input.put("appId", Protocol.APP_ID).put("nonce", args[4]).put("op", "status");
            int wechatUid = Os.stat("/data/user/0/com.tencent.mm").st_uid;
            for (String pid : appbrandPids()) {
                JSONObject status;
                try { status = call(pid, wechatUid, input, 1000); }
                catch (Exception ignored) { continue; }
                if (!bound(status, input) || !status.optBoolean("ok") || !status.optBoolean("ready")) continue;
                input.put("op", "login");
                // Once a login request is sent, do not try another process on an ambiguous failure.
                JSONObject result;
                try { result = call(pid, wechatUid, input, 18000); }
                catch (Exception ignored) { print(Protocol.response(input, "WX_LOGIN_TIMEOUT")); return; }
                if (!bound(result, input)) { print(Protocol.response(input, "INVALID_RESPONSE")); return; }
                print(result);
                return;
            }
            print(Protocol.response(input, "TARGET_NOT_OPEN"));
        } catch (Exception ignored) {
            try { print(Protocol.response(input, "BRIDGE_UNAVAILABLE")); }
            catch (Exception failure) { System.exit(1); }
        }
    }

    private static JSONObject call(String pid, int expectedUid, JSONObject request, int timeout) throws Exception {
        if (!pid.matches("[0-9]{1,10}")) throw new IllegalArgumentException();
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(Protocol.SOCKET_PREFIX + pid, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(timeout);
            if (socket.getPeerCredentials().getUid() != expectedUid) throw new SecurityException();
            Protocol.write(socket.getOutputStream(), request);
            return Protocol.read(socket.getInputStream());
        }
    }

    private static boolean bound(JSONObject result, JSONObject input) {
        return Protocol.APP_ID.equals(result.optString("appId")) && input.optString("nonce").equals(result.optString("nonce"));
    }

    private static List<String> appbrandPids() throws Exception {
        List<String> command = new ArrayList<>();
        command.add("/system/bin/pidof");
        for (int i = 0; i < 10; i++) command.add("com.tencent.mm:appbrand" + i);
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IllegalStateException();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) {
                if (bytes.size() + count > 1024) throw new IllegalStateException();
                bytes.write(buffer, 0, count);
            }
            List<String> result = new ArrayList<>();
            for (String value : bytes.toString(StandardCharsets.UTF_8.name()).trim().split("\\s+")) {
                if (value.matches("[0-9]{1,10}") && !result.contains(value)) result.add(value);
            }
            return result;
        } finally { process.destroy(); }
    }

    private static void print(JSONObject value) {
        // This stdout is a private pipe to the requesting app, never a diagnostic log.
        System.out.println(value.toString());
    }

    private BridgeCli() { }
}
