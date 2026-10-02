package dev.railway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public final class Database {
    static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Config config;
    public Database(Config config) { this.config = config; }

    Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.dbUser());
        properties.setProperty("password", config.dbPassword());
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "20");
        properties.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=5000");
        return DriverManager.getConnection(config.dbUrl(), properties);
    }

    public void initialize(boolean seed) throws SQLException, IOException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                executeResource(connection, "/database/001_schema.sql");
                if (seed) executeResource(connection, "/database/002_seed.sql");
                executeResource(connection, "/database/003_accounts_cancellation.sql");
                connection.commit();
            } catch (SQLException | IOException e) { connection.rollback(); throw e; }
        }
    }

    private void executeResource(Connection connection, String name) throws IOException, SQLException {
        try (var input = Database.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing database resource: " + name);
            try (Statement statement = connection.createStatement()) {
                statement.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    void verify() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT train_id FROM seat_inventory LIMIT 0").close();
        }
    }

    void prepareDemo() throws SQLException, IOException {
        initialize(true);
        try (Connection connection = open(); Statement query = connection.createStatement()) {
            query.execute("SELECT release_train(t.train_id, d::date, 2, 3) FROM (SELECT DISTINCT train_id FROM routes) t CROSS JOIN generate_series(CURRENT_DATE, CURRENT_DATE + 14, INTERVAL '1 day') d");
        }
    }

    JsonNode release(int train, LocalDate date, int ac, int sl) throws SQLException {
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT release_train(?, ?, ?, ?)")) {
            query.setInt(1, train); query.setDate(2, Date.valueOf(date)); query.setInt(3, ac); query.setInt(4, sl);
            try (ResultSet results = query.executeQuery()) {
                results.next();
                if (!results.getBoolean(1)) throw new RequestException("TRAIN_ALREADY_RELEASED", "Train is already available for that date.");
            }
        }
        return JSON.createObjectNode().put("train", train).put("date", date.toString()).put("acCoaches", ac).put("sleeperCoaches", sl);
    }

    JsonNode book(int train, LocalDate date, String travelClass, List<String> names) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            java.sql.Array passengers = connection.createArrayOf("text", names.toArray(String[]::new));
            try {
                UUID pnr;
                try (PreparedStatement query = connection.prepareStatement("SELECT book_tickets(?, ?, ?, ?)")) {
                    query.setInt(1, train); query.setDate(2, Date.valueOf(date)); query.setString(3, travelClass); query.setArray(4, passengers);
                    try (ResultSet results = query.executeQuery()) { results.next(); pnr = results.getObject(1, UUID.class); }
                }
                JsonNode ticket = ticket(connection, pnr);
                connection.commit();
                return ticket;
            } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
            finally { passengers.free(); }
        }
    }

    JsonNode ticket(UUID pnr) throws SQLException {
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT owner_id FROM tickets WHERE pnr=?")) {
            query.setObject(1, pnr);
            try (ResultSet result = query.executeQuery()) {
                if (result.next() && result.getObject(1) != null) throw new RequestException("UNAUTHORIZED", "Account tickets require the authenticated web interface.");
            }
            return ticket(connection, pnr);
        }
    }

    JsonNode ticket(Connection connection, UUID pnr) throws SQLException {
        ObjectNode ticket = JSON.createObjectNode();
        try (PreparedStatement query = connection.prepareStatement("SELECT train_id, journey_date, travel_class, status FROM tickets WHERE pnr = ?")) {
            query.setObject(1, pnr);
            try (ResultSet results = query.executeQuery()) {
                if (!results.next()) throw new RequestException("TICKET_NOT_FOUND", "No ticket found for that PNR.");
                ticket.put("pnr", pnr.toString()).put("train", results.getInt(1)).put("date", results.getDate(2).toString()).put("class", results.getString(3));
                ticket.put("status", results.getString(4));
            }
        }
        ArrayNode people = ticket.putArray("passengers");
        try (PreparedStatement query = connection.prepareStatement("SELECT passenger_name, coach_number, berth_number, berth_type FROM passengers WHERE pnr = ? ORDER BY passenger_order")) {
            query.setObject(1, pnr);
            try (ResultSet results = query.executeQuery()) {
                while (results.next()) people.addObject().put("name", results.getString(1)).put("coach", results.getInt(2)).put("berth", results.getInt(3)).put("berthType", results.getString(4));
            }
        }
        return ticket;
    }

    JsonNode availability(int train, LocalDate date) throws SQLException {
        ObjectNode data = JSON.createObjectNode().put("train", train).put("date", date.toString());
        ArrayNode classes = data.putArray("classes");
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT travel_class, coach_count * seats_per_coach, seats_booked FROM seat_inventory WHERE train_id = ? AND journey_date = ? ORDER BY travel_class")) {
            query.setInt(1, train); query.setDate(2, Date.valueOf(date));
            try (ResultSet results = query.executeQuery()) {
                while (results.next()) classes.addObject().put("class", results.getString(1)).put("capacity", results.getInt(2)).put("booked", results.getInt(3)).put("available", results.getInt(2) - results.getInt(3));
            }
        }
        if (classes.isEmpty()) throw new RequestException("TRAIN_NOT_AVAILABLE", "Train is not available for that date.");
        return data;
    }

    JsonNode search(String source, String destination) throws SQLException, IOException {
        try (Connection connection = open(); PreparedStatement query = connection.prepareStatement("SELECT search_routes(?, ?)")) {
            query.setString(1, source); query.setString(2, destination);
            try (ResultSet results = query.executeQuery()) { results.next(); return JSON.readTree(results.getString(1)); }
        }
    }
}
