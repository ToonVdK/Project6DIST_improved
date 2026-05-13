package be.uantwerpen.fti.node;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ShutdownService {

    private final NodeState nodeState;
    private final RestTemplate restTemplate = new RestTemplate();
    private final String NAMING_SERVER_URL;
    private final FileReplicationService replicationService;

    @Value("${node.api.port}")
    String nodePort;

    public ShutdownService(
            NodeState nodeState,
            @Value("${naming.server.url}") String namingServerUrl,
            FileReplicationService replicationService
    ) {
        this.nodeState = nodeState;
        this.NAMING_SERVER_URL = namingServerUrl;
        this.replicationService = replicationService;
    }

    @PreDestroy
    public void shutdown() {
        System.out.println("Initiating graceful shutdown for node: " + nodeState.getName());

        int currentID = nodeState.getCurrentID();
        int previousID = nodeState.getPreviousID();
        int nextID = nodeState.getNextID();

        if (previousID == currentID && nextID == currentID) {
            removeFromNamingServer();
            return;
        }

        try {
            /*
             * Lab 5 behavior: replicas that were stored on this node for other owners
             * are shifted before the node disappears.
             */
            replicationService.transferReplicasOnShutdown();
            replicationService.warnLocalFilesOffline();
        } catch (Exception e) {
            System.err.println("Error during file synchronization on shutdown: " + e.getMessage());
        }

        try {
            /*
             * Patch the ring first, so the FailureAgent/ownership handoff can travel over
             * the surviving nodes without passing through this leaving node.
             */
            String prevIp = restTemplate.getForObject(
                    NAMING_SERVER_URL + "ip/" + previousID,
                    String.class
            );

            if (prevIp != null) {
                restTemplate.postForObject(
                        "http://" + prevIp + ":" + nodePort + "/api/node/next/" + nextID,
                        null,
                        String.class
                );

                System.out.println("Updated previous node (" + previousID + ") with new nextID: " + nextID);
            }

            String nextIp = restTemplate.getForObject(
                    NAMING_SERVER_URL + "ip/" + nextID,
                    String.class
            );

            if (nextIp != null) {
                restTemplate.postForObject(
                        "http://" + nextIp + ":" + nodePort + "/api/node/previous/" + previousID,
                        null,
                        String.class
                );

                System.out.println("Updated next node (" + nextID + ") with new previousID: " + previousID);
            }

        } catch (Exception e) {
            System.err.println("Error during shutdown topology update: " + e.getMessage());
        }

        /*
         * Lab 6 extension:
         * A graceful shutdown is not a crash, so FailureDetectionService will not start a
         * FailureAgent. We explicitly start the same ownership handoff here, before the
         * node is removed from the Naming Server.
         */
        startGracefulOwnershipHandoff(nextID);

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
             * Push this node's last known file metadata to the starter node. This makes sure
             * the surviving ring knows which files belonged to the leaving node.
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
            System.out.println("Successfully removed node from Naming Server: " + nodeState.getName());
        } catch (Exception e) {
            System.err.println("Failed to remove node from Naming Server: " + e.getMessage());
        }
    }
}
