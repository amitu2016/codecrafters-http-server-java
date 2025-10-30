import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
  public static void main(String[] args) {
    // You can use print statements as follows for debugging, they'll be visible when running tests.
    System.out.println("Logs from your program will appear here!");

    // TODO: Uncomment the code below to pass the first stage
    
     try {
       ServerSocket serverSocket = new ServerSocket(4221);
    
       // Since the tester restarts your program quite often, setting SO_REUSEADDR
       // ensures that we don't run into 'Address already in use' errors
       serverSocket.setReuseAddress(true);
    
       Socket clientSocket = serverSocket.accept(); // Wait for connection from client.
       System.out.println("accepted new connection");

       InputStream inputStream = clientSocket.getInputStream();
       OutputStream outputStream = clientSocket.getOutputStream();
       BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));

       String inputLine = bufferedReader.readLine();
       System.out.println("Input: "+inputLine);
       String[] httpRequest = inputLine.split(" ");
       String responseBody = "";
       int contentLength = 0;
         if (httpRequest.length >= 2) {
             String path = httpRequest[1]; // /echo/abc

             String prefix = "/echo/";
             if (path.contentEquals(prefix)) {
                 responseBody = path.substring(prefix.length());
                 contentLength = responseBody.length();
                 System.out.println("Content: " + responseBody);
                 System.out.println("Length: " + contentLength);

                 String response = String.format(
                         "HTTP/1.1 200 OK\r\n" +
                                 "Content-Type: text/plain\r\n" +
                                 "Content-Length: %d\r\n" +
                                 "\r\n" +
                                 "%s",
                         contentLength, responseBody
                 );
                 outputStream.write(response.getBytes());
                 outputStream.flush();
             } else {
                 outputStream.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
             }
         } else {
             outputStream.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
         }

     } catch (IOException e) {
       System.out.println("IOException: " + e.getMessage());
     }
  }
}
