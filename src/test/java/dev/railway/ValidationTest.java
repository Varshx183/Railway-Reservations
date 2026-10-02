package dev.railway;

import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ValidationTest {
    private final RequestHandler handler = new RequestHandler(null, "test-admin-token");

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "1", "{}", "{\"operation\":\"unknown\"}",
            "{\"operation\":42}", "{\"operation\":\"BOOK\",\"class\":\"XX\"}",
            "{\"operation\":\"BOOK\",\"class\":\"AC\",\"passengers\":[]}",
            "{\"operation\":\"BOOK\",\"class\":\"AC\",\"passengers\":[\" \" ]}",
            "{\"operation\":\"BOOK\",\"class\":\"AC\",\"passengers\":[123]}",
            "{\"operation\":\"AVAILABILITY\",\"train\":0}",
            "{\"operation\":\"AVAILABILITY\",\"train\":1.5}",
            "{\"operation\":\"AVAILABILITY\",\"train\":2147483648}",
            "{\"operation\":\"AVAILABILITY\",\"train\":1,\"date\":\"2030-02-30\"}",
            "{\"operation\":\"AVAILABILITY\",\"train\":1,\"date\":\"0000-01-01\"}",
            "{\"operation\":\"AVAILABILITY\",\"train\":1,\"date\":\"2030-1-1\"}",
            "{\"operation\":\"TICKET\",\"pnr\":\"1-1-1-1-1\"}",
            "{\"operation\":\"SEARCH\",\"source\":\"Delhi\",\"destination\":\" delhi \"}"})
    void invalidRequestsReturnErrors(String request) {
        var result = handler.handle(request);
        assertFalse(result.path("ok").asBoolean());
        assertEquals("INVALID_REQUEST", result.path("error").path("code").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"{bad", "", "{\"operation\":\"PING\"} trailing", "{\"operation\":\"PING\"} {}"})
    void malformedJsonIsRejected(String request) {
        String code = handler.handle(request).path("error").path("code").asText();
        assertTrue(code.equals("INVALID_JSON") || code.equals("INVALID_REQUEST"));
    }

    @Test void pingDoesNotNeedDatabase() { assertTrue(handler.handle("{\"operation\":\"PING\"}").path("ok").asBoolean()); }
    @Test void trainReleaseRequiresAdminToken() {
        assertEquals("UNAUTHORIZED", handler.handle("{\"operation\":\"RELEASE\"}").path("error").path("code").asText());
        assertEquals("UNAUTHORIZED", handler.handle("{\"operation\":\"RELEASE\",\"adminToken\":\"wrong\"}").path("error").path("code").asText());
    }
    @Test void trainReleaseRequiresCapacity() {
        var result = handler.handle("{\"operation\":\"RELEASE\",\"adminToken\":\"test-admin-token\",\"acCoaches\":0,\"sleeperCoaches\":0}");
        assertEquals("INVALID_REQUEST", result.path("error").path("code").asText());
    }
    @Test void tooManyPassengersRejected() {
        var request = Database.JSON.createObjectNode().put("operation", "BOOK").put("class", "AC");
        var people = request.putArray("passengers"); for (int i = 0; i < 101; i++) people.add("Name");
        assertEquals("INVALID_REQUEST", handler.handle(request.toString()).path("error").path("code").asText());
    }
    @Test void missingSecretsFailEarly() {
        assertThrows(IllegalArgumentException.class, () -> Config.fromEnvironment(Map.of()));
    }
    @Test void configurationDoesNotExposeSecrets() {
        var config = Config.fromEnvironment(Map.of("DB_PASSWORD", "secret-password", "ADMIN_TOKEN", "secret-token"));
        assertFalse(config.toString().contains("secret"));
    }
    @Test void readerHandlesEofAndCrLf() throws Exception {
        var input = new StringReader("first\r\nsecond");
        assertEquals("first", RailwayServer.readLine(input)); assertEquals("second", RailwayServer.readLine(input));
        assertNull(RailwayServer.readLine(input));
    }
    @Test void readerLimitsRequests() {
        assertThrows(java.io.IOException.class, () -> RailwayServer.readLine(new StringReader("x".repeat(32769))));
    }
}
