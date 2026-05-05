package be.uantwerpen.fti.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

@Service
public class TcpFileTransferService {

    private final int tcpPort;
    private ServerSocket serverSocket;
    private boolean isRunning = true;

    // We keep replicated files separate from local files to avoid confusion
    private final String REPLICATED_FOLDER = "replicated_files/";

    public TcpFileTransferService(@Value("${tcp.port:5000}") int tcpPort) {
        this.tcpPort = tcpPort;
        // Create the folder for replicated files if it doesn't exist yet
        new File(REPLICATED_FOLDER).mkdirs();
    }

    /**
     * Starts the TCP Server in a background thread to listen for incoming files.
     */
    @PostConstruct
    public void startListening() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(tcpPort);
                System.out.println("TCP File Server listening on port " + tcpPort);

                while (isRunning) {
                    Socket clientSocket = serverSocket.accept();
                    handleIncomingFile(clientSocket);
                }
            } catch (IOException e) {
                if (isRunning) {
                    System.err.println("TCP Server Error: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * Reads the file name, file size, and the file bytes from the incoming socket.
     */
    private void handleIncomingFile(Socket clientSocket) {
        new Thread(() -> {
            try (
                    DataInputStream dis = new DataInputStream(clientSocket.getInputStream())
            ) {
                String fileName = dis.readUTF(); // Read the file name first
                long fileSize = dis.readLong();  // Read the file size

                File outputFile = new File(REPLICATED_FOLDER, fileName);

                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[4096];
                    int read;
                    long totalRead = 0;

                    while (totalRead < fileSize && (read = dis.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalRead))) != -1) {
                        fos.write(buffer, 0, read);
                        totalRead += read;
                    }
                }
                System.out.println("Successfully received replicated file: " + fileName);
            } catch (IOException e) {
                System.err.println("Error receiving file: " + e.getMessage());
            } finally {
                try { clientSocket.close(); } catch (IOException ignored) {}
            }
        }).start();
    }

    /**
     * Connects to a target node and streams a file over TCP.
     */
    public void sendFile(String targetIp, File file) {
        new Thread(() -> {
            try (
                    Socket socket = new Socket(targetIp, tcpPort);
                    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                    FileInputStream fis = new FileInputStream(file)
            ) {
                dos.writeUTF(file.getName()); // Send the file name first
                dos.writeLong(file.length()); // Send the file size

                byte[] buffer = new byte[4096];
                int read;
                while ((read = fis.read(buffer)) != -1) {
                    dos.write(buffer, 0, read);
                }
                System.out.println("📤 Successfully transferred " + file.getName() + " to " + targetIp);
            } catch (IOException e) {
                System.err.println("Error sending file " + file.getName() + " to " + targetIp + ": " + e.getMessage());
            }
        }).start();
    }

    @PreDestroy
    public void stopServer() {
        isRunning = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}