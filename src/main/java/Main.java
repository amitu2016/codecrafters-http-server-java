import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight concurrent HTTP server extended to advertise gzip support.
 *
 * - If client sends "Accept-Encoding: gzip" for the /echo/{msg} route,
 *   server will include "Content-Encoding: gzip" in the response headers.
 * - The body is NOT compressed in this stage (per task instructions).
 *
 * All other behavior retained from previous version.
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("Server starting...");

        // ---- Parse command-line args ----
        String filesDirectory = null;
        for (int i = 0; i < args.length; i++) {
            if ("--directory".equals(args[i]) && i + 1 < args.length) {
                filesDirectory = args[i + 1];
                break;
            }
        }
        if (filesDirectory == null) {
            filesDirectory = new File(".").getAbsolutePath();
        }

        final String baseDir = filesDirectory;

        // ---- Thread pool ----
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server is listening on port 4221...");
            System.out.println("Files directory: " + baseDir);

            // Accept loop
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted connection from: " + clientSocket.getInetAddress());
                executor.submit(new ClientHandler(clientSocket, baseDir));
            }
        } catch (IOException e) {
            System.err.println("Server exception: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }
}

/**
 * Handles one client request end-to-end.
 */
class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final String filesDirectory;

    public ClientHandler(Socket clientSocket, String filesDirectory) {
        this.clientSocket = clientSocket;
        this.filesDirectory = filesDirectory;
    }

    @Override
    public void run() {
        try (
                InputStream in = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream()
        ) {
            // Read headers safely (stop at \r\n\r\n)
            byte[] headerBytes = readUntilDoubleCRLF(in);
            if (headerBytes == null || headerBytes.length == 0) {
                out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                out.flush();
                return;
            }

            String headerBlock = new String(headerBytes, "ISO-8859-1");
            String[] lines = headerBlock.split("\r\n");
            String requestLine = lines[0];
            System.out.println("Request: " + requestLine);

            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                writeTextResponse(out, "400 Bad Request", "Bad Request");
                return;
            }

            String method = requestParts[0];
            String path = requestParts[1];

            // Parse headers into a map (lowercased keys)
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String value = line.substring(colon + 1).trim();
                    headers.put(name, value);
                }
            }

            // Routing
            if ("/".equals(path)) {
                writeTextResponse(out, "200 OK", "OK");
                return;
            }

            if (path.startsWith("/echo/")) {
                // --- NEW: check Accept-Encoding and include Content-Encoding if gzip supported ---
                // Per task: only advertise gzip; do not compress body yet.
                String msg = path.substring("/echo/".length());
                boolean clientAcceptsGzip = clientAcceptsGzip(headers.get("accept-encoding"));

                if (clientAcceptsGzip) {
                    // use the variant that includes Content-Encoding: gzip header
                    writeTextResponseWithEncoding(out, "200 OK", msg, "gzip");
                } else {
                    // normal response without Content-Encoding header
                    writeTextResponse(out, "200 OK", msg);
                }
                return;
            }

            if ("/user-agent".equals(path)) {
                String ua = headers.getOrDefault("user-agent", "");
                writeTextResponse(out, "200 OK", ua);
                return;
            }

            if (path.startsWith("/files/")) {
                String filename = path.substring("/files/".length());

                if ("GET".equalsIgnoreCase(method)) {
                    handleFileGet(out, filename);
                    return;
                }

                if ("POST".equalsIgnoreCase(method)) {
                    handleFilePost(out, in, filename, headers);
                    return;
                }
            }

            writeTextResponse(out, "404 Not Found", "Not Found");

        } catch (IOException e) {
            System.err.println("Client handling error: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Simple check whether the Accept-Encoding header advertises gzip support.
     *
     * Accept-Encoding can contain multiple tokens, e.g.:
     *   Accept-Encoding: gzip, deflate
     *   Accept-Encoding: gzip;q=1.0, identity; q=0.5
     *
     * We consider the client accepting gzip if any comma-separated token starts with "gzip"
     * (case-insensitive) — this covers q-values and whitespace.
     */
    private boolean clientAcceptsGzip(String acceptEncodingHeader) {
        if (acceptEncodingHeader == null) return false;
        String[] parts = acceptEncodingHeader.split(",");
        for (String p : parts) {
            if (p.trim().toLowerCase().startsWith("gzip")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes a text response WITHOUT Content-Encoding header.
     * Used by most routes.
     */
    private void writeTextResponse(OutputStream out, String status, String body) throws IOException {
        byte[] bodyBytes = body.getBytes();
        String headers = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
        out.write(headers.getBytes());
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * Writes a text response and includes a Content-Encoding header if encoding != null.
     *
     * Note: per this stage we DO NOT compress the body; we only advertise the encoding header.
     */
    private void writeTextResponseWithEncoding(OutputStream out, String status, String body, String encoding) throws IOException {
        byte[] bodyBytes = body.getBytes();
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        sb.append("Content-Type: text/plain\r\n");
        // Add Content-Encoding header if requested (e.g., "gzip")
        if (encoding != null && !encoding.isEmpty()) {
            sb.append("Content-Encoding: ").append(encoding).append("\r\n");
        }
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");

        out.write(sb.toString().getBytes());
        out.write(bodyBytes); // body is not compressed in this stage
        out.flush();
    }

    /**
     * Reads bytes from InputStream until "\r\n\r\n" is found.
     */
    private byte[] readUntilDoubleCRLF(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0;
        while (true) {
            int b = in.read();
            if (b == -1) break;
            buffer.write(b);

            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') {
                byte[] all = buffer.toByteArray();
                byte[] result = new byte[all.length - 4];
                System.arraycopy(all, 0, result, 0, all.length - 4);
                return result;
            } else {
                state = (b == '\r') ? 1 : 0;
            }
        }
        return buffer.toByteArray();
    }

    /**
     * GET /files/{filename}
     */
    private void handleFileGet(OutputStream out, String filename) throws IOException {
        File base = new File(filesDirectory);
        File requested = new File(base, filename);

        String baseCanonical = base.getCanonicalPath();
        String reqCanonical = requested.getCanonicalPath();
        if (!reqCanonical.startsWith(baseCanonical)) {
            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            out.flush();
            return;
        }

        if (!requested.exists() || !requested.isFile()) {
            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            out.flush();
            return;
        }

        byte[] content = Files.readAllBytes(requested.toPath());
        String headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "\r\n";
        out.write(headers.getBytes());
        out.write(content);
        out.flush();
    }

    /**
     * POST /files/{filename}
     */
    private void handleFilePost(OutputStream out, InputStream in, String filename, Map<String, String> headers) throws IOException {
        File base = new File(filesDirectory);
        File target = new File(base, filename);

        String baseCanonical = base.getCanonicalPath();
        String targetCanonical = target.getCanonicalPath();
        if (!targetCanonical.startsWith(baseCanonical)) {
            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            out.flush();
            return;
        }

        int contentLength = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try { contentLength = Integer.parseInt(cl.trim()); } catch (NumberFormatException ignored) {}
        }

        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int r = in.read(body, read, contentLength - read);
            if (r == -1) break;
            read += r;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.write(target.toPath(), body);

        out.write("HTTP/1.1 201 Created\r\n\r\n".getBytes());
        out.flush();
    }
}
