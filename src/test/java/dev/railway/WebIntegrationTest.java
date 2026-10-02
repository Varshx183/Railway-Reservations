package dev.railway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DB_URL", matches=".+")
@Timeout(60)
class WebIntegrationTest {
    private static final String PASSWORD = "A very good password 2026";
    private static final String ADMIN = "test-web-admin-token";
    private String schema;
    private Database database;
    private Accounts accounts;
    private WebStore store;
    private WebServer server;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    record Session(String cookie, String csrf, UUID id) { }

    @BeforeEach void setup() throws Exception {
        schema = "railway_web_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection=base(); Statement query=connection.createStatement()) { query.execute("CREATE SCHEMA " + schema); }
        String url = System.getenv("TEST_DB_URL");
        Config config = new Config(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
            System.getenv().getOrDefault("TEST_DB_USER", "railway"), System.getenv("TEST_DB_PASSWORD"), ADMIN, "127.0.0.1", 0, 8, 32, 1000);
        database = new Database(config); database.initialize(false); accounts = new Accounts(database); store = new WebStore(database);
        server = new WebServer(config, database, "127.0.0.1", 0, false); server.start();
    }
    @AfterEach void cleanup() throws Exception {
        if (server != null) server.close();
        try (Connection connection=base(); Statement query=connection.createStatement()) { query.execute("DROP SCHEMA " + schema + " CASCADE"); }
    }
    private Connection base() throws SQLException { return DriverManager.getConnection(System.getenv("TEST_DB_URL"), System.getenv().getOrDefault("TEST_DB_USER", "railway"), System.getenv("TEST_DB_PASSWORD")); }
    private HttpResponse<String> request(String path, JsonNode data, Session session) throws Exception {
        return request(path, data, session, Map.of());
    }
    private HttpResponse<String> request(String path, JsonNode data, Session session, Map<String,String> overrides) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(15));
        Map<String,String> headers = new HashMap<>();
        if (data != null) { headers.put("Content-Type", "application/json"); headers.put("X-Requested-With", "railway-web"); builder.POST(HttpRequest.BodyPublishers.ofString(data.toString())); }
        else builder.GET();
        if (session != null) { headers.put("Cookie", session.cookie()); if (data != null) headers.put("X-CSRF-Token", session.csrf()); }
        headers.putAll(overrides); headers.forEach((key,value) -> { if (!value.isEmpty()) builder.header(key,value); });
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response) throws Exception { assertEquals(200,response.statusCode(),response.body()); return Database.JSON.readTree(response.body()).path("data"); }
    private String code(HttpResponse<String> response) throws Exception { return Database.JSON.readTree(response.body()).path("error").path("code").asText(); }
    private ObjectNode credentials(String email) { return Database.JSON.createObjectNode().put("email", email).put("password", PASSWORD); }
    private Session session(String email) throws Exception {
        ObjectNode registration=credentials(email).put("name","Alice Smith"); data(request("/api/register", registration, null));
        HttpResponse<String> response=request("/api/login",credentials(email),null); JsonNode user=data(response);
        return new Session(response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0], user.path("csrfToken").asText(), UUID.fromString(user.path("id").asText()));
    }
    private ObjectNode booking(int count) {
        var body=Database.JSON.createObjectNode().put("train",6773).put("date","2030-03-08").put("class","AC").put("requestId",UUID.randomUUID().toString());
        var names=body.putArray("passengers"); for(int i=0;i<count;i++) names.add("Passenger " + i); return body;
    }
    private void release() throws SQLException { database.release(6773, LocalDate.of(2030,3,8),1,1); }
    private void sql(String command) throws SQLException { try(Connection connection=database.open();Statement query=connection.createStatement()){query.execute(command);} }
    private int scalar(String command) throws SQLException { try(Connection connection=database.open();Statement query=connection.createStatement();ResultSet result=query.executeQuery(command)){result.next();return result.getInt(1);} }

    @Test void pageAndAssetsHaveSecurityHeaders() throws Exception {
        HttpResponse<String> page=request("/",null,null);assertEquals(200,page.statusCode()); assertTrue(page.body().contains("Find a journey"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
        assertEquals("nosniff",page.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals(200,request("/app.js",null,null).statusCode()); assertEquals(200,request("/styles.css",null,null).statusCode());
        assertEquals(404,request("/missing",null,null).statusCode());
    }
    @Test void registrationLoginAndSessionWork() throws Exception {
        data(request("/api/register",credentials(" Alice@Example.com ").put("name","Alice Smith"),null));
        HttpResponse<String> response=request("/api/login",credentials("alice@example.com"),null); JsonNode user=data(response);
        String cookie=response.headers().firstValue("Set-Cookie").orElseThrow(); assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));
        Session session=new Session(cookie.split(";")[0],user.path("csrfToken").asText(),UUID.fromString(user.path("id").asText()));
        assertEquals("alice@example.com",data(request("/api/me",null,session)).path("email").asText());
        try(Connection connection=database.open();Statement query=connection.createStatement();ResultSet result=query.executeQuery("SELECT password_hash FROM accounts")) {result.next();assertTrue(Passwords.verify(PASSWORD,result.getString(1)));assertNotEquals(PASSWORD,result.getString(1));}
        assertEquals(1,scalar("SELECT count(*) FROM sessions WHERE length(token_hash)=64"));
    }
    @Test void duplicateEmailAndWeakPasswordAreRejected() throws Exception {
        session("alice@example.com");
        HttpResponse<String> duplicate=request("/api/register",credentials("ALICE@example.com").put("name","Bob"),null);assertEquals(409,duplicate.statusCode());assertEquals("EMAIL_EXISTS",code(duplicate));
        HttpResponse<String> weak=request("/api/register",credentials("bob@example.com").put("name","Bob").put("password","short"),null);assertEquals(400,weak.statusCode());
    }
    @Test void incorrectAndMissingAccountsHaveSameLoginError() throws Exception {
        session("alice@example.com");
        var incorrect=request("/api/login",credentials("alice@example.com").put("password","wrong password"),null);
        var absent=request("/api/login",credentials("nobody@example.com"),null);
        assertEquals(401,incorrect.statusCode());assertEquals(incorrect.body(),absent.body());
    }
    @Test void privateEndpointsRequireLogin() throws Exception {
        assertEquals(401,request("/api/bookings",null,null).statusCode());
        assertEquals(401,request("/api/book",booking(1),null).statusCode());
        assertEquals(401,request("/api/cancel",Database.JSON.createObjectNode().put("pnr",UUID.randomUUID().toString()),null).statusCode());
    }
    @Test void csrfAndContentTypeAreChecked() throws Exception {
        Session session=session("alice@example.com");release();
        assertEquals(403,request("/api/book",booking(1),session,Map.of("X-CSRF-Token","wrong")).statusCode());
        assertEquals(403,request("/api/book",booking(1),session,Map.of("X-Requested-With","")).statusCode());
        assertEquals(403,request("/api/book",booking(1),session,Map.of("Sec-Fetch-Site","cross-site")).statusCode());
        assertEquals(415,request("/api/book",booking(1),session,Map.of("Content-Type","text/plain")).statusCode());
        assertEquals(0,scalar("SELECT count(*) FROM tickets"));
    }
    @Test void usersCannotViewOrCancelOtherPeoplesTickets() throws Exception {
        Session alice=session("alice@example.com"),bob=session("bob@example.com");release();
        JsonNode ticket=data(request("/api/book",booking(2),alice));var pnr=Database.JSON.createObjectNode().put("pnr",ticket.path("pnr").asText());
        assertEquals(404,request("/api/ticket",pnr,bob).statusCode());assertEquals(404,request("/api/cancel",pnr,bob).statusCode());
        assertTrue(data(request("/api/bookings",null,bob)).isEmpty());assertEquals(1,data(request("/api/bookings",null,alice)).size());
        assertThrows(RequestException.class,()->database.ticket(UUID.fromString(ticket.path("pnr").asText())));
    }
    @Test void cancellationReturnsCapacityAndPreservesHistory() throws Exception {
        Session user=session("alice@example.com");release();
        JsonNode original=data(request("/api/book",booking(18),user));var pnr=Database.JSON.createObjectNode().put("pnr",original.path("pnr").asText());
        assertEquals("CANCELLED",data(request("/api/cancel",pnr,user)).path("status").asText());
        assertEquals(0,scalar("SELECT sum(seats_booked) FROM seat_inventory"));
        JsonNode replacement=data(request("/api/book",booking(18),user));assertEquals(1,replacement.path("passengers").get(0).path("berth").asInt());
        assertEquals(18,scalar("SELECT count(*) FROM passengers WHERE active"));assertEquals(36,scalar("SELECT count(*) FROM passengers"));
        assertEquals(2,data(request("/api/bookings",null,user)).size());
    }
    @Test void repeatedCancellationCannotFreeReplacementSeats() throws Exception {
        Session user=session("alice@example.com");release();
        var pnr=Database.JSON.createObjectNode().put("pnr",data(request("/api/book",booking(5),user)).path("pnr").asText());
        data(request("/api/cancel",pnr,user));data(request("/api/book",booking(5),user));data(request("/api/cancel",pnr,user));
        assertEquals(5,scalar("SELECT sum(seats_booked) FROM seat_inventory"));assertEquals(5,scalar("SELECT count(*) FROM passengers WHERE active"));
    }
    @Test void freedSeatsInMiddleAreReusedWithoutMovingOthers() throws Exception {
        Session user=session("alice@example.com");release();
        data(request("/api/book",booking(3),user));JsonNode middle=data(request("/api/book",booking(3),user));JsonNode last=data(request("/api/book",booking(3),user));
        data(request("/api/cancel",Database.JSON.createObjectNode().put("pnr",middle.path("pnr").asText()),user));
        JsonNode next=data(request("/api/book",booking(4),user));
        assertEquals(4,next.path("passengers").get(0).path("berth").asInt());assertEquals(10,next.path("passengers").get(3).path("berth").asInt());
        assertEquals(7,last.path("passengers").get(0).path("berth").asInt());assertEquals(10,scalar("SELECT count(*) FROM passengers WHERE active"));
    }
    @Test void duplicateBookingRequestReturnsSameTicket() throws Exception {
        Session user=session("alice@example.com");release();ObjectNode body=booking(2);
        JsonNode first=data(request("/api/book",body,user)),second=data(request("/api/book",body,user));assertEquals(first,second);
        assertEquals(1,scalar("SELECT count(*) FROM tickets"));assertEquals(2,scalar("SELECT sum(seats_booked) FROM seat_inventory"));
        body.put("class","SL");assertEquals(409,request("/api/book",body,user).statusCode());assertEquals(1,scalar("SELECT count(*) FROM tickets"));
    }
    @Test void concurrentBookingRetriesCreateOneTicket() throws Exception {
        Session user=session("alice@example.com");release();ObjectNode body=booking(3);Set<String> pnrs=new HashSet<>();
        try(ExecutorService executor=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<JsonNode>>();for(int i=0;i<6;i++)futures.add(executor.submit(()->store.book(user.id(),body)));
            for(var future:futures)pnrs.add(future.get().path("pnr").asText());
        }
        assertEquals(1,pnrs.size());assertEquals(1,scalar("SELECT count(*) FROM tickets"));assertEquals(3,scalar("SELECT sum(seats_booked) FROM seat_inventory"));
    }
    @Test void concurrentCancellationsRestoreCapacityOnce() throws Exception {
        Session user=session("alice@example.com");release();UUID pnr=UUID.fromString(data(request("/api/book",booking(6),user)).path("pnr").asText());
        try(ExecutorService executor=Executors.newFixedThreadPool(6)) {
            var futures=new ArrayList<Future<JsonNode>>();for(int i=0;i<6;i++)futures.add(executor.submit(()->store.cancel(user.id(),pnr)));
            for(var future:futures)assertEquals("CANCELLED",future.get().path("status").asText());
        }
        assertEquals(0,scalar("SELECT sum(seats_booked) FROM seat_inventory"));assertEquals(0,scalar("SELECT count(*) FROM passengers WHERE active"));
    }
    @Test void concurrentCancellationAndBookingsKeepInventoryConsistent() throws Exception {
        Session user=session("alice@example.com");release();UUID pnr=UUID.fromString(data(request("/api/book",booking(18),user)).path("pnr").asText());
        try(ExecutorService executor=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<?>>();futures.add(executor.submit(()->store.cancel(user.id(),pnr)));
            for(int i=0;i<20;i++)futures.add(executor.submit(()->{try{return store.book(user.id(),booking(1));}catch(SQLException e){assertTrue(e.getMessage().contains("SEATS_NOT_AVAILABLE"));return null;}}));
            for(var future:futures)future.get();
        }
        int booked=scalar("SELECT sum(seats_booked) FROM seat_inventory");assertTrue(booked<=18);assertEquals(booked,scalar("SELECT count(*) FROM passengers WHERE active"));
        assertEquals(booked,scalar("SELECT count(DISTINCT seat_index) FROM passengers WHERE active"));
    }
    @Test void logoutAndSessionExpiryRevokeAccess() throws Exception {
        Session user=session("alice@example.com");data(request("/api/logout",Database.JSON.createObjectNode(),user));assertEquals(401,request("/api/me",null,user).statusCode());
        var login=request("/api/login",credentials("alice@example.com"),null);JsonNode profile=data(login);
        Session expired=new Session(login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],profile.path("csrfToken").asText(),user.id());
        sql("UPDATE sessions SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1 minute'");assertEquals(401,request("/api/me",null,expired).statusCode());
    }
    @Test void dateSearchIncludesOnlyMatchingServiceDayAndAvailability() throws Exception {
        sql("INSERT INTO routes VALUES (6773,'A','C','09:00','10:00',4,0),(2,'A','C','09:00','10:00',0,0)");release();
        JsonNode routes=data(request("/api/search",Database.JSON.createObjectNode().put("source","A").put("destination","C").put("date","2030-03-08"),null));
        assertEquals(1,routes.size());assertEquals(18,routes.get(0).path("availability").get(0).path("available").asInt());
        assertEquals(2,data(request("/api/stations",null,null)).size());
    }
    @Test void adminReleaseRequiresAccountAndCorrectAdminToken() throws Exception {
        Session user=session("alice@example.com");var body=Database.JSON.createObjectNode().put("train",6773).put("date","2030-03-08").put("acCoaches",1).put("sleeperCoaches",1).put("adminToken","wrong");
        assertEquals(401,request("/api/admin/release",body,user).statusCode());body.put("adminToken",ADMIN);data(request("/api/admin/release",body,user));
        assertEquals(409,request("/api/admin/release",body,user).statusCode());
    }
    @Test void oversizedBodyIsRejected() throws Exception {
        var body=Database.JSON.createObjectNode().put("source","x".repeat(33000));assertEquals(413,request("/api/search",body,null).statusCode());
    }
    @Test void authenticationRateLimitRejectsExcessAttempts() throws Exception {
        var input=credentials("nobody@example.com");
        for(int i=0;i<20;i++) assertEquals(401,request("/api/login",input,null).statusCode());
        var denied=request("/api/login",input,null);assertEquals(429,denied.statusCode());assertEquals("RATE_LIMITED",code(denied));
    }
}
