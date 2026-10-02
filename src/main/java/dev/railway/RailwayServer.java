package dev.railway;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.*;

public final class RailwayServer implements AutoCloseable {
    static final int MAX_LINE_LENGTH = 32768;
    private final Config config;
    private final RequestHandler handler;
    private final ServerSocket listener;
    private final ThreadPoolExecutor workers;
    private final Set<Socket> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public RailwayServer(Config config, Database database) throws IOException {
        this.config = config; this.handler = new RequestHandler(database, config.adminToken());
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(config.host(), config.port()));
        workers = new ThreadPoolExecutor(config.workers(), config.workers(), 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueSize()));
    }

    public int port() { return listener.getLocalPort(); }

    public void serve() throws IOException {
        while (!closed) {
            Socket socket;
            try { socket = listener.accept(); }
            catch (SocketException e) { if (closed) return; throw e; }
            active.add(socket);
            if (closed) { disconnect(socket); return; }
            try { workers.execute(() -> handle(socket)); }
            catch (RejectedExecutionException e) {
                // Close promptly instead of blocking the accept loop on a slow client.
                disconnect(socket);
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(config.socketTimeoutMs());
            while (!closed) {
                String line;
                try { line = readLine(input); }
                catch (LineTooLongException e) { send(output, RequestHandler.error("REQUEST_TOO_LARGE", "Request exceeds 32768 characters.").toString()); break; }
                catch (SocketTimeoutException e) { send(output, RequestHandler.error("CLIENT_TIMEOUT", "Client was idle for too long.").toString()); break; }
                if (line == null || line.strip().equals("#")) break;
                send(output, handler.handle(line).toString());
            }
        } catch (IOException e) {
            // A client may disconnect at any point. Closing the socket frees its worker.
        } finally { active.remove(socket); }
    }

    static String readLine(Reader input) throws IOException {
        return readLine(input, MAX_LINE_LENGTH);
    }

    static String readLine(Reader input, int limit) throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = input.read()) != -1) {
            if (character == '\n') break;
            if (line.length() >= limit) throw new LineTooLongException();
            line.append((char) character);
        }
        if (character == -1 && line.isEmpty()) return null;
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') line.setLength(line.length() - 1);
        return line.toString();
    }

    private static void send(BufferedWriter output, String line) throws IOException {
        output.write(line); output.newLine(); output.flush();
    }
    private void disconnect(Socket socket) {
        active.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { listener.close(); } catch (IOException ignored) { }
        active.forEach(this::disconnect);
        workers.shutdownNow();
        try { if (!workers.awaitTermination(25, TimeUnit.SECONDS)) System.err.println("Some database workers are still finishing."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static final class LineTooLongException extends IOException { }
}
