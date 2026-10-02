package dev.railway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

public final class RequestHandler {
    private final Database database;
    private final byte[] adminToken;
    public RequestHandler(Database database, String token) {
        this.database = database; this.adminToken = token.getBytes(StandardCharsets.UTF_8);
    }

    public ObjectNode handle(String line) {
        try {
            JsonNode request = Database.JSON.readTree(line);
            if (request == null || !request.isObject()) throw invalid("Request must be a JSON object.");
            String operation = text(request, "operation", 30).toUpperCase(Locale.ROOT);
            JsonNode data = switch (operation) {
                case "PING" -> Database.JSON.createObjectNode().put("message", "pong");
                case "RELEASE" -> {
                    JsonNode supplied = request.get("adminToken");
                    if (supplied == null || !supplied.isTextual() || !MessageDigest.isEqual(adminToken, supplied.asText().getBytes(StandardCharsets.UTF_8)))
                        throw new RequestException("UNAUTHORIZED", "A valid admin token is required to release trains.");
                    int ac = number(request, "acCoaches", 0, 100), sl = number(request, "sleeperCoaches", 0, 100);
                    if (ac + sl == 0) throw invalid("At least one coach is required.");
                    yield database.release(number(request, "train", 1, Integer.MAX_VALUE), date(request), ac, sl);
                }
                case "BOOK" -> {
                    String travelClass = text(request, "class", 2).toUpperCase(Locale.ROOT);
                    if (!travelClass.equals("AC") && !travelClass.equals("SL")) throw invalid("Class must be AC or SL.");
                    JsonNode people = request.get("passengers");
                    if (people == null || !people.isArray() || people.size() < 1 || people.size() > 100)
                        throw invalid("Passengers must contain between 1 and 100 names.");
                    var names = new ArrayList<String>();
                    for (JsonNode person : people) {
                        if (!person.isTextual() || person.asText().strip().isEmpty() || person.asText().strip().length() > 100)
                            throw invalid("Each passenger name must contain 1 to 100 characters.");
                        names.add(person.asText().strip());
                    }
                    yield database.book(number(request, "train", 1, Integer.MAX_VALUE), date(request), travelClass, names);
                }
                case "AVAILABILITY" -> database.availability(number(request, "train", 1, Integer.MAX_VALUE), date(request));
                case "TICKET" -> {
                    String pnr = text(request, "pnr", 36);
                    if (!pnr.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw invalid("PNR must be a UUID.");
                    yield database.ticket(UUID.fromString(pnr));
                }
                case "SEARCH" -> {
                    String source = text(request, "source", 100), destination = text(request, "destination", 100);
                    if (source.equalsIgnoreCase(destination)) throw invalid("Source and destination must differ.");
                    yield database.search(source, destination);
                }
                default -> throw invalid("Unknown operation. Use PING, RELEASE, BOOK, AVAILABILITY, TICKET or SEARCH.");
            };
            ObjectNode response = Database.JSON.createObjectNode().put("ok", true);
            response.set("data", data); return response;
        } catch (RequestException e) { return error(e.code, e.getMessage()); }
        catch (SQLException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if ("P0001".equals(e.getSQLState()) && message.contains("TRAIN_NOT_AVAILABLE")) return error("TRAIN_NOT_AVAILABLE", "Train is not available for that date.");
            if ("P0001".equals(e.getSQLState()) && message.contains("SEATS_NOT_AVAILABLE")) return error("SEATS_NOT_AVAILABLE", "Not enough seats remain for this group.");
            if ("22023".equals(e.getSQLState())) return error("INVALID_REQUEST", "Invalid booking or train details.");
            System.err.println("Database request failed (SQLSTATE " + e.getSQLState() + ").");
            return error("DATABASE_ERROR", "Database request failed; please retry later.");
        } catch (IOException e) { return error("INVALID_JSON", "Request must be valid JSON."); }
    }

    static ObjectNode error(String code, String message) {
        ObjectNode response = Database.JSON.createObjectNode().put("ok", false);
        response.putObject("error").put("code", code).put("message", message); return response;
    }
    private static RequestException invalid(String message) { return new RequestException("INVALID_REQUEST", message); }
    static String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().strip().isEmpty() || value.asText().strip().length() > maxLength)
            throw invalid(field + " must be a non-empty string of at most " + maxLength + " characters.");
        return value.asText().strip();
    }
    static int number(JsonNode node, String field, int min, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max)
            throw invalid(field + " must be an integer between " + min + " and " + max + ".");
        return value.intValue();
    }
    static LocalDate date(JsonNode node) {
        String value = text(node, "date", 10);
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw invalid("Date must use YYYY-MM-DD.");
        try {
            LocalDate parsed = LocalDate.parse(value);
            if (parsed.getYear() < 1) throw invalid("Date year must be between 0001 and 9999.");
            return parsed;
        }
        catch (DateTimeParseException e) { throw invalid("Date must be a valid calendar date."); }
    }
}
