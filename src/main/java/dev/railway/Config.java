package dev.railway;

import java.util.Map;

public record Config(String dbUrl, String dbUser, String dbPassword, String adminToken,
                     String host, int port, int workers, int queueSize, int socketTimeoutMs) {
    public Config {
        if (dbUrl == null || !dbUrl.startsWith("jdbc:postgresql:") || dbUser == null || dbUser.isBlank()
                || dbPassword == null || dbPassword.isBlank() || adminToken == null || adminToken.isBlank()
                || host == null || host.isBlank() || port < 0 || port > 65535 || workers < 1
                || queueSize < 1 || socketTimeoutMs < 1) {
            throw new IllegalArgumentException("Invalid configuration; DB_PASSWORD and ADMIN_TOKEN are required.");
        }
    }

    public static Config fromEnvironment(Map<String, String> env) {
        return new Config(env.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/railway"),
                env.getOrDefault("DB_USER", "railway"), env.get("DB_PASSWORD"), env.get("ADMIN_TOKEN"),
                env.getOrDefault("SERVER_HOST", "127.0.0.1"), number(env, "SERVER_PORT", 7008),
                number(env, "SERVER_WORKERS", 8), number(env, "SERVER_QUEUE_SIZE", 32),
                number(env, "SOCKET_TIMEOUT_MS", 15000));
    }

    private static int number(Map<String, String> env, String key, int fallback) {
        try { return Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer."); }
    }

    @Override public String toString() {
        return "Config[host=" + host + ", port=" + port + ", workers=" + workers + ", credentials=REDACTED]";
    }
}
