import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

/**
 * HTTP server with:
 *  - persistent HTTP/1.1 connections (handle multiple requests per socket)
 *  - gzip compression on /echo/{msg}
 *  - GET/POST /files/{file}
 *  - / and /user-agent routes
 *
 * Key change: ClientHandler.run() loops reading requests until the client asks to close
 * (Connection: close) or the socket is closed by the client.
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("Server starting...");

        // parse --directory <path> if present
        String filesDirectory = null;
        for (int i = 0; i < args.length; i++) {
            if ("--directory".equals(args[i]) && i + 1 < args.length) {
                filesDirectory = args[i + 1];
                break;
            }
        }
        if (filesDirectory == null) filesDirectory = new File(".").getAbsolutePath();
        final String baseDir = filesDirectory;

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
 * Handles one socket; now supports multiple sequential requests on the same socket.
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
        // keep streams open for the lifetime of the connection
        try (
                InputStream in = clientSocket.getInputStream();
                OutputStream out = clientSocket.getOutputStream()
        ) {
            // Loop: handle multiple requests on the same connection
            while (true) {
                // Read request headers (stop at \r\n\r\n)
                byte[] headerBytes = readUntilDoubleCRLF(in);
                if (headerBytes == null || headerBytes.length == 0) {
                    // client closed connection or sent nothing -> break loop
                    break;
                }

                // Parse header block
                String headerBlock = new String(headerBytes, "ISO-8859-1");
                String[] lines = headerBlock.split("\r\n");
                String requestLine = lines[0];
                System.out.println("Request: " + requestLine);

                String[] requestParts = requestLine.split(" ");
                if (requestParts.length < 2) {
                    // malformed request -> respond and close
                    writeTextResponse(out, "400 Bad Request", "Bad Request", true);
                    break;
                }

                String method = requestParts[0];
                String path = requestParts[1];

                // Parse headers into map (lowercased keys)
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

                // Decide whether client requests connection close
                // Per HTTP/1.1, connection is persistent unless client sends "Connection: close"
                String connectionHeader = headers.get("connection");
                boolean clientWantsClose = connectionHeader != null && connectionHeader.equalsIgnoreCase("close");

                // Route handling — responses must include Connection: close header if we will close
                try {
                    if ("/".equals(path)) {
                        // root: no compression, don't add Connection header unless closing
                        writeTextResponse(out, "200 OK", "OK", clientWantsClose);
                    } else if (path.startsWith("/echo/")) {
                        String msg = path.substring("/echo/".length());
                        boolean clientAcceptsGzip = clientAcceptsGzip(headers.get("accept-encoding"));
                        if (clientAcceptsGzip) {
                            writeTextResponseWithEncoding(out, "200 OK", msg, "gzip", clientWantsClose);
                        } else {
                            writeTextResponse(out, "200 OK", msg, clientWantsClose);
                        }
                    } else if ("/user-agent".equals(path)) {
                        String ua = headers.getOrDefault("user-agent", "");
                        writeTextResponse(out, "200 OK", ua, clientWantsClose);
                    } else if (path.startsWith("/files/")) {
                        String filename = path.substring("/files/".length());
                        if ("GET".equalsIgnoreCase(method)) {
                            handleFileGet(out, filename, clientWantsClose);
                        } else if ("POST".equalsIgnoreCase(method)) {
                            handleFilePost(out, in, filename, headers, clientWantsClose);
                        } else {
                            writeTextResponse(out, "405 Method Not Allowed", "Method Not Allowed", clientWantsClose);
                        }
                    } else {
                        writeTextResponse(out, "404 Not Found", "Not Found", clientWantsClose);
                    }
                } catch (IOException e) {
                    // If handling the request fails, break the loop and close connection
                    System.err.println("Error handling request: " + e.getMessage());
                    break;
                }

                // If client requested close, break loop and close socket
                if (clientWantsClose) {
                    break;
                }

                // Otherwise, loop to read next request on same connection
            }
        } catch (IOException e) {
            System.err.println("Client handling error: " + e.getMessage());
        } finally {
            // Ensure socket is closed
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Helper to check gzip support in Accept-Encoding header.
     */
    private boolean clientAcceptsGzip(String acceptEncodingHeader) {
        if (acceptEncodingHeader == null) return false;
        String[] parts = acceptEncodingHeader.split(",");
        for (String p : parts) {
            if (p.trim().toLowerCase().startsWith("gzip")) return true;
        }
        return false;
    }

    /**
     * Write a plain-text response. If closeConnection==true, include Connection: close header.
     */
    private void writeTextResponse(OutputStream out, String status, String body, boolean closeConnection) throws IOException {
        byte[] bodyBytes = body.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append("\r\n");
        sb.append("Content-Type: text/plain\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        if (closeConnection) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        out.write(bodyBytes);
        out.flush();
    }

    /**
     * Write response that uses Content-Encoding (gzip). If closeConnection==true, include Connection: close header.
     */
    private void writeTextResponseWithEncoding(OutputStream out, String status, String body, String encoding, boolean closeConnection) throws IOException {
        byte[] bodyUtf8 = body.getBytes("UTF-8");
        if ("gzip".equalsIgnoreCase(encoding)) {
            byte[] compressed = gzipCompress(bodyUtf8);
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ").append(status).append("\r\n");
            sb.append("Content-Encoding: gzip\r\n");
            sb.append("Content-Type: text/plain\r\n");
            sb.append("Content-Length: ").append(compressed.length).append("\r\n");
            if (closeConnection) sb.append("Connection: close\r\n");
            sb.append("\r\n");
            out.write(sb.toString().getBytes("ISO-8859-1"));
            out.write(compressed);
            out.flush();
        } else {
            writeTextResponse(out, status, body, closeConnection);
        }
    }

    /**
     * Compress bytes with gzip.
     */
    private byte[] gzipCompress(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(input);
        }
        return baos.toByteArray();
    }

    /**
     * Read until \r\n\r\n. Returns bytes before the final CRLFCRLF.
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
     * GET /files/{filename} with optional Connection header handling.
     */
    private void handleFileGet(OutputStream out, String filename, boolean closeConnection) throws IOException {
        File base = new File(filesDirectory);
        File requested = new File(base, filename);

        String baseCanonical = base.getCanonicalPath();
        String reqCanonical = requested.getCanonicalPath();
        if (!reqCanonical.startsWith(baseCanonical)) {
            writeRaw(out, "HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            return;
        }

        if (!requested.exists() || !requested.isFile()) {
            writeRaw(out, "HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            return;
        }

        byte[] content = Files.readAllBytes(requested.toPath());
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 200 OK\r\n");
        sb.append("Content-Type: application/octet-stream\r\n");
        sb.append("Content-Length: ").append(content.length).append("\r\n");
        if (closeConnection) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        out.write(content);
        out.flush();
    }

    /**
     * POST /files/{filename} with body reading and Connection header handling.
     */
    private void handleFilePost(OutputStream out, InputStream in, String filename, Map<String, String> headers, boolean closeConnection) throws IOException {
        File base = new File(filesDirectory);
        File target = new File(base, filename);

        String baseCanonical = base.getCanonicalPath();
        String targetCanonical = target.getCanonicalPath();
        if (!targetCanonical.startsWith(baseCanonical)) {
            writeRaw(out, "HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
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

        // Send 201 Created; include Connection: close only if we plan to close
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 201 Created\r\n");
        if (closeConnection) sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes("ISO-8859-1"));
        out.flush();
    }

    /** Helper to write raw bytes and flush. */
    private void writeRaw(OutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }
}
