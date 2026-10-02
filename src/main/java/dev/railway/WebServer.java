package dev.railway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

public final class WebServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers;
    private final Database database;
    private final Accounts accounts;
    private final WebStore store;
    private final String adminToken;
    private final boolean secureCookies;
    private boolean closed;
    private final Map<String, long[]> attempts = new LinkedHashMap<>();
    private static final Map<String, String> ASSETS = Map.of("/", "index.html", "/app.js", "app.js", "/styles.css", "styles.css");

    public WebServer(Config config, Database database, String host, int port, boolean secureCookies) throws IOException {
        this.database = database; this.accounts = new Accounts(database); this.store = new WebStore(database);
        this.adminToken = config.adminToken(); this.secureCookies = secureCookies;
        defaultProperty("jdk.httpserver.maxConnections", "96");
        defaultProperty("sun.net.httpserver.maxReqHeaderSize", "8192");
        defaultProperty("sun.net.httpserver.maxReqTime", "15000");
        defaultProperty("sun.net.httpserver.maxRspTime", "25000");
        server = HttpServer.create(new InetSocketAddress(host, port), 64);
        workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            headers(exchange);
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (!path.startsWith("/api/")) { asset(exchange, path, method); return; }
            if (!Set.of("GET", "POST").contains(method)) { send(exchange, 405, RequestHandler.error("METHOD_NOT_ALLOWED", "Use GET or POST.")); return; }
            if (method.equals("POST")) {
                if (!"application/json".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Type") == null ? "" : exchange.getRequestHeaders().getFirst("Content-Type").split(";")[0].strip())) throw new RequestException("UNSUPPORTED_MEDIA_TYPE", "Use application/json.");
                // A custom header forces browser cross-origin requests to preflight, which is not allowed.
                if (!"railway-web".equals(exchange.getRequestHeaders().getFirst("X-Requested-With"))) throw new RequestException("CSRF_FAILED", "Browser request verification failed.");
                String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
                if (site != null && !Set.of("same-origin", "none").contains(site)) throw new RequestException("CSRF_FAILED", "Cross-origin requests are not allowed.");
            }
            JsonNode body = method.equals("POST") ? body(exchange) : Database.JSON.createObjectNode();
            JsonNode data;
            if (path.equals("/api/register") && method.equals("POST")) {
                rateLimit(exchange);
                data = accounts.register(text(body, "email", 254), text(body, "name", 80), password(body)).json();
            } else if (path.equals("/api/login") && method.equals("POST")) {
                rateLimit(exchange);
                Accounts.Login login = accounts.login(text(body, "email", 254), password(body));
                cookie(exchange, login.token(), 86400);
                ObjectNode result = login.user().json().put("csrfToken", csrf(login.token())); data = result;
            } else if (path.equals("/api/stations") && method.equals("GET")) data = store.stations();
            else if (path.equals("/api/search") && method.equals("POST")) data = store.search(body);
            else if (path.equals("/api/availability") && method.equals("POST")) data = database.availability(RequestHandler.number(body, "train", 1, Integer.MAX_VALUE), RequestHandler.date(body));
            else {
                String token = token(exchange); Accounts.User user = accounts.authenticate(token);
                if (method.equals("POST") && !constant(csrf(token), exchange.getRequestHeaders().getFirst("X-CSRF-Token"))) throw new RequestException("CSRF_FAILED", "Session verification failed. Refresh and try again.");
                if (path.equals("/api/me") && method.equals("GET")) data = user.json().put("csrfToken", csrf(token));
                else if (path.equals("/api/logout") && method.equals("POST")) {
                    accounts.logout(token); cookie(exchange, "", 0); data = Database.JSON.createObjectNode().put("message", "Signed out.");
                } else if (path.equals("/api/bookings") && method.equals("GET")) data = store.bookings(user.id());
                else if (path.equals("/api/book") && method.equals("POST")) data = store.book(user.id(), body);
                else if (path.equals("/api/cancel") && method.equals("POST")) data = store.cancel(user.id(), WebStore.uuid(text(body, "pnr", 36)));
                else if (path.equals("/api/ticket") && method.equals("POST")) data = store.ticket(user.id(), WebStore.uuid(text(body, "pnr", 36)));
                else if (path.equals("/api/admin/release") && method.equals("POST")) {
                    if (!constant(adminToken, text(body, "adminToken", 1024))) throw new RequestException("UNAUTHORIZED", "Admin token is incorrect.");
                    int ac = RequestHandler.number(body, "acCoaches", 0, 100), sl = RequestHandler.number(body, "sleeperCoaches", 0, 100);
                    if (ac + sl == 0) throw new RequestException("INVALID_REQUEST", "At least one coach is required.");
                    data = database.release(RequestHandler.number(body, "train", 1, Integer.MAX_VALUE), RequestHandler.date(body), ac, sl);
                } else { send(exchange, 404, RequestHandler.error("NOT_FOUND", "Endpoint not found.")); return; }
            }
            ObjectNode response = Database.JSON.createObjectNode().put("ok", true); response.set("data", data); send(exchange, 200, response);
        } catch (RequestException e) {
            int status = switch (e.code) {
                case "UNAUTHORIZED", "INVALID_CREDENTIALS" -> 401;
                case "CSRF_FAILED" -> 403;
                case "TICKET_NOT_FOUND" -> 404;
                case "EMAIL_EXISTS", "TRAIN_ALREADY_RELEASED", "REQUEST_ID_CONFLICT" -> 409;
                case "RATE_LIMITED" -> 429;
                case "REQUEST_TOO_LARGE" -> 413;
                case "UNSUPPORTED_MEDIA_TYPE" -> 415;
                default -> 400;
            };
            send(exchange, status, RequestHandler.error(e.code, e.getMessage()));
        } catch (SQLException e) {
            String message = Objects.toString(e.getMessage(), "");
            if ("P0001".equals(e.getSQLState()) && message.contains("TICKET_NOT_FOUND")) send(exchange, 404, RequestHandler.error("TICKET_NOT_FOUND", "Ticket not found."));
            else if ("P0001".equals(e.getSQLState()) && message.contains("TRAIN_NOT_AVAILABLE")) send(exchange, 409, RequestHandler.error("TRAIN_NOT_AVAILABLE", "Train is not open for booking on this date."));
            else if ("P0001".equals(e.getSQLState()) && message.contains("SEATS_NOT_AVAILABLE")) send(exchange, 409, RequestHandler.error("SEATS_NOT_AVAILABLE", "Not enough seats remain for this group."));
            else { System.err.println("Web database request failed (SQLSTATE " + e.getSQLState() + ")."); send(exchange, 503, RequestHandler.error("DATABASE_ERROR", "Database unavailable. Try again later.")); }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { send(exchange, 400, RequestHandler.error("INVALID_JSON", "Request must be valid JSON.")); }
        catch (RuntimeException e) { System.err.println("Web request failed: " + e.getClass().getSimpleName()); send(exchange, 500, RequestHandler.error("SERVER_ERROR", "Request failed. Try again later.")); }
        finally { exchange.close(); }
    }
    private void headers(HttpExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.set("Cache-Control", "no-store"); headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY"); headers.set("Referrer-Policy", "same-origin");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
    }
    private void asset(HttpExchange exchange, String path, String method) throws IOException {
        if (!method.equals("GET")) { send(exchange, 405, RequestHandler.error("METHOD_NOT_ALLOWED", "Use GET.")); return; }
        String asset = ASSETS.get(path);
        if (asset == null) { send(exchange, 404, RequestHandler.error("NOT_FOUND", "Page not found.")); return; }
        try (var input = WebServer.class.getResourceAsStream("/web/" + asset)) {
            if (input == null) throw new IOException("Web asset is missing.");
            byte[] bytes = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", asset.endsWith(".js") ? "text/javascript; charset=utf-8" : asset.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        }
    }
    private JsonNode body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(32769);
        if (bytes.length > 32768) throw new RequestException("REQUEST_TOO_LARGE", "Request exceeds 32 KB.");
        JsonNode body = Database.JSON.readTree(bytes);
        if (body == null || !body.isObject()) throw new RequestException("INVALID_REQUEST", "Request must be a JSON object.");
        return body;
    }
    private void send(HttpExchange exchange, int status, JsonNode response) throws IOException {
        byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private String token(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String value : cookie.split(";")) if (value.strip().startsWith("railway_session=")) return value.strip().substring("railway_session=".length());
        return null;
    }
    private void cookie(HttpExchange exchange, String token, int seconds) {
        exchange.getResponseHeaders().add("Set-Cookie", "railway_session=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + seconds + (secureCookies ? "; Secure" : ""));
    }
    private synchronized void rateLimit(HttpExchange exchange) {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress(); long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e -> now - e.getValue()[0] > 60000);
        if (!attempts.containsKey(ip) && attempts.size() >= 10000) throw new RequestException("RATE_LIMITED", "Too many requests. Try again in a minute.");
        long[] entry = attempts.computeIfAbsent(ip, ignored -> new long[]{now, 0});
        if (++entry[1] > 20) throw new RequestException("RATE_LIMITED", "Too many sign-in attempts. Try again in a minute.");
    }
    private static String text(JsonNode body, String key, int limit) { return RequestHandler.text(body, key, limit); }
    private static String password(JsonNode body) {
        if (!body.path("password").isTextual() || body.path("password").asText().length() > 128) throw new RequestException("INVALID_REQUEST", "Password is required (maximum 128 characters).");
        return body.path("password").asText();
    }
    private static String csrf(String token) { return Passwords.digest("csrf:" + token); }
    private static boolean constant(String expected, String value) { return value != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8)); }
    private static void defaultProperty(String key, String value) { if (System.getProperty(key) == null) System.setProperty(key, value); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        server.stop(1); workers.shutdownNow();
        try { workers.awaitTermination(25, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
