package dev.railway;

import java.nio.file.Path;
import java.util.Arrays;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) {
            System.err.println("Command failed: " + safeMessage(e));
            System.exit(1);
        }
    }
    static void run(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            System.out.println("Railway Reservation System\n  init [--seed]       Create/upgrade schema and optionally load sample routes\n  demo                Load sample routes and open seats for the next 15 days\n  web                 Start browser application on port 8080\n  server              Start legacy TCP server\n  release TRAIN DATE AC_COACHES SL_COACHES  Open seats over TCP (requires ADMIN_TOKEN)\n  client FILE [HOST PORT]  Send JSON requests and print JSON responses\nSee README.md for environment variables and examples.");
            return;
        }
        if (args[0].equals("client")) {
            if (args.length != 2 && args.length != 4) throw new IllegalArgumentException("Usage: client FILE [HOST PORT]");
            RailwayClient.run(args.length == 4 ? args[2] : "127.0.0.1", args.length == 4 ? Integer.parseInt(args[3]) : 7008, Path.of(args[1]), System.out);
            return;
        }
        if (args[0].equals("release")) {
            if (args.length != 5) throw new IllegalArgumentException("Usage: release TRAIN DATE AC_COACHES SL_COACHES");
            String token = System.getenv("ADMIN_TOKEN");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("Set ADMIN_TOKEN to the server's admin token.");
            var request = Database.JSON.createObjectNode().put("operation", "RELEASE").put("adminToken", token)
                    .put("train", Integer.parseInt(args[1])).put("date", args[2])
                    .put("acCoaches", Integer.parseInt(args[3])).put("sleeperCoaches", Integer.parseInt(args[4]));
            var response = RailwayClient.request(System.getenv().getOrDefault("CLIENT_HOST", "127.0.0.1"),
                    Integer.parseInt(System.getenv().getOrDefault("CLIENT_PORT", "7008")), request);
            System.out.println(response);
            if (!response.path("ok").asBoolean()) throw new IllegalArgumentException(response.path("error").path("message").asText());
            return;
        }
        if (!java.util.Set.of("init", "server", "web", "demo").contains(args[0])) throw new IllegalArgumentException("Unknown command; use help.");
        if ((args[0].equals("web") || args[0].equals("demo")) && args.length != 1) throw new IllegalArgumentException("Usage: " + args[0]);
        if (args[0].equals("server") && args.length != 1) throw new IllegalArgumentException("Usage: server");
        if (args[0].equals("init") && !(args.length == 1 || Arrays.equals(args, new String[]{"init", "--seed"}))) throw new IllegalArgumentException("Usage: init [--seed]");
        Config config = Config.fromEnvironment(System.getenv());
        Database database = new Database(config);
        if (args[0].equals("demo")) { database.prepareDemo(); System.out.println("Demo routes and train availability are ready for the next 15 days."); return; }
        if (args[0].equals("init")) {
            database.initialize(args.length == 2);
            System.out.println("Database initialized" + (args.length == 2 ? " with sample routes." : "."));
            return;
        }
        database.verify();
        if (args[0].equals("web")) {
            String host = System.getenv().getOrDefault("WEB_HOST", "127.0.0.1");
            int port = Integer.parseInt(System.getenv().getOrDefault("WEB_PORT", "8080"));
            WebServer web = new WebServer(config, database, host, port, Boolean.parseBoolean(System.getenv().getOrDefault("COOKIE_SECURE", "false")));
            Runtime.getRuntime().addShutdownHook(new Thread(web::close, "railway-web-shutdown"));
            web.start();
            System.out.println("Railway web application: http://" + host + ":" + web.port());
            return;
        }
        try (RailwayServer server = new RailwayServer(config, database)) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "railway-shutdown"));
            System.out.println("Railway server listening on " + config.host() + ":" + server.port());
            server.serve();
        }
    }
    private static String safeMessage(Exception e) {
        if (e instanceof java.sql.SQLException sql) return "Database connection or schema error (SQLSTATE " + sql.getSQLState() + "). Check configuration and run init.";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
