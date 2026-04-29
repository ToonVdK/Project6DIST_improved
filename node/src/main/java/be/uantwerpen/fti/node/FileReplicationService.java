package be.uantwerpen.fti.node;

import be.uantwerpen.fti.common.HashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;

@Service
public class FileReplicationService {

    private final NodeState nodeState;
    private final TcpFileTransferService tcpService;
    private final RestTemplate restTemplate;
    private final String namingServerUrl;

    @Value("${node.api.port:8080}")
    private String nodePort;

    private final String LOCAL_FOLDER = "local_files/";

    public FileReplicationService(NodeState nodeState,
                                  TcpFileTransferService tcpService,
                                  @Value("${naming.server.url}") String namingServerUrl) {
        this.nodeState = nodeState;
        this.tcpService = tcpService;
        this.namingServerUrl = namingServerUrl;
        this.restTemplate = new RestTemplate();

        new File(LOCAL_FOLDER).mkdirs();
    }

    // ==========================================
    // PHASE 1: STARTUP SYNCHRONIZATION
    // ==========================================
    public void replicateExistingFiles() {
        System.out.println("Starting Phase: Scanning local files for replication...");
        File folder = new File(LOCAL_FOLDER);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null || listOfFiles.length == 0) {
            System.out.println("No local files found to replicate.");
            return;
        }

        for (File file : listOfFiles) {
            if (file.isFile() && !file.getName().startsWith(".") && !file.getName().endsWith("~")) {
                replicateSingleFile(file);
            }
        }
    }

    public void replicateSingleFile(File file) {
        try {
            String targetIp = restTemplate.getForObject(
                    namingServerUrl + "files/replicate/" + file.getName(), String.class);

            if (targetIp != null) {
                if (targetIp.equals(nodeState.getIpAddress())) {
                    System.out.println("File " + file.getName() + " maps to local node. No transfer needed.");
                    return;
                }
                tcpService.sendFile(targetIp, file);
            }
        } catch (Exception e) {
            System.err.println("Error replicating file " + file.getName() + ": " + e.getMessage());
        }
    }

    // ==========================================
    // PHASE 2: LIVE FOLDER UPDATES
    // ==========================================
    public void notifyReplicaDeletion(String filename) {
        try {
            String targetIp = restTemplate.getForObject(
                    namingServerUrl + "files/replicate/" + filename, String.class);

            if (targetIp != null) {
                if (targetIp.equals(nodeState.getIpAddress())) {
                    System.out.println("File " + filename + " was local only. No remote replica to delete.");
                    return;
                }
                restTemplate.delete("http://" + targetIp + ":" + nodePort + "/api/node/files/" + filename);
            }
        } catch (Exception e) {
            System.err.println("Error notifying replica deletion for " + filename + ": " + e.getMessage());
        }
    }

    // ==========================================
    // PHASE 3: SHUTDOWN LOGIC
    // ==========================================
    public void transferReplicasOnShutdown() {
        System.out.println("🛑 Initiating Phase 3: Shifting replicated files to previous neighbor...");
        File folder = new File("replicated_files/");
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null || listOfFiles.length == 0) {
            System.out.println("No replicated files to transfer.");
            return;
        }

        try {
            int previousNodeId = nodeState.getPreviousID();

            if (previousNodeId == nodeState.getCurrentID()) {
                System.out.println("Only node in network. No need to transfer replicas.");
                return;
            }

            String previousIp = restTemplate.getForObject(
                    namingServerUrl + "ip/" + previousNodeId, String.class);

            if (previousIp != null) {
                for (File file : listOfFiles) {
                    if (file.isFile()) {
                        System.out.println("📦 Transferring replica '" + file.getName() + "' to Node " + previousNodeId);
                        tcpService.sendFile(previousIp, file);
                        Thread.sleep(50);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error transferring replicas during shutdown: " + e.getMessage());
        }
    }

    // THIS IS THE METHOD YOUR SHUTDOWNSERVICE WAS LOOKING FOR!
    public void warnLocalFilesOffline() {
        System.out.println("📢 Warning network: Local files are going offline...");
        File folder = new File(LOCAL_FOLDER);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null || listOfFiles.length == 0) return;

        for (File file : listOfFiles) {
            if (file.isFile() && !file.getName().startsWith(".") && !file.getName().endsWith("~")) {
                try {
                    String targetIp = restTemplate.getForObject(
                            namingServerUrl + "files/replicate/" + file.getName(), String.class);

                    if (targetIp != null && !targetIp.equals(nodeState.getIpAddress())) {
                        System.out.println("Telling Node " + targetIp + " that local file '" + file.getName() + "' is offline.");
                        restTemplate.postForObject("http://" + targetIp + ":" + nodePort + "/api/node/files/" + file.getName() + "/offline", null, String.class);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to warn about offline file " + file.getName() + ": " + e.getMessage());
                }
            }
        }
    }
}