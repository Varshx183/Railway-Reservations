package dev.railway;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RailwayClient {
    private RailwayClient() { }

    public static JsonNode request(String host, int port, JsonNode request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000); socket.setSoTimeout(25000);
            try (BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                output.write(request.toString()); output.newLine(); output.flush();
                String response = RailwayServer.readLine(input, 4 * 1024 * 1024);
                if (response == null) throw new EOFException("Server closed the connection before responding (it may be busy).");
                return Database.JSON.readTree(response);
            }
        }
    }

    public static void run(String host, int port, Path file, PrintStream out) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(25000);
            try (BufferedReader requests = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                 BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = requests.readLine()) != null) {
                    if (line.isBlank()) continue;
                    output.write(line); output.newLine(); output.flush();
                    if (line.strip().equals("#")) return;
                    String response = RailwayServer.readLine(input, 4 * 1024 * 1024);
                    if (response == null) throw new EOFException("Server closed the connection before responding (it may be busy).");
                    out.println(response);
                }
                // No sentinel is required: normal EOF in the file ends the connection safely.
            }
        }
    }
}
