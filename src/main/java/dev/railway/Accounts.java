package dev.railway;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.Locale;
import java.util.UUID;

final class Accounts {
    record User(UUID id, String email, String name) {
        ObjectNode json() { return Database.JSON.createObjectNode().put("id", id.toString()).put("email", email).put("name", name); }
    }
    record Login(User user, String token) { }
    private final Database database;
    private final String dummyHash = Passwords.hash("dummy-authentication-password");
    Accounts(Database database) { this.database = database; }

    User register(String email, String name, String password) throws SQLException {
        email = email(email);
        if (name == null || name.strip().isEmpty() || name.strip().length() > 80) throw invalid("Name must contain 1 to 80 characters.");
        validatePassword(password);
        String hash = Passwords.hash(password);
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement("INSERT INTO accounts(email, display_name, password_hash) VALUES (?, ?, ?) RETURNING id")) {
            query.setString(1, email); query.setString(2, name.strip()); query.setString(3, hash);
            try (ResultSet results = query.executeQuery()) { results.next(); return new User(results.getObject(1, UUID.class), email, name.strip()); }
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) throw new RequestException("EMAIL_EXISTS", "An account with this email already exists.");
            throw e;
        }
    }
    Login login(String email, String password) throws SQLException {
        email = email(email);
        if (password == null || password.length() > 128) throw invalid("Invalid password.");
        User user = null; String hash = dummyHash;
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement("SELECT id, display_name, password_hash FROM accounts WHERE email=?")) {
            query.setString(1, email);
            try (ResultSet results = query.executeQuery()) {
                if (results.next()) { user = new User(results.getObject(1, UUID.class), email, results.getString(2)); hash = results.getString(3); }
            }
        }
        boolean valid = Passwords.verify(password, hash);
        if (!valid || user == null) throw new RequestException("INVALID_CREDENTIALS", "Email or password is incorrect.");
        String token = Passwords.token();
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement("INSERT INTO sessions VALUES (?, ?, CURRENT_TIMESTAMP + INTERVAL '24 hours')")) {
            query.setString(1, Passwords.digest(token)); query.setObject(2, user.id()); query.executeUpdate();
            try (Statement cleanup = connection.createStatement()) { cleanup.executeUpdate("DELETE FROM sessions WHERE expires_at < CURRENT_TIMESTAMP"); }
        }
        return new Login(user, token);
    }
    User authenticate(String token) throws SQLException {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unauthorized();
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement("SELECT a.id, a.email, a.display_name FROM accounts a JOIN sessions s ON a.id=s.account_id WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP")) {
            query.setString(1, Passwords.digest(token));
            try (ResultSet results = query.executeQuery()) {
                if (!results.next()) throw unauthorized();
                return new User(results.getObject(1, UUID.class), results.getString(2), results.getString(3));
            }
        }
    }
    void logout(String token) throws SQLException {
        try (Connection connection = database.open(); PreparedStatement query = connection.prepareStatement("DELETE FROM sessions WHERE token_hash=?")) {
            query.setString(1, Passwords.digest(token)); query.executeUpdate();
        }
    }
    private static String email(String value) {
        if (value == null) throw invalid("Email is required.");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}")) throw invalid("Enter a valid email address.");
        return value;
    }
    private static void validatePassword(String password) {
        if (password == null || password.length() < 10 || password.length() > 128) throw invalid("Password must contain 10 to 128 characters.");
    }
    static RequestException unauthorized() { return new RequestException("UNAUTHORIZED", "Please sign in to continue."); }
    private static RequestException invalid(String message) { return new RequestException("INVALID_REQUEST", message); }
}
