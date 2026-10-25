package edu.uestc.nightsign.bridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

final class InvocationShape {
    static List<Method> loginMethods(Class<?> apiClass) {
        for (Class<?> type = apiClass; type != null; type = type.getSuperclass()) {
            List<Method> methods = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (!method.isBridge() && !Modifier.isAbstract(method.getModifiers()) &&
                    !Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class &&
                    p.length == 3 && !p[0].isPrimitive() && p[0] != String.class &&
                    p[1].getName().equals("org.json.JSONObject") && p[2] == int.class) {
                    methods.add(method);
                }
            }
            if (!methods.isEmpty()) return methods;
        }
        return Collections.emptyList();
    }

    static Method callback(Class<?> componentClass) throws NoSuchMethodException {
        Method result = componentClass.getMethod("callback", int.class, String.class);
        if (result.getReturnType() != void.class || Modifier.isStatic(result.getModifiers())) {
            throw new NoSuchMethodException("UNSUPPORTED_CALLBACK");
        }
        return result;
    }

    static String appId(Object component) throws Exception {
        Method method = component.getClass().getMethod("getAppId");
        if (method.getReturnType() != String.class || Modifier.isStatic(method.getModifiers())) return "";
        method.setAccessible(true);
        return (String) method.invoke(component);
    }

    private InvocationShape() { }
}
