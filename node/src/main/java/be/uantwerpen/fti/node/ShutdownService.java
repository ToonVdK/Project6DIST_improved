package be.uantwerpen.fti.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy;

@Service
public class ShutdownService {

    private final NodeState nodeState;
    private final RestTemplate restTemplate = new RestTemplate();
    private final String NAMING_SERVER_URL;
    @Value("${node.api.port}") String nodePort;

    // NEW: We inject the FileReplicationService so we can shift files on shutdown
    private final FileReplicationService replicationService;

    // NEW: Constructor updated to accept the FileReplicationService
    public ShutdownService(NodeState nodeState,
                           @Value("${naming.server.url}") String namingServerUrl,
                           FileReplicationService replicationService) {
        this.nodeState = nodeState;
        this.NAMING_SERVER_URL = namingServerUrl;
        this.replicationService = replicationService;
    }

    /**
     * This method runs automatically when the Spring Boot application shuts down.
     */
    @PreDestroy
    public void shutdown() {
        System.out.println("Initiating graceful shutdown for node: " + nodeState.getName());

        int previousID = nodeState.getPreviousID();
        int nextID = nodeState.getNextID();

        // If we are the only node in the network, we only need to tell the Naming Server
        if (previousID == nodeState.getCurrentID() && nextID == nodeState.getCurrentID()) {
            removeFromNamingServer();
            return;
        }

        // ==========================================
        // LAB 5 PHASE 3: FILE SYNCHRONIZATION
        // ==========================================
        try {
            // 1. Shift our replicated files to the previous neighbor BEFORE we leave the ring
            replicationService.transferReplicasOnShutdown();

            // 2. Warn the owners of our local files that our local physical files are going offline
            replicationService.warnLocalFilesOffline();
        } catch (Exception e) {
            System.err.println("Error during file synchronization on shutdown: " + e.getMessage());
        }
        // ==========================================

        try {
            // 1. Send the ID of the next node to the previous node
            // We need to ask the Naming Server for the IP of our previous node first!
            String prevIp = restTemplate.getForObject(NAMING_SERVER_URL + "ip/" + previousID, String.class);
            if (prevIp != null) {
                restTemplate.postForObject("http://" + prevIp + ":" + nodePort + "/api/node/next/" + nextID, null, String.class);
                System.out.println("Updated previous node (" + previousID + ") with new nextID: " + nextID);
            }

            // 2. Send the ID of the previous node to the next node
            // We need to ask the Naming Server for the IP of our next node!
            String nextIp = restTemplate.getForObject(NAMING_SERVER_URL + "ip/" + nextID, String.class);
            if (nextIp != null) {
                restTemplate.postForObject("http://" + nextIp + ":" + nodePort + "/api/node/previous/" + previousID, null, String.class);
                System.out.println("Updated next node (" + nextID + ") with new previousID: " + previousID);
            }

        } catch (Exception e) {
            System.err.println("Error during shutdown topology update: " + e.getMessage());
        }

        startGracefulOwnershipHandoff(nextID);
        // 3. Remove the node from the Naming server's Map
        removeFromNamingServer();
    }

    private void startGracefulOwnershipHandoff(int starterNodeId) {
        try {
            if (starterNodeId == nodeState.getCurrentID()) {
                System.out.println("[SHUTDOWN] No ownership handoff needed: only node in network.");
                return;
            }

            String starterIp = restTemplate.getForObject(
                    NAMING_SERVER_URL + "ip/" + starterNodeId,
                    String.class
            );

            if (starterIp == null || starterIp.trim().isEmpty()) {
                System.out.println("[SHUTDOWN] Cannot start ownership handoff: starter node IP unknown.");
                return;
            }

            /*
             * Push this node's last known file metadata to the starter node.
             * This helps the surviving nodes know which files were owned by the leaving node.
             */
            restTemplate.postForObject(
                    "http://" + starterIp + ":" + nodePort + "/api/node/files/list/merge",
                    nodeState.getFileListSnapshot(),
                    String.class
            );

            FailureAgent agent = new FailureAgent(
                    nodeState.getCurrentID(),
                    starterNodeId
            );

            System.out.println(
                    "[SHUTDOWN] Starting graceful ownership handoff for leaving node " +
                            nodeState.getCurrentID() +
                            " via starter node " + starterNodeId
            );

            restTemplate.postForObject(
                    "http://" + starterIp + ":" + nodePort + "/api/node/agents/failure",
                    agent,
                    String.class
            );

            System.out.println("[SHUTDOWN] Graceful ownership handoff completed/started.");

        } catch (Exception e) {
            System.err.println("[SHUTDOWN] Failed to start graceful ownership handoff: " + e.getMessage());
        }
    }

    private void removeFromNamingServer() {
        try {
            restTemplate.delete(NAMING_SERVER_URL + nodeState.getName());
            System.out.println("Successfully removed node from Naming Server.");
        } catch (Exception e) {
            System.err.println("Failed to remove node from Naming Server: " + e.getMessage());
        }
    }
}