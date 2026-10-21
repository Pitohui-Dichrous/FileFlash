package app.fileflash.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.IllegalBlockingModeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HttpServerCleanupTest {
    @Test void uncheckedFinalFlushFailureClosesTheConnectionAndKeepsServing() throws Exception {
        ConcurrentLinkedQueue<Throwable> uncaught = new ConcurrentLinkedQueue<>();
        HttpServer server = server(uncaught, (request, response) -> {
            if (request.path.equals("/fault")) {
                // The production handoff is blocking. Deliberately change this test connection
                // after parsing to reproduce the Android channel adapter's unchecked failure.
                request.socket.getChannel().configureBlocking(false);
                response.begin(200, "text/plain", 0, null);
                // The small response remains buffered until serve's final flush.
            } else response.json(200, "{\"ok\":true}");
        });
        try {
            server.start(0);
            String id = UUID.randomUUID().toString();
            try (Socket socket = request(server, "/fault", id)) {
                JsonObject trace = awaitClosed(server, id, uncaught);
                assertEquals(200, trace.get("status").getAsInt());
                assertFalse(trace.get("flushed").getAsBoolean());
                assertEquals("IllegalBlockingModeException", trace.get("errorClass").getAsString());
                assertEquals(-1, socket.getInputStream().read(), "Failed connection must reach EOF");
            }
            assertHealthy(server, uncaught);
        } finally { server.stop(); }
    }

    @Test void uncheckedErrorResponseFailuresAreContainedForEveryHandlerCatch() throws Exception {
        ConcurrentLinkedQueue<Throwable> uncaught = new ConcurrentLinkedQueue<>();
        HttpServer server = server(uncaught, (request, response) -> {
            if (request.path.equals("/healthy")) {
                response.json(200, "{\"ok\":true}");
                return;
            }
            request.socket.getChannel().configureBlocking(false);
            switch (request.path) {
                case "/http-error": throw new HttpServer.HttpError(400, "test_rejected", "rejected");
                case "/timeout": throw new SocketTimeoutException("test timeout");
                default: throw new IllegalStateException("test handler failure");
            }
        });
        try {
            server.start(0);
            List<String> paths = List.of("/http-error", "/timeout", "/exception");
            int[] statuses = {400, 408, 500};
            String[] codes = {"test_rejected", "timeout", "internal_error"};
            for (int i = 0; i < paths.size(); i++) {
                String id = UUID.randomUUID().toString();
                try (Socket socket = request(server, paths.get(i), id)) {
                    JsonObject trace = awaitClosed(server, id, uncaught);
                    assertEquals(statuses[i], trace.get("status").getAsInt(), paths.get(i));
                    assertEquals(codes[i], trace.get("errorCode").getAsString(), paths.get(i));
                    assertEquals("IllegalBlockingModeException", trace.get("errorClass").getAsString());
                    assertFalse(trace.get("flushed").getAsBoolean());
                    assertEquals(-1, socket.getInputStream().read(), "Error response failure must still close");
                }
            }
            assertHealthy(server, uncaught);
        } finally { server.stop(); }
    }

    @Test void uncheckedShutdownDrainAndCloseFailuresCannotSkipBookkeeping() throws Exception {
        FaultySocket socket = new FaultySocket();
        HttpServer server = new HttpServer((request, response) -> response.error(400, "test_rejected", "rejected"));
        Set<Socket> tracked = trackedSockets(server);
        tracked.add(socket);
        TransportDiagnostics.Trace trace = new TransportDiagnostics().accepted();
        Method serve = HttpServer.class.getDeclaredMethod("serve", Socket.class, byte[].class,
                HttpServer.HttpError.class, TransportDiagnostics.Trace.class);
        serve.setAccessible(true);
        byte[] headers = ("POST /reject HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        assertDoesNotThrow(() -> serve.invoke(server, socket, headers, null, trace),
                "Transport RuntimeException must not leave the connection boundary");

        assertTrue(socket.shutdownAttempted, "Shutdown must be attempted after the response");
        assertTrue(socket.readAttempts > 0, "A failed shutdown must not skip bounded rejected-body draining");
        assertTrue(socket.closeAttempted, "A failed drain must not skip closing the socket");
        assertFalse(tracked.contains(socket), "A failed close must not retain the connection");
        assertEquals(0, server.diagnostics().get("openConnections").getAsInt());
        JsonObject finished = trace.snapshot();
        assertTrue(finished.get("closed").getAsBoolean());
        assertEquals("closed", finished.get("stage").getAsString());
        assertEquals("IllegalBlockingModeException", finished.get("errorClass").getAsString());
    }

    private static HttpServer server(ConcurrentLinkedQueue<Throwable> uncaught, HttpServer.Handler handler) {
        return new HttpServer((request, response) -> {
            // Avoid changing the JVM-wide exception handler or hiding an asynchronous worker crash.
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
            handler.handle(request, response);
        });
    }

    private static Socket request(HttpServer server, String path, String id) throws Exception {
        Socket socket = new Socket("127.0.0.1", server.port());
        try {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nX-Request-ID: "
                    + id + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return socket;
        } catch (Exception error) { socket.close(); throw error; }
    }

    private static JsonObject awaitClosed(HttpServer server, String id,
                                           ConcurrentLinkedQueue<Throwable> uncaught) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        do {
            assertTrue(uncaught.isEmpty(), () -> "Worker leaked an exception: " + uncaught);
            JsonObject snapshot = server.diagnostics();
            for (JsonElement item : snapshot.getAsJsonArray("requests")) {
                JsonObject trace = item.getAsJsonObject();
                if (trace.has("clientRequestId") && id.equals(trace.get("clientRequestId").getAsString())
                        && trace.get("closed").getAsBoolean()) {
                    assertEquals(0, snapshot.get("openConnections").getAsInt());
                    return trace;
                }
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("Connection did not complete cleanup: " + server.diagnostics());
        return null;
    }

    private static void assertHealthy(HttpServer server, ConcurrentLinkedQueue<Throwable> uncaught) throws Exception {
        String id = UUID.randomUUID().toString();
        try (Socket socket = request(server, "/healthy", id)) {
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"), response);
            assertTrue(response.endsWith("{\"ok\":true}"), response);
            JsonObject trace = awaitClosed(server, id, uncaught);
            assertTrue(trace.get("flushed").getAsBoolean());
        }
        assertTrue(uncaught.isEmpty(), () -> "Worker leaked an exception: " + uncaught);
    }

    @SuppressWarnings("unchecked")
    private static Set<Socket> trackedSockets(HttpServer server) throws Exception {
        Field sockets = HttpServer.class.getDeclaredField("sockets");
        sockets.setAccessible(true);
        return (Set<Socket>) sockets.get(server);
    }

    private static final class FaultySocket extends Socket {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        boolean shutdownAttempted, closeAttempted;
        int readAttempts;
        @Override public OutputStream getOutputStream() { return output; }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() { readAttempts++; throw new IllegalBlockingModeException(); }
            };
        }
        @Override public synchronized void setSoTimeout(int timeout) { }
        @Override public void shutdownOutput() { shutdownAttempted = true; throw new IllegalBlockingModeException(); }
        @Override public synchronized void close() { closeAttempted = true; throw new IllegalBlockingModeException(); }
    }
}
