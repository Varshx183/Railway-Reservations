package dev.railway;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@Timeout(40)
class DatabaseIntegrationTest {
    private static final LocalDate DATE = LocalDate.of(2030, 3, 8);
    private static final String TOKEN = "test-admin-token";
    private Config config;
    private Database database;
    private RequestHandler handler;
    private String schema;
    private RailwayServer server;
    private Thread serving;
    private volatile Throwable serverFailure;

    @BeforeEach void setup() throws Exception {
        schema = "railway_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = baseConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String base = System.getenv("TEST_DB_URL");
        config = new Config(base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv().getOrDefault("TEST_DB_USER", "railway"), System.getenv("TEST_DB_PASSWORD"),
                TOKEN, "127.0.0.1", 0, 8, 32, 1000);
        database = new Database(config); database.initialize(false); handler = new RequestHandler(database, TOKEN);
    }

    @AfterEach void cleanup() throws Exception {
        if (server != null) server.close();
        if (serving != null) { serving.join(5000); assertFalse(serving.isAlive()); }
        if (schema != null) {
            try (Connection connection = baseConnection(); Statement statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
        assertNull(serverFailure);
    }
    private Connection baseConnection() throws SQLException {
        return DriverManager.getConnection(System.getenv("TEST_DB_URL"), System.getenv().getOrDefault("TEST_DB_USER", "railway"), System.getenv("TEST_DB_PASSWORD"));
    }
    private void release(int ac, int sl) throws SQLException { database.release(6773, DATE, ac, sl); }
    private JsonNode book(String type, int count) throws SQLException {
        return database.book(6773, DATE, type, java.util.stream.IntStream.range(0, count).mapToObj(i -> "Passenger " + i).toList());
    }
    private int scalar(String query) throws SQLException {
        try (Connection connection = database.open(); Statement statement = connection.createStatement(); ResultSet results = statement.executeQuery(query)) { results.next(); return results.getInt(1); }
    }
    private void sql(String query) throws SQLException {
        try (Connection connection = database.open(); Statement statement = connection.createStatement()) { statement.execute(query); }
    }
    private void startServer() throws IOException {
        server = new RailwayServer(config, database);
        serving = new Thread(() -> { try { server.serve(); } catch (Throwable e) { serverFailure = e; } }, "test-server");
        serving.start();
    }

    @Test void schemaAndSeedInitializationAreRepeatable() throws Exception {
        database.initialize(true); database.initialize(true);
        assertEquals(2361, scalar("SELECT count(*) FROM routes"));
        JsonNode result = database.search(" anandpur sahib ", "NEW DELHI");
        assertEquals(7, result.size()); assertEquals(12058, result.get(0).path("train").asInt());
        assertTrue(database.search("Unknown", "Nowhere").isEmpty());
        assertEquals(1, scalar("SELECT count(*) FROM routes WHERE train_id=12650 AND source='New Delhi' AND destination='Kacheguda' AND departure_day=0 AND arrival_offset_days=1"));
    }
    @Test void releaseIsIdempotentWithoutResettingBookings() throws Exception {
        release(1, 1); book("AC", 2);
        assertThrows(RequestException.class, () -> release(2, 2));
        assertEquals(2, scalar("SELECT sum(seats_booked) FROM seat_inventory"));
        assertEquals(18, database.availability(6773, DATE).path("classes").get(0).path("capacity").asInt());
    }
    @Test void acAssignmentsCrossCoachBoundaryCorrectly() throws Exception {
        release(2, 0); JsonNode ticket = book("AC", 20); JsonNode people = ticket.path("passengers");
        assertEquals(18, people.get(17).path("berth").asInt()); assertEquals(1, people.get(17).path("coach").asInt());
        assertEquals(1, people.get(18).path("berth").asInt()); assertEquals(2, people.get(18).path("coach").asInt());
        assertEquals("LB", people.get(18).path("berthType").asText());
        assertEquals(ticket, database.ticket(UUID.fromString(ticket.path("pnr").asText())));
        assertEquals(20, scalar("SELECT count(*) FROM passengers"));
    }
    @Test void sleeperAssignmentsCrossCoachBoundaryCorrectly() throws Exception {
        release(0, 2); JsonNode people = book("SL", 26).path("passengers");
        assertEquals(24, people.get(23).path("berth").asInt()); assertEquals("SU", people.get(23).path("berthType").asText());
        assertEquals(1, people.get(24).path("berth").asInt()); assertEquals(2, people.get(24).path("coach").asInt());
        assertEquals("MB", people.get(25).path("berthType").asText());
    }
    @Test void separateBookingsContinueAtNextSeatAndKeepAllNames() throws Exception {
        release(2, 1); book("AC", 18);
        JsonNode ticket = database.book(6773, DATE, "AC", List.of("Alice Smith", "O'Connor", "Varshith Reddy"));
        assertEquals(2, ticket.path("passengers").get(0).path("coach").asInt());
        assertEquals(1, ticket.path("passengers").get(0).path("berth").asInt());
        assertEquals("O'Connor", ticket.path("passengers").get(1).path("name").asText());
        assertEquals("Varshith Reddy", ticket.path("passengers").get(2).path("name").asText());
    }
    @Test void oversizedBookingIsAtomic() throws Exception {
        release(1, 0); book("AC", 17);
        assertThrows(SQLException.class, () -> book("AC", 2));
        assertEquals(17, scalar("SELECT sum(seats_booked) FROM seat_inventory"));
        assertEquals(1, scalar("SELECT count(*) FROM tickets")); assertEquals(17, scalar("SELECT count(*) FROM passengers"));
        book("AC", 1); assertThrows(SQLException.class, () -> book("AC", 1));
    }
    @Test void unavailableTrainAndUnknownTicketHaveClearErrors() {
        assertEquals("TRAIN_NOT_AVAILABLE", handler.handle("{\"operation\":\"BOOK\",\"train\":6773,\"date\":\"2030-03-08\",\"class\":\"AC\",\"passengers\":[\"Alice\"]}").path("error").path("code").asText());
        assertEquals("TICKET_NOT_FOUND", handler.handle("{\"operation\":\"TICKET\",\"pnr\":\"" + UUID.randomUUID() + "\"}").path("error").path("code").asText());
    }
    @Test void databaseRejectsInvalidInputsEvenWithoutJavaValidation() throws Exception {
        release(1, 0);
        for (String command : List.of("SELECT book_tickets(6773, '2030-03-08', 'XX', ARRAY['Alice'])", "SELECT book_tickets(6773, '2030-03-08', 'AC', ARRAY[]::text[])", "SELECT book_tickets(6773, '2030-03-08', 'AC', ARRAY[NULL]::text[])", "SELECT release_train(-1, '2030-03-08', 1, 0)")) {
            SQLException error = assertThrows(SQLException.class, () -> sql(command)); assertEquals("22023", error.getSQLState());
        }
        assertEquals(0, scalar("SELECT count(*) FROM tickets"));
    }
    @Test void fortyConcurrentBookingsCannotOversellOrDuplicateSeats() throws Exception {
        release(1, 0);
        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            var futures = new ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 40; i++) futures.add(executor.submit(() -> handler.handle("{\"operation\":\"BOOK\",\"train\":6773,\"date\":\"2030-03-08\",\"class\":\"AC\",\"passengers\":[\"Alice\"]}")));
            int success = 0, rejected = 0; Set<String> pnrs = new HashSet<>();
            for (var future : futures) {
                JsonNode response = future.get(30, TimeUnit.SECONDS);
                if (response.path("ok").asBoolean()) { success++; assertTrue(pnrs.add(response.path("data").path("pnr").asText())); }
                else { assertEquals("SEATS_NOT_AVAILABLE", response.path("error").path("code").asText()); rejected++; }
            }
            assertEquals(18, success); assertEquals(22, rejected);
        }
        assertEquals(18, scalar("SELECT count(*) FROM passengers"));
        assertEquals(18, scalar("SELECT count(DISTINCT seat_index) FROM passengers"));
        assertEquals(18, scalar("SELECT sum(seats_booked) FROM seat_inventory"));
    }
    @Test void concurrentReleaseCreatesOnlyOneInventory() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> { try { release(1, 1); return true; } catch (RequestException e) { return false; } }));
            int success = 0; for (var future : futures) if (future.get()) success++;
            assertEquals(1, success);
        }
        assertEquals(2, scalar("SELECT count(*) FROM seat_inventory"));
    }
    @Test void searchFindsDirectAndOneTransferWithoutSameTrainChanges() throws Exception {
        sql("INSERT INTO routes VALUES (1,'A','B','09:00','10:00',0,0),(2,'B','C','11:00','12:00',0,0),(3,'A','C','08:00','13:00',0,0),(1,'B','C','10:30','12:00',0,0)");
        JsonNode routes = database.search("A", "C"); assertEquals(2, routes.size());
        assertEquals("DIRECT", routes.get(0).path("type").asText());
        assertEquals("ONE_TRANSFER", routes.get(1).path("type").asText());
        assertEquals(60, routes.get(1).path("waitMinutes").asInt());
    }
    @Test void searchHandlesMidnightWeekRolloverAndMultiDayArrival() throws Exception {
        sql("INSERT INTO routes VALUES (1,'A','B','22:00','23:30',6,0),(2,'B','C','00:15','02:00',0,0),(3,'X','Y','22:00','23:30',5,1),(4,'Y','Z','00:15','03:00',0,0)");
        JsonNode route = database.search("A", "C").get(0);
        assertEquals(45, route.path("waitMinutes").asInt()); assertEquals(0, route.path("arrivalDay").asInt());
        assertEquals(240, route.path("durationMinutes").asInt());
        assertEquals(45, database.search("X", "Z").get(0).path("waitMinutes").asInt());
    }
    @Test void searchExcludesZeroAndTwoHourTransfers() throws Exception {
        sql("INSERT INTO routes VALUES (1,'A','B','09:00','10:00',0,0),(2,'B','C','10:00','12:00',0,0),(3,'B','C','12:00','13:00',0,0),(4,'B','C','11:59','13:00',0,0)");
        JsonNode routes = database.search("A", "C"); assertEquals(1, routes.size()); assertEquals(119, routes.get(0).path("waitMinutes").asInt());
    }
    @Test void networkHandlesMalformedInputMultipleRequestsAndEof() throws Exception {
        startServer();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output.println("{bad"); assertFalse(Database.JSON.readTree(input.readLine()).path("ok").asBoolean());
            output.println("{\"operation\":\"PING\"}"); assertTrue(Database.JSON.readTree(input.readLine()).path("ok").asBoolean());
            socket.shutdownOutput(); assertNull(input.readLine());
        }
    }
    @Test void networkClosesOversizedAndIdleConnections() throws Exception {
        startServer();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000); socket.getOutputStream().write(("x".repeat(32769) + "\n").getBytes(StandardCharsets.UTF_8)); socket.getOutputStream().flush();
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("REQUEST_TOO_LARGE", Database.JSON.readTree(input.readLine()).path("error").path("code").asText()); assertNull(input.readLine());
        }
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000); var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertEquals("CLIENT_TIMEOUT", Database.JSON.readTree(input.readLine()).path("error").path("code").asText()); assertNull(input.readLine());
        }
    }
    @Test void packagedClientWorksWithoutSentinel() throws Exception {
        release(1, 1); startServer();
        var file = Files.createTempFile("railway-client-", ".jsonl");
        try {
            Files.writeString(file, "{\"operation\":\"AVAILABILITY\",\"train\":6773,\"date\":\"2030-03-08\"}\n{\"operation\":\"BOOK\",\"train\":6773,\"date\":\"2030-03-08\",\"class\":\"AC\",\"passengers\":[\"Alice Smith\"]}\n");
            var bytes = new ByteArrayOutputStream();
            RailwayClient.run("127.0.0.1", server.port(), file, new PrintStream(bytes, true, StandardCharsets.UTF_8));
            var responses = bytes.toString(StandardCharsets.UTF_8).lines().toList();
            assertEquals(2, responses.size()); assertTrue(Database.JSON.readTree(responses.get(1)).path("ok").asBoolean());
            assertEquals("Alice Smith", Database.JSON.readTree(responses.get(1)).path("data").path("passengers").get(0).path("name").asText());
        } finally { Files.deleteIfExists(file); }
    }

    @Test void simultaneousSocketBookingsRespectCapacity() throws Exception {
        release(1, 0); startServer();
        var request = Database.JSON.createObjectNode().put("operation", "BOOK").put("train", 6773)
                .put("date", "2030-03-08").put("class", "AC");
        request.putArray("passengers").add("Alice");
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<JsonNode>>();
            for (int i = 0; i < 24; i++) futures.add(executor.submit(() -> RailwayClient.request("127.0.0.1", server.port(), request)));
            int successes = 0;
            for (var future : futures) {
                JsonNode response = future.get(30, TimeUnit.SECONDS);
                if (response.path("ok").asBoolean()) successes++;
                else assertEquals("SEATS_NOT_AVAILABLE", response.path("error").path("code").asText());
            }
            assertEquals(18, successes);
        }
        assertEquals(18, scalar("SELECT count(DISTINCT seat_index) FROM passengers"));
    }

    @Test void clientReadsSearchResponsesLargerThanRequestLimit() throws Exception {
        sql("INSERT INTO routes SELECT n, 'A', 'C', '09:00', '10:00', 0, 0 FROM generate_series(1, 250) n");
        startServer();
        var file = Files.createTempFile("railway-large-response-", ".jsonl");
        try {
            Files.writeString(file, "{\"operation\":\"SEARCH\",\"source\":\"A\",\"destination\":\"C\"}\n");
            var bytes = new ByteArrayOutputStream();
            RailwayClient.run("127.0.0.1", server.port(), file, new PrintStream(bytes, true, StandardCharsets.UTF_8));
            assertTrue(bytes.size() > RailwayServer.MAX_LINE_LENGTH);
            assertEquals(250, Database.JSON.readTree(bytes.toString(StandardCharsets.UTF_8)).path("data").size());
        } finally { Files.deleteIfExists(file); }
    }

    @Test void serverShutdownClosesActiveClient() throws Exception {
        startServer();
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            socket.getOutputStream().write("{\"operation\":\"PING\"}\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            assertTrue(Database.JSON.readTree(input.readLine()).path("ok").asBoolean());
            server.close(); assertNull(input.readLine());
        }
    }
}
