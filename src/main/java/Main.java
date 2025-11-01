import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight multi-threaded HTTP server implementation.
 *
 * <p>Supported routes:</p>
 * <ul>
 *   <li><b>GET /</b> → Returns "OK"</li>
 *   <li><b>GET /echo/{message}</b> → Echoes back {message}</li>
 *   <li><b>GET /user-agent</b> → Returns the client's User-Agent header</li>
 *   <li><b>GET /files/{filename}</b> → Returns file content from directory passed via --directory flag</li>
 * </ul>
 *
 * <p>Usage (as per Codecrafters test harness):</p>
 * <pre>
 *   ./your_program.sh --directory /tmp/
 * </pre>
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("Server starting...");

        // Parse command-line arguments for the --directory flag
        // Example: --directory /tmp/
        String filesDirectory = null;
        for (int i = 0; i < args.length; i++) {
            if ("--directory".equals(args[i]) && i + 1 < args.length) {
                filesDirectory = args[i + 1];
                break;
            }
        }

        // If no directory provided, fallback to current working directory
        if (filesDirectory == null) {
            filesDirectory = new File(".").getAbsolutePath();
        }

        final String baseDir = filesDirectory;

        // Use a fixed-size thread pool to handle multiple concurrent connections efficiently
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server is listening on port 4221...");
            System.out.println("Files directory: " + baseDir);

            // Continuous loop to accept and handle new client connections
            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted connection from: " + clientSocket.getInetAddress());

                // Delegate request handling to a worker thread
                executor.submit(new ClientHandler(clientSocket, baseDir));
            }
        } catch (IOException e) {
            System.err.println("Server exception: " + e.getMessage());
        } finally {
            // Gracefully shutdown the thread pool on termination
            executor.shutdown();
        }
    }
}

/**
 * Handles a single client connection and processes HTTP requests.
 *
 * <p>Implements logic for the supported endpoints, constructs appropriate HTTP
 * responses, and writes them to the output stream.</p>
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
                InputStream inputStream = clientSocket.getInputStream();
                OutputStream outputStream = clientSocket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))
        ) {
            // Read the HTTP request line, e.g. "GET /echo/hello HTTP/1.1"
            String requestLine = reader.readLine();
            System.out.println("Request: " + requestLine);

            // If the request is malformed or empty, return 400
            if (requestLine == null || requestLine.isEmpty()) {
                writeTextResponse(outputStream, "400 Bad Request", "Bad Request");
                return;
            }

            // Split request into parts: [method, path, version]
            String[] httpRequest = requestLine.split(" ");
            if (httpRequest.length < 2) {
                writeTextResponse(outputStream, "400 Bad Request", "Bad Request");
                return;
            }

            String method = httpRequest[0];
            String path = httpRequest[1];

            // Read all headers into a map for easy access
            Map<String, String> headers = readHeaders(reader);

            // Currently, only GET is supported
            if (!"GET".equalsIgnoreCase(method)) {
                writeTextResponse(outputStream, "405 Method Not Allowed", "Method Not Allowed");
                return;
            }

            // Handle different routes
            if ("/".equals(path)) {
                // Root route: respond with OK
                writeTextResponse(outputStream, "200 OK", "OK");

            } else if (path.startsWith("/echo/")) {
                // Echo route: return the substring after /echo/
                String message = path.substring("/echo/".length());
                writeTextResponse(outputStream, "200 OK", message);

            } else if ("/user-agent".equals(path)) {
                // Return the User-Agent header from the request
                String ua = headers.getOrDefault("user-agent", "");
                writeTextResponse(outputStream, "200 OK", ua);

            } else if (path.startsWith("/files/")) {
                // Handle file download endpoint
                String fileName = path.substring("/files/".length());
                handleFileRequest(outputStream, fileName);

            } else {
                // Route not found → 404
                writeTextResponse(outputStream, "404 Not Found", "Not Found");
            }

        } catch (IOException e) {
            System.err.println("Client handling error: " + e.getMessage());
        } finally {
            // Always close the socket after handling the request
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Reads all HTTP headers from the BufferedReader until a blank line is encountered.
     *
     * @return a Map containing header names (lowercased) and their values
     */
    private Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) break; // blank line indicates end of headers
            int colon = line.indexOf(':');
            if (colon > 0) {
                String name = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                headers.put(name, value);
            }
        }
        return headers;
    }

    /**
     * Sends a plain-text HTTP response with the provided status and body.
     *
     * @param out    Output stream to write to
     * @param status HTTP status (e.g., "200 OK", "404 Not Found")
     * @param body   Response body
     */
    private void writeTextResponse(OutputStream out, String status, String body) throws IOException {
        byte[] bodyBytes = body.getBytes();
        String headers = "HTTP/1.1 " + status + "\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "\r\n";
        out.write(headers.getBytes());
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * Handles GET /files/{filename} requests.
     *
     * <p>Logic:</p>
     * <ul>
     *   <li>If file exists → return 200 with Content-Length and binary data.</li>
     *   <li>If file not found → return 404.</li>
     *   <li>Performs canonical path validation to prevent directory traversal attacks.</li>
     * </ul>
     *
     * @param out      output stream to send the HTTP response
     * @param fileName the requested file name
     */
    private void handleFileRequest(OutputStream out, String fileName) throws IOException {
        File base = new File(filesDirectory);
        File requested = new File(base, fileName);

        // Canonical path validation to prevent "../" traversal
        String baseCanonical = base.getCanonicalPath();
        String requestedCanonical = requested.getCanonicalPath();

        // Ensure file is inside base directory
        if (!requestedCanonical.equals(baseCanonical) &&
                !requestedCanonical.startsWith(baseCanonical + File.separator)) {
            out.write(("HTTP/1.1 404 Not Found\r\n\r\n").getBytes());
            out.flush();
            return;
        }

        // Return 404 if file does not exist
        if (!requested.exists() || !requested.isFile()) {
            out.write(("HTTP/1.1 404 Not Found\r\n\r\n").getBytes());
            out.flush();
            return;
        }

        // Read file bytes and construct a binary HTTP response
        byte[] content = Files.readAllBytes(requested.toPath());
        String headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "\r\n";

        out.write(headers.getBytes());
        out.write(content); // Write file content as raw bytes
        out.flush();
    }
}
