package app.fileflash.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Small HTTP/1.1 transport. Each connection carries exactly one bounded request. */
final class HttpServer {
    interface Handler { void handle(Request request, Response response) throws Exception; }
    static final int MAX_HEADER_BYTES = 16 * 1024;
    private final Handler handler;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private ServerSocket listener;
    private Thread acceptor;
    private ThreadPoolExecutor workers;
    volatile boolean running;

    HttpServer(Handler handler) { this.handler = handler; }

    synchronized void start(int port) throws IOException {
        if (running) return;
        listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(port), 32);
        workers = new ThreadPoolExecutor(6, 6, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(18), r -> { Thread t = new Thread(r, "fileflash-http"); t.setDaemon(true); return t; });
        running = true;
        acceptor = new Thread(this::accept, "fileflash-listen");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() { return listener == null ? 0 : listener.getLocalPort(); }

    private void accept() {
        while (running) {
            try {
                Socket socket = listener.accept();
                socket.setSoTimeout(30_000);
                socket.setTcpNoDelay(true);
                sockets.add(socket);
                try { workers.execute(() -> serve(socket)); }
                catch (RejectedExecutionException e) {
                    sockets.remove(socket);
                    // Do not block the acceptor on an overloaded peer.
                    socket.close();
                }
            } catch (IOException ignored) { if (!running) break; }
        }
    }

    private void serve(Socket socket) {
        Response response = null;
        try {
            response = new Response(new BufferedOutputStream(socket.getOutputStream(), 32 * 1024));
            Request request = parse(new BufferedInputStream(socket.getInputStream(), 32 * 1024), socket);
            handler.handle(request, response);
        } catch (HttpError e) {
            if (response != null && !response.started) try { response.error(e.status, e.code, e.getMessage()); } catch (IOException ignored) { }
        } catch (SocketTimeoutException e) {
            if (response != null && !response.started) try { response.error(408, "timeout", "连接超时，请重试"); } catch (IOException ignored) { }
        } catch (Exception e) {
            if (response != null && !response.started) try { response.error(500, "internal_error", "操作失败，请重试或检查剩余存储空间"); } catch (IOException ignored) { }
        } finally {
            if (response != null) try { response.flush(); } catch (IOException ignored) { }
            try { socket.close(); } catch (IOException ignored) { }
            sockets.remove(socket);
        }
    }

