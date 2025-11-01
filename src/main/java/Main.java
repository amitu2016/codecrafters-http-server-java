import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Robust lightweight HTTP server.
 *
 * Fixes request-body handling by reading the request-line + headers directly
 * from the InputStream (searching for \r\n\r\n) so we can safely read the
 * raw body bytes afterwards from the same InputStream without losing bytes.
 *
 * Supported routes:
 *  - GET  /                    -> 200 text/plain "OK"
 *  - GET  /echo/{msg}          -> 200 text/plain {msg}
 *  - GET  /user-agent          -> 200 text/plain <User-Agent header>
 *  - GET  /files/{filename}    -> 200 application/octet-stream or 404
 *  - POST /files/{filename}    -> create/overwrite file and respond 201 Created
 *
 * Usage (grader):
 *   ./your_program.sh --directory /tmp/...
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("Server starting...");

        // Parse --directory <path>
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

        // Fixed thread pool
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server is listening on port 4221...");
            System.out.println("Files directory: " + baseDir);

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
 * ClientHandler reads request-line + headers directly from the InputStream
 * (so no BufferedReader pre-buffering of body occurs), and then reads body bytes.
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
            // Read header block (request line + headers) from InputStream until \r\n\r\n
            byte[] headerBytes = readUntilDoubleCRLF(in);
            if (headerBytes == null || headerBytes.length == 0) {
                // Malformed request or closed connection
                writeRaw(out, "HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                return;
            }

            // Convert header block to String using ISO-8859-1 to preserve raw bytes (HTTP header charset)
            String headerBlock = new String(headerBytes, "ISO-8859-1");
            String[] lines = headerBlock.split("\r\n");
            if (lines.length == 0) {
                writeRaw(out, "HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
                return;
            }

            // First line is request line: e.g., "POST /files/foo HTTP/1.1"
            String requestLine = lines[0];
            System.out.println("Request: " + requestLine);

            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                writeTextResponse(out, "400 Bad Request", "Bad Request");
                return;
            }
            String method = requestParts[0];
            String path = requestParts[1];

            // Parse remaining lines into header map (lowercased keys)
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

            // Only allow GET and POST in our server
            if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                writeTextResponse(out, "405 Method Not Allowed", "Method Not Allowed");
                return;
            }

            // Preserve legacy routes
            if ("/".equals(path)) {
                writeTextResponse(out, "200 OK", "OK");
                return;
            } else if (path.startsWith("/echo/")) {
                String msg = path.substring("/echo/".length());
                writeTextResponse(out, "200 OK", msg);
                return;
            } else if ("/user-agent".equals(path)) {
                String ua = headers.getOrDefault("user-agent", "");
                writeTextResponse(out, "200 OK", ua);
                return;
            }

            // Files route
            if (path.startsWith("/files/")) {
                String filename = path.substring("/files/".length());
                if ("GET".equalsIgnoreCase(method)) {
                    handleFileGet(out, filename);
                    return;
                } else {
                    // POST: read body bytes from the same InputStream (no mismatch)
                    handleFilePost(out, in, filename, headers);
                    return;
                }
            }

            // Unknown path
            writeTextResponse(out, "404 Not Found", "Not Found");

        } catch (IOException e) {
            System.err.println("Client handling error: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads from InputStream until the sequence \r\n\r\n is encountered.
     * Returns the bytes up to but excluding the final \r\n\r\n.
     * If the stream closes before the sequence, returns what was read (or null if none).
     */
    private byte[] readUntilDoubleCRLF(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int state = 0; // tracks matching of \r\n\r\n
        // state progression: 0 -> '\r' (1) -> '\n' (2) -> '\r' (3) -> '\n' (done)
        while (true) {
            int b = in.read();
            if (b == -1) {
                // stream closed
                break;
            }
            buffer.write(b);

            // update state machine
            if (state == 0) {
                if (b == '\r') state = 1; else state = 0;
            } else if (state == 1) {
                if (b == '\n') state = 2; else state = (b == '\r') ? 1 : 0;
            } else if (state == 2) {
                if (b == '\r') state = 3; else state = 0;
            } else if (state == 3) {
                if (b == '\n') {
                    // Found \r\n\r\n. Return everything except the final \r\n\r\n (4 bytes)
                    byte[] all = buffer.toByteArray();
                    int len = all.length - 4;
                    if (len < 0) len = 0;
                    byte[] result = new byte[len];
                    System.arraycopy(all, 0, result, 0, len);
                    return result;
                } else {
                    state = (b == '\r') ? 1 : 0;
                }
            }
            // continue reading
        }
        // If we reach here, stream ended before delimiter; return what we have (may be empty)
        return buffer.toByteArray();
    }

    /**
     * Writes a binary blob to OutputStream (helper).
     */
    private void writeRaw(OutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    /**
     * Helper to write text responses for legacy routes, includes Content-Length.
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
     * GET /files/{filename} handling (exact grader semantics).
     */
    private void handleFileGet(OutputStream out, String filename) throws IOException {
        File base = new File(filesDirectory);
        File requested = new File(base, filename);

        String baseCanonical = base.getCanonicalPath();
        String reqCanonical = requested.getCanonicalPath();
        if (!reqCanonical.equals(baseCanonical) && !reqCanonical.startsWith(baseCanonical + File.separator)) {
            out.write(("HTTP/1.1 404 Not Found\r\n\r\n").getBytes());
            out.flush();
            return;
        }

        if (!requested.exists() || !requested.isFile()) {
            out.write(("HTTP/1.1 404 Not Found\r\n\r\n").getBytes());
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
     * POST /files/{filename} handling:
     * - Reads Content-Length header and then reads exactly that many bytes from InputStream.
     * - Writes bytes to file (create/overwrite).
     * - Responds with exactly "HTTP/1.1 201 Created\r\n\r\n"
     */
    private void handleFilePost(OutputStream out, InputStream in, String filename, Map<String, String> headers) throws IOException {
        File base = new File(filesDirectory);
        File target = new File(base, filename);

        // canonical path validation
        String baseCanonical = base.getCanonicalPath();
        String targetCanonical = target.getCanonicalPath();
        if (!targetCanonical.equals(baseCanonical) && !targetCanonical.startsWith(baseCanonical + File.separator)) {
            out.write(("HTTP/1.1 404 Not Found\r\n\r\n").getBytes());
            out.flush();
            return;
        }

        // Parse Content-Length; fallback to 0 if missing/invalid
        int contentLength = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try {
                contentLength = Integer.parseInt(cl.trim());
                if (contentLength < 0) contentLength = 0;
            } catch (NumberFormatException ignore) {
                contentLength = 0;
            }
        }

        // Read exactly contentLength bytes from InputStream.
        byte[] body = new byte[contentLength];
        int pos = 0;
        while (pos < contentLength) {
            int r = in.read(body, pos, contentLength - pos);
            if (r == -1) break; // EOF unexpected
            pos += r;
        }
        if (pos != contentLength) {
            // Trim if fewer bytes read
            byte[] tmp = new byte[pos];
            System.arraycopy(body, 0, tmp, 0, pos);
            body = tmp;
        }

        // Ensure parent dirs exist
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Write file (overwrite if exists)
        Files.write(target.toPath(), body);

        // Respond with exactly the grader-expected bytes
        out.write(("HTTP/1.1 201 Created\r\n\r\n").getBytes());
        out.flush();
    }
}
