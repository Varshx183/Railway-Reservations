package dev.railway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;

final class WebStore {
    private final Database database;
    WebStore(Database database) { this.database = database; }

    JsonNode book(UUID owner, JsonNode request) throws SQLException {
        int train = RequestHandler.number(request, "train", 1, Integer.MAX_VALUE);
        LocalDate date = RequestHandler.date(request);
        String type = RequestHandler.text(request, "class", 2).toUpperCase(Locale.ROOT);
        if (!Set.of("AC", "SL").contains(type)) throw invalid("Class must be AC or SL.");
        JsonNode people = request.get("passengers");
        if (people == null || !people.isArray() || people.isEmpty() || people.size() > 100) throw invalid("Enter 1 to 100 passenger names.");
        List<String> names = new ArrayList<>();
        for (JsonNode person : people) {
            if (!person.isTextual() || person.asText().strip().isEmpty() || person.asText().strip().length() > 100) throw invalid("Passenger names must contain 1 to 100 characters.");
            names.add(person.asText().strip());
        }
        UUID requestId = uuid(RequestHandler.text(request, "requestId", 36));
        ObjectNode canonical = Database.JSON.createObjectNode().put("train", train).put("date", date.toString()).put("class", type);
        ArrayNode array = canonical.putArray("passengers"); names.forEach(array::add);
        String fingerprint = Passwords.digest(canonical.toString());
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                // Serialize retry checks for this user, then lock the inventory in the SQL function.
                try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM accounts WHERE id=? FOR UPDATE")) {
                    lock.setObject(1, owner);
                    try (ResultSet row = lock.executeQuery()) { if (!row.next()) throw Accounts.unauthorized(); }
                }
                try (PreparedStatement lookup = connection.prepareStatement("SELECT pnr, request_hash FROM booking_requests WHERE owner_id=? AND request_id=?")) {
                    lookup.setObject(1, owner); lookup.setObject(2, requestId);
                    try (ResultSet result = lookup.executeQuery()) {
                        if (result.next()) {
                            if (!result.getString(2).equals(fingerprint)) throw new RequestException("REQUEST_ID_CONFLICT", "This booking request ID was already used for different details.");
                            JsonNode ticket = database.ticket(connection, result.getObject(1, UUID.class));
                            connection.commit(); return ticket;
                        }
                    }
                }
                UUID pnr;
                java.sql.Array passengers = connection.createArrayOf("text", names.toArray(String[]::new));
                try (PreparedStatement query = connection.prepareStatement("SELECT book_tickets(?, ?, ?, ?)")) {
                    query.setInt(1, train); query.setDate(2, java.sql.Date.valueOf(date)); query.setString(3, type); query.setArray(4, passengers);
                    try (ResultSet result = query.executeQuery()) { result.next(); pnr = result.getObject(1, UUID.class); }
                } finally { passengers.free(); }
                try (PreparedStatement query = connection.prepareStatement("UPDATE tickets SET owner_id=? WHERE pnr=?")) {
                    query.setObject(1, owner); query.setObject(2, pnr); query.executeUpdate();
                }
                try (PreparedStatement query = connection.prepareStatement("INSERT INTO booking_requests VALUES (?, ?, ?, ?)")) {
                    query.setObject(1, owner); query.setObject(2, requestId); query.setString(3, fingerprint); query.setObject(4, pnr); query.executeUpdate();
                }
                JsonNode ticket = database.ticket(connection, pnr); connection.commit(); return ticket;
            } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
        }
    }
    JsonNode bookings(UUID owner) throws SQLException {
        ArrayNode data = Database.JSON.createArrayNode();
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement("SELECT pnr FROM tickets WHERE owner_id=? ORDER BY booked_at DESC, pnr LIMIT 100")) {
            query.setObject(1, owner);
            List<UUID> pnrs = new ArrayList<>();
            try (ResultSet result = query.executeQuery()) { while (result.next()) pnrs.add(result.getObject(1, UUID.class)); }
            for (UUID pnr : pnrs) data.add(database.ticket(connection, pnr));
        }
        return data;
    }
    JsonNode ticket(UUID owner, UUID pnr) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement("SELECT 1 FROM tickets WHERE owner_id=? AND pnr=?")) {
            query.setObject(1, owner); query.setObject(2, pnr);
            try (ResultSet result = query.executeQuery()) { if (!result.next()) throw missing(); }
            return database.ticket(connection, pnr);
        }
    }
    JsonNode cancel(UUID owner, UUID pnr) throws SQLException {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement query = connection.prepareStatement("SELECT cancel_ticket(?, ?)")) {
                    query.setObject(1, pnr); query.setObject(2, owner); query.executeQuery().close();
                }
                JsonNode ticket = database.ticket(connection, pnr); connection.commit(); return ticket;
            } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
        }
    }
    JsonNode stations() throws SQLException {
        ArrayNode data = Database.JSON.createArrayNode();
        try (Connection connection = database.open(); Statement query = connection.createStatement(); ResultSet results = query.executeQuery("SELECT source FROM routes UNION SELECT destination FROM routes ORDER BY 1")) {
            while (results.next()) data.add(results.getString(1));
        }
        return data;
    }
    JsonNode search(JsonNode request) throws SQLException, IOException {
        String source = RequestHandler.text(request, "source", 100), destination = RequestHandler.text(request, "destination", 100);
        if (source.equalsIgnoreCase(destination)) throw invalid("Choose different stations.");
        LocalDate date = RequestHandler.date(request);
        ArrayNode data = Database.JSON.createArrayNode();
        for (JsonNode route : database.search(source, destination)) {
            if (route.path("departureDay").asInt() != date.getDayOfWeek().getValue() - 1) continue;
            ObjectNode item = (ObjectNode) route;
            item.put("date", date.toString());
            if (item.path("type").asText().equals("DIRECT")) {
                try { item.set("availability", database.availability(item.path("train").asInt(), date).path("classes")); }
                catch (RequestException e) {
                    if (!e.code.equals("TRAIN_NOT_AVAILABLE")) throw e;
                    item.set("availability", Database.JSON.createArrayNode());
                }
            }
            data.add(item);
        }
        return data;
    }
    static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("Ticket and request IDs must be UUIDs.");
        return UUID.fromString(value);
    }
    private static RequestException invalid(String message) { return new RequestException("INVALID_REQUEST", message); }
    private static RequestException missing() { return new RequestException("TICKET_NOT_FOUND", "Ticket not found."); }
}
