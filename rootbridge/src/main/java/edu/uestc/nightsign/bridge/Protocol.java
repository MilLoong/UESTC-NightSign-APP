package edu.uestc.nightsign.bridge;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class Protocol {
    static final String APP_ID = "wxaa78bf2905f01cee";
    static final String SOCKET_PREFIX = "uestc-nightsign.wxlogin.";
    static final int MAX_BYTES = 4096;

    static JSONObject read(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() >= MAX_BYTES) throw new IOException("RESPONSE_TOO_LARGE");
            bytes.write(value);
        }
        if (bytes.size() == 0) throw new IOException("EMPTY_RESPONSE");
        return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
    }

    static void write(OutputStream output, JSONObject value) throws Exception {
        byte[] encoded = value.toString().getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_BYTES) throw new IOException("RESPONSE_TOO_LARGE");
        output.write(encoded);
        output.write('\n');
        output.flush();
    }

    static boolean validRequest(JSONObject value) {
        return APP_ID.equals(value.optString("appId")) &&
            value.optString("nonce").matches("[a-f0-9]{32}") &&
            ("status".equals(value.optString("op")) || "login".equals(value.optString("op")));
    }

    static JSONObject response(JSONObject request, String error) throws Exception {
        JSONObject result = new JSONObject().put("ok", error == null)
            .put("appId", APP_ID).put("nonce", request.optString("nonce"));
        if (error != null) result.put("error", error);
        return result;
    }

    private Protocol() { }
}
