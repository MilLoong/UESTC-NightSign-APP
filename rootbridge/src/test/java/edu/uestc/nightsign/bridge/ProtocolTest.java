package edu.uestc.nightsign.bridge;

import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class ProtocolTest {
    private static final String NONCE = "0123456789abcdef0123456789abcdef";

    private JSONObject input() throws Exception {
        return new JSONObject().put("op", "login").put("appId", Protocol.APP_ID).put("nonce", NONCE);
    }

    @Test public void acceptsOnlyTargetAndKnownOperations() throws Exception {
        assertTrue(Protocol.validRequest(input()));
        assertFalse(Protocol.validRequest(input().put("appId", "other")));
        assertFalse(Protocol.validRequest(input().put("op", "readCookies")));
        assertFalse(Protocol.validRequest(input().put("nonce", "old-response")));
    }

    @Test public void framesOneBoundResponseWithoutPersistingCode() throws Exception {
        JSONObject response = Protocol.response(input(), null).put("code", "0" + "A".repeat(31));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Protocol.write(output, response);
        byte[] bytes = output.toByteArray();
        assertEquals('\n', bytes[bytes.length - 1]);
        JSONObject actual = Protocol.read(new ByteArrayInputStream(bytes));
        assertEquals(NONCE, actual.getString("nonce"));
        assertEquals(Protocol.APP_ID, actual.getString("appId"));
        assertTrue(actual.getBoolean("ok"));
    }

    @Test public void rejectsOversizedAndEmptyInput() throws Exception {
        try {
            Protocol.read(new ByteArrayInputStream("a".repeat(4097).getBytes(StandardCharsets.UTF_8)));
            fail();
        } catch (java.io.IOException expected) { assertEquals("RESPONSE_TOO_LARGE", expected.getMessage()); }
        try {
            Protocol.read(new ByteArrayInputStream(new byte[0]));
            fail();
        } catch (java.io.IOException expected) { assertEquals("EMPTY_RESPONSE", expected.getMessage()); }
    }

    @Test public void errorsRemainBoundWithoutIncludingCredentials() throws Exception {
        JSONObject error = Protocol.response(input(), "BUSY");
        assertFalse(error.getBoolean("ok"));
        assertEquals(NONCE, error.getString("nonce"));
        assertFalse(error.has("code"));
    }

    @Test public void discoversOnlyAsyncJSONObjectInvocationShape() {
        assertEquals(1, InvocationShape.loginMethods(Api.class).size());
        Method method = InvocationShape.loginMethods(Api.class).get(0);
        assertEquals("invoke", method.getName());
        assertEquals(1, InvocationShape.loginMethods(ChildApi.class).size());
    }

    @Test public void refusesWrongCallbackAndIdentitySignatures() throws Exception {
        assertEquals("callback", InvocationShape.callback(Component.class).getName());
        assertEquals(Protocol.APP_ID, InvocationShape.appId(new Component()));
        try { InvocationShape.callback(WrongComponent.class); fail(); }
        catch (NoSuchMethodException expected) { }
    }

    public static class Component {
        public String getAppId() { return Protocol.APP_ID; }
        public void callback(int id, String payload) { }
    }
    public static class Api {
        public void invoke(Component component, JSONObject params, int callback) { }
        public void unrelated(Component component, String params, int callback) { }
        public static void wrong(Component component, JSONObject params, int callback) { }
    }
    public static class ChildApi extends Api { }
    public static class WrongComponent { public String callback(int id, String value) { return value; } }
}
