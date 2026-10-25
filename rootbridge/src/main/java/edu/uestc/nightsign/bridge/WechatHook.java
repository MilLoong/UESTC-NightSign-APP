package edu.uestc.nightsign.bridge;

import android.app.Application;
import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class WechatHook implements IXposedHookLoadPackage {
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicInteger sequence = new AtomicInteger(1_500_000_000);
    private final Set<Class<?>> installed = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Set<Method> callbacks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<Integer, WeakReference<Object>> ownedCallbacks = new ConcurrentHashMap<>();
    private volatile Binding binding;
    private volatile Pending pending;

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam info) {
        if (!"com.tencent.mm".equals(info.packageName) || info.processName == null ||
            !info.processName.startsWith("com.tencent.mm:appbrand")) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                start(context.getClassLoader());
            }
        });
    }

    private void start(ClassLoader loader) {
        if (!started.compareAndSet(false, true)) return;
        XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.args.length == 0 || !(param.args[0] instanceof String)) return;
                String name = (String) param.args[0];
                if (isLoginClass(name) && param.getResult() instanceof Class<?>) {
                    installLogin((Class<?>) param.getResult());
                }
            }
        });
        try { installLogin(Class.forName("com.tencent.mm.plugin.appbrand.jsapi.auth.JsApiLogin", false, loader)); }
        catch (Throwable ignored) { /* Split APK classes can be loaded after Application.attach. */ }
        Thread server = new Thread(this::serve, "nightsign-root-bridge");
        server.setDaemon(true);
        server.start();
    }

    private static boolean isLoginClass(String name) {
        return name.startsWith("com.tencent.mm.plugin.appbrand.jsapi.") && name.endsWith(".JsApiLogin");
    }

    private void installLogin(Class<?> apiClass) {
        if (!installed.add(apiClass)) return;
        for (Method method : InvocationShape.loginMethods(apiClass)) {
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.thisObject == null || !isLoginClass(param.thisObject.getClass().getName())) return;
                            Object component = param.args[0];
                            if (!Protocol.APP_ID.equals(InvocationShape.appId(component))) return;
                            Looper looper = Looper.myLooper();
                            if (looper == null) return;
                            Method callback = InvocationShape.callback(component.getClass());
                            installCallback(callback);
                            binding = new Binding(param.thisObject, component, method, new Handler(looper));
                        } catch (Throwable ignored) { /* Unknown signatures fail closed. */ }
                    }
                });
            } catch (Throwable ignored) { /* Do not break WeChat when an adapter shape is unsupported. */ }
        }
    }

    private void installCallback(Method method) throws Throwable {
        if (!callbacks.add(method)) return;
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof Integer)) return;
                    int id = (Integer) param.args[0];
                    WeakReference<Object> owner = ownedCallbacks.get(id);
                    if (owner == null || owner.get() != param.thisObject) return;
                    ownedCallbacks.remove(id);
                    // Our synthetic callback must never reach a page, even after the requester timed out.
                    param.setResult(null);
                    Pending request = pending;
                    if (request == null || request.component != param.thisObject ||
                        !Integer.valueOf(request.callbackId).equals(param.args[0])) return;
                    try {
                        JSONObject value = new JSONObject((String) param.args[1]);
                        String code = value.optString("code");
                        if (code.matches("[A-Za-z0-9_-]{24,128}") &&
                            Protocol.APP_ID.equals(InvocationShape.appId(param.thisObject))) request.code = code;
                        else request.error = "WX_LOGIN_FAILED";
                    } catch (Throwable ignored) { request.error = "WX_LOGIN_FAILED"; }
                    request.done.countDown();
                }
            });
        } catch (Throwable error) {
            callbacks.remove(method);
            throw error;
        }
    }

    private JSONObject login(JSONObject input) throws Exception {
        if (!busy.compareAndSet(false, true)) return Protocol.response(input, "BUSY");
        Pending request = null;
        try {
            Binding current = binding;
            Object api = current == null ? null : current.api.get();
            Object component = current == null ? null : current.component.get();
            if (api == null || component == null) return Protocol.response(input, "TARGET_NOT_OPEN");
            if (!Protocol.APP_ID.equals(InvocationShape.appId(component))) return Protocol.response(input, "TARGET_NOT_OPEN");
            if (ownedCallbacks.size() >= 64) return Protocol.response(input, "BUSY");
            int id = sequence.getAndIncrement();
            if (id < 1_500_000_000) return Protocol.response(input, "RESTART_REQUIRED");
            request = new Pending(component, id);
            pending = request;
            ownedCallbacks.put(id, new WeakReference<>(component));
            current.handler.postDelayed(() -> ownedCallbacks.remove(id), 60000);
            Pending active = request;
            boolean posted = current.handler.post(() -> {
                if (pending != active || SystemClock.elapsedRealtime() > active.expiresAt) return;
                try {
                    if (!Protocol.APP_ID.equals(InvocationShape.appId(component))) throw new IllegalStateException();
                    XposedBridge.invokeOriginalMethod(current.method, api,
                        new Object[] { component, new JSONObject().put("timeout", 10000), id });
                } catch (Throwable ignored) {
                    active.error = "WX_LOGIN_FAILED";
                    active.done.countDown();
                }
            });
            if (!posted || !request.done.await(15, TimeUnit.SECONDS)) return Protocol.response(input, "WX_LOGIN_TIMEOUT");
            if (request.code == null) return Protocol.response(input, request.error);
            return Protocol.response(input, null).put("code", request.code);
        } finally {
            if (pending == request) pending = null;
            busy.set(false);
        }
    }

    private void serve() {
        ThreadPoolExecutor clients = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), task -> { Thread t = new Thread(task, "nightsign-root-client"); t.setDaemon(true); return t; });
        try (LocalServerSocket server = new LocalServerSocket(Protocol.SOCKET_PREFIX + Process.myPid())) {
            while (true) {
                LocalSocket socket = server.accept();
                try {
                    if (socket.getPeerCredentials().getUid() != 0) { socket.close(); continue; }
                    socket.setSoTimeout(2000);
                    clients.execute(() -> handleClient(socket));
                } catch (Exception ignored) { socket.close(); }
            }
        } catch (Exception ignored) {
            XposedBridge.log("NightSign bridge: local socket unavailable");
        } finally { clients.shutdownNow(); }
    }

    private void handleClient(LocalSocket socket) {
        try (LocalSocket client = socket) {
            JSONObject input = Protocol.read(client.getInputStream());
            if (!Protocol.validRequest(input)) {
                Protocol.write(client.getOutputStream(), Protocol.response(input, "INVALID_REQUEST"));
                return;
            }
            JSONObject result;
            if ("status".equals(input.optString("op"))) {
                Binding current = binding;
                Object component = current == null ? null : current.component.get();
                boolean ready = component != null && current.api.get() != null &&
                    Protocol.APP_ID.equals(InvocationShape.appId(component));
                result = Protocol.response(input, null).put("ready", ready);
            } else result = login(input);
            Protocol.write(client.getOutputStream(), result);
        } catch (Exception ignored) { /* Never log client payloads or login callbacks. */ }
    }

    private static final class Binding {
        final WeakReference<Object> api;
        final WeakReference<Object> component;
        final Method method;
        final Handler handler;
        Binding(Object api, Object component, Method method, Handler handler) {
            this.api = new WeakReference<>(api); this.component = new WeakReference<>(component);
            this.method = method; this.handler = handler;
        }
    }

    private static final class Pending {
        final Object component;
        final int callbackId;
        final CountDownLatch done = new CountDownLatch(1);
        final long expiresAt = SystemClock.elapsedRealtime() + 15000;
        volatile String code;
        volatile String error = "WX_LOGIN_FAILED";
        Pending(Object component, int callbackId) { this.component = component; this.callbackId = callbackId; }
    }
}