    synchronized void stop() {
        running = false;
        try { if (listener != null) listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        sockets.clear();
        if (workers != null) workers.shutdownNow();
        // Completed requests can hold metadata locks. Wait before a new instance opens storage.
        if (workers != null) try { workers.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static Request parse(InputStream in, Socket socket) throws IOException {
        int[] budget = {MAX_HEADER_BYTES};
        String line = readLine(in, budget);
        String[] start = line.split(" ", -1);
        if (start.length != 3 || !start[0].matches("[A-Z]{3,8}") || !start[2].equals("HTTP/1.1"))
            throw new HttpError(400, "bad_request", "无效的 HTTP 请求");
        if (!start[1].startsWith("/") || start[1].startsWith("//") || start[1].indexOf('#') >= 0)
            throw new HttpError(400, "bad_target", "无效的请求地址");
        Map<String, String> headers = new LinkedHashMap<>();
        while (!(line = readLine(in, budget)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9a-zA-Z-]+"))
                throw new HttpError(400, "bad_header", "无效的请求头");
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            for (int i = 0; i < value.length(); i++) if (value.charAt(i) < 32 && value.charAt(i) != '\t')
                throw new HttpError(400, "bad_header", "无效的请求头");
            if (headers.put(key, value) != null)
                throw new HttpError(400, "duplicate_header", "不允许重复请求头");
        }
        if (!headers.containsKey("host") || headers.get("host").isEmpty())
            throw new HttpError(400, "missing_host", "缺少 Host");
        if (headers.containsKey("transfer-encoding"))
            throw new HttpError(400, "unsupported_encoding", "请使用明确的 Content-Length");
        if (headers.containsKey("expect")) throw new HttpError(417, "expectation_failed", "不支持 Expect 请求头");
        String length = headers.getOrDefault("content-length", "0");
        if (!length.matches("[0-9]{1,19}")) throw new HttpError(400, "bad_length", "无效的请求长度");
        long size;
        try { size = Long.parseLong(length); } catch (NumberFormatException e) { throw new HttpError(400, "bad_length", "请求长度超出范围"); }
        int query = start[1].indexOf('?');
        String path = query < 0 ? start[1] : start[1].substring(0, query);
        // Routing identifiers contain no escapes. Never decode a path into a filesystem path.
        if (path.contains("%") || path.contains("\\") || path.contains("..") || path.contains("\0"))
            throw new HttpError(400, "bad_path", "无效的请求路径");
        return new Request(start[0], path, headers, size, in, socket);
    }

    private static String readLine(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int next = in.read();
            if (next < 0) throw new HttpError(400, "unexpected_eof", "请求不完整");
            if (--budget[0] < 0) throw new HttpError(431, "headers_too_large", "请求头过长");
            if (next == '\n') {
                if (previous != '\r') throw new HttpError(400, "bad_line_ending", "请求头须使用 CRLF");
                byte[] result = bytes.toByteArray();
                return new String(result, 0, result.length - 1, StandardCharsets.ISO_8859_1);
            }
            if (previous == '\r' || next == 0) throw new HttpError(400, "bad_line_ending", "无效的请求头");
            bytes.write(next);
            previous = next;
        }
    }

    static final class Request {
        final String method, path;
        final Map<String, String> headers;
        final long length;
        final InputStream body;
        final Socket socket;
        Request(String method, String path, Map<String,String> headers, long length, InputStream body, Socket socket) {
            this.method=method; this.path=path; this.headers=headers; this.length=length; this.body=body; this.socket=socket;
        }
        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
        byte[] readBody(int maximum) throws IOException {
            if (length > maximum) throw new HttpError(413, "body_too_large", "请求内容过大");
            byte[] data = new byte[(int) length];
            int offset=0;
            while (offset < data.length) {
                int count = body.read(data, offset, data.length-offset);
                if (count < 0) throw new HttpError(400, "incomplete_body", "传输中断，请重试");
                offset += count;
            }
            return data;
        }
    }

    static final class Response {
        private final OutputStream out;
        boolean started;
        Response(OutputStream out) { this.out=out; }
        void begin(int status, String mime, long length, Map<String,String> extra) throws IOException {
            if (started) throw new IOException("Response already started");
            started=true;
            StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            h.append("Content-Length: ").append(length).append("\r\nContent-Type: ").append(mime)
             .append("\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n")
             .append("Cross-Origin-Resource-Policy: same-origin\r\n");
            if (extra != null) for (Map.Entry<String,String> e : extra.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        void bytes(int status, String mime, byte[] body, Map<String,String> headers) throws IOException {
            begin(status,mime,body.length,headers); out.write(body); out.flush();
        }
        void json(int status, String json) throws IOException { bytes(status,"application/json; charset=utf-8",json.getBytes(StandardCharsets.UTF_8),null); }
        void error(int status,String code,String message) throws IOException {
            com.google.gson.JsonObject error = new com.google.gson.JsonObject();
            error.addProperty("error",message); error.addProperty("code",code); json(status,error.toString());
        }
        void write(byte[] bytes,int off,int count) throws IOException { out.write(bytes,off,count); }
        void flush() throws IOException { out.flush(); }
        private static String reason(int status) {
            switch(status) {
                case 200:return "OK";case 201:return "Created";case 204:return "No Content";case 206:return "Partial Content";
                case 400:return "Bad Request";case 401:return "Unauthorized";case 403:return "Forbidden";case 404:return "Not Found";
                case 405:return "Method Not Allowed";case 408:return "Request Timeout";case 409:return "Conflict";case 410:return "Gone";
                case 413:return "Payload Too Large";case 416:return "Range Not Satisfiable";case 417:return "Expectation Failed";
                case 422:return "Unprocessable Content";case 429:return "Too Many Requests";case 431:return "Request Header Fields Too Large";
                case 503:return "Service Unavailable";case 507:return "Insufficient Storage";default:return "Internal Server Error";
            }
        }
    }

    static final class HttpError extends IOException {
        final int status; final String code;
        HttpError(int status,String code,String message) { super(message);this.status=status;this.code=code; }
    }
}
