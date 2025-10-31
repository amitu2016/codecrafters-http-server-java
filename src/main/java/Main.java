import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point for the lightweight HTTP server.
 *
 * <p>This server listens on port 4221 and handles multiple client connections
 * concurrently using a fixed thread pool. Each client connection is processed
 * independently by a dedicated {@link ClientHandler} thread.</p>
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("Server starting...");

        // Create a fixed thread pool to handle concurrent client connections.
        // This approach prevents the server from creating unbounded threads,
        // which could exhaust system resources under high load.
        ExecutorService executor = Executors.newFixedThreadPool(10);

        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            System.out.println("Server is listening on port 4221...");

            // The main server loop runs indefinitely, accepting new connections.
            while (true) {
                // Blocks until a new client connects.
                Socket clientSocket = serverSocket.accept();
                System.out.println("Accepted new connection from: " + clientSocket.getInetAddress());

                // Submit client handling task to the thread pool for concurrent processing.
                executor.submit(new ClientHandler(clientSocket));
            }

        } catch (IOException e) {
            System.err.println("Server exception: " + e.getMessage());
        }
    }
}

/**
 * Handles a single client connection.
 *
 * <p>This class reads the HTTP request from the client, parses it,
 * and sends back an appropriate HTTP response. Supported endpoints include:</p>
 * <ul>
 *   <li><b>GET /</b> - Returns HTTP 200 OK</li>
 *   <li><b>GET /echo/{text}</b> - Returns {text} in response body</li>
 *   <li><b>GET /user-agent</b> - Returns the client's User-Agent header</li>
 * </ul>
 */
class ClientHandler implements Runnable {

    private final Socket clientSocket;

    public ClientHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (
                InputStream inputStream = clientSocket.getInputStream();
                OutputStream outputStream = clientSocket.getOutputStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))
        ) {
            // Read the request line (e.g., "GET /echo/abc HTTP/1.1")
            String requestLine = reader.readLine();
            System.out.println("Request: " + requestLine);

            if (requestLine == null || requestLine.isEmpty()) {
                writeResponse(outputStream, "400 Bad Request", "", "");
                return;
            }

            String[] httpRequest = requestLine.split(" ");
            if (httpRequest.length < 2) {
                writeResponse(outputStream, "400 Bad Request", "", "");
                return;
            }

            String method = httpRequest[0];
            String path = httpRequest[1];

            // Only handle GET requests for simplicity
            if (!"GET".equalsIgnoreCase(method)) {
                writeResponse(outputStream, "405 Method Not Allowed", "", "");
                return;
            }

            // Route: root "/"
            if ("/".equals(path)) {
                writeResponse(outputStream, "200 OK", "", "");
            }

            // Route: /echo/{message}
            else if (path.startsWith("/echo/")) {
                String message = path.substring("/echo/".length());
                writeResponse(outputStream, "200 OK", "text/plain", message);
            }

            // Route: /user-agent
            else if ("/user-agent".equals(path)) {
                String userAgent = extractUserAgent(reader);
                writeResponse(outputStream, "200 OK", "text/plain", userAgent);
            }

            // Route not found
            else {
                writeResponse(outputStream, "404 Not Found", "", "");
            }

        } catch (IOException e) {
            System.err.println("Client handling error: " + e.getMessage());
        } finally {
            // Ensure socket closure even if exception occurs
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing socket: " + e.getMessage());
            }
        }
    }

    /**
     * Extracts the User-Agent header from the HTTP request headers.
     *
     * @param reader BufferedReader reading from client input stream
     * @return User-Agent value, or an empty string if not found
     * @throws IOException if an I/O error occurs
     */
    private String extractUserAgent(BufferedReader reader) throws IOException {
        String headerLine;
        while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
            if (headerLine.startsWith("User-Agent:")) {
                return headerLine.substring("User-Agent:".length()).trim();
            }
        }
        return "";
    }

    /**
     * Writes a complete HTTP response to the client output stream.
     *
     * @param outputStream the output stream to write to
     * @param status       HTTP status line (e.g., "200 OK", "404 Not Found")
     * @param contentType  value for the Content-Type header (can be empty)
     * @param body         response body (can be empty)
     * @throws IOException if an I/O error occurs
     */
    private void writeResponse(OutputStream outputStream, String status, String contentType, String body) throws IOException {
        StringBuilder response = new StringBuilder();
        response.append("HTTP/1.1 ").append(status).append("\r\n");
        if (!contentType.isEmpty()) {
            response.append("Content-Type: ").append(contentType).append("\r\n");
            response.append("Content-Length: ").append(body.length()).append("\r\n");
        }
        response.append("\r\n").append(body);
        outputStream.write(response.toString().getBytes());
        outputStream.flush();
    }
}
