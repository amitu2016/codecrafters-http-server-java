import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point for the lightweight HTTP server.
 *
 * Server listens on port 4221 and handles connections via a fixed thread pool.
 * The optional second command-line argument is the base directory for /files.
 *
 * Usage:
 *   java Main                -> server starts, uses current working dir for files
 *   java Main /path/to/dir   -> server starts, uses provided dir for /files
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("Server starting...");

        // Accept optional directory argument. If absent, use current working directory.
        String filesDirectory = null;
        if (args.length >= 1) {
            // If user passed a single arg, consider it the directory. This keeps it flexible.
            filesDirectory = args[0];
        }

        // Thread pool
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server is listening on port 4221...");
            System.out.println("Files directory: " + (filesDirectory != null ? filesDirectory : new File(".").getAbsolutePath()));

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted connection from: " + clientSocket.getInetAddress());
                executor.submit(new ClientHandler(clientSocket, filesDirectory));
            }

        } catch (IOException e) {
            System.err.println("Server exception: " + e.getMessage());
        } finally {
            executor.shutdown();
        }
    }
}

/**
 * Handles a single client connection.
 */
class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final String filesDirectory; // may be null -> use current dir

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
            // Read request line
            String requestLine = reader.readLine();
            System.out.println("Request: " + requestLine);

            if (requestLine == null || requestLine.isEmpty()) {
                writeResponse(outputStream, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }

            String[] httpRequest = requestLine.split(" ");
            if (httpRequest.length < 2) {
                writeResponse(outputStream, "400 Bad Request", "text/plain", "Bad Request");
                return;
            }

            String method = httpRequest[0];
            String path = httpRequest[1];

            // Read headers into a map so other handlers can use them (e.g., /user-agent)
            Map<String, String> headers = readHeaders(reader);

            // Only GET for now
            if (!"GET".equalsIgnoreCase(method)) {
                writeResponse(outputStream, "405 Method Not Allowed", "text/plain", "Method Not Allowed");
                return;
            }

            // Routes
            if ("/".equals(path)) {
                writeResponse(outputStream, "200 OK", "text/plain", "OK");
            } else if (path.startsWith("/echo/")) {
                String message = path.substring("/echo/".length());
                writeResponse(outputStream, "200 OK", "text/plain", message);
            } else if ("/user-agent".equals(path)) {
                String userAgent = headers.getOrDefault("user-agent", "");
                writeResponse(outputStream, "200 OK", "text/plain", userAgent);
            } else if (path.startsWith("/files/")) {
                String fileName = path.substring("/files/".length());
                handleFileRequest(outputStream, fileName);
            } else {
                writeResponse(outputStream, "404 Not Found", "text/plain", "Not Found");
            }

        } catch (IOException e) {
            System.err.println("Client handling error: " + e.getMessage());
        } finally {
            // close socket
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }

    /**
     * Read headers until blank line and return a map of header-name -> value (lowercased keys).
     */
    private Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) break; // end of headers
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
     * Handles /files/{fileName}. Server will not fail at startup if no directory provided.
     * If file not found -> 404. If path traversal detected -> 403.
     */
    private void handleFileRequest(OutputStream outputStream, String fileName) throws IOException {
        File baseDir = (filesDirectory != null) ? new File(filesDirectory) : new File(".");
        File requested = new File(baseDir, fileName);

        // Prevent simple directory traversal attacks by comparing canonical paths
        String baseCanonical = baseDir.getCanonicalPath();
        String requestedCanonical = requested.getCanonicalPath();
        if (!requestedCanonical.startsWith(baseCanonical)) {
            writeResponse(outputStream, "403 Forbidden", "text/plain", "Forbidden");
            return;
        }

        if (!requested.exists() || !requested.isFile()) {
            writeResponse(outputStream, "404 Not Found", "text/plain", "File Not Found");
            return;
        }

        byte[] content = Files.readAllBytes(requested.toPath());
        String contentType = "application/octet-stream"; // keep simple; could probe by extension
        String headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + content.length + "\r\n" +
                "\r\n";

        outputStream.write(headers.getBytes());
        outputStream.write(content); // write binary body
        outputStream.flush();
    }

    /**
     * Writes a simple text response (headers + body).
     */
    private void writeResponse(OutputStream outputStream, String status, String contentType, String body) throws IOException {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(status).append("\r\n");
        if (contentType != null && !contentType.isEmpty()) {
            response.append("Content-Type: ").append(contentType).append("\r\n");
            response.append("Content-Length: ").append(body.getBytes().length).append("\r\n");
        }
        response.append("Connection: close\r\n");
        response.append("\r\n");
        response.append(body);
        outputStream.write(response.toString().getBytes());
        outputStream.flush();
    }
}
