package be.uantwerpen.fti.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Service
@EnableScheduling
public class FailureDetectionService {

    private int lastKnownNextID = -1;
    private int lastKnownPreviousID = -1;
    private final NodeState nodeState;
    private final RestTemplate restTemplate;
    private final String NAMING_SERVER_URL;
    @Value("${node.api.port}") String nodePort;

    public FailureDetectionService(NodeState nodeState,
                                   @Value("${naming.server.url}") String namingServerUrl) {
        this.nodeState = nodeState;
        this.NAMING_SERVER_URL = namingServerUrl;

        // Enforce a timeout
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000); // 2 second connection timeout
        factory.setReadTimeout(2000);    // 2 second read timeout
        this.restTemplate = new RestTemplate(factory);
    }

    // This runs automatically every 5 seconds
    @Scheduled(fixedRate = 5000)
    public void pingNextNode() {
        int previousID = nodeState.getPreviousID();
        int currentID = nodeState.getCurrentID();
        int nextID = nodeState.getNextID();

        // Check if EITHER the next node OR the previous node has changed
        if (nextID != lastKnownNextID || previousID != lastKnownPreviousID) {
            System.out.println("[TOPOLOGY CHANGE] Node " + currentID +
                    " | Previous: " + previousID +
                    " | Next: " + nextID);

            // Update our known state
            lastKnownNextID = nextID;
            lastKnownPreviousID = previousID;
        }

        // Don't ping if we are the only node in the network
        if (nextID == currentID) return;

        try {
            String nextIp = restTemplate.getForObject(NAMING_SERVER_URL + "ip/" + nextID, String.class);
            if (nextIp != null) {
                restTemplate.getForObject("http://" + nextIp + ":" + nodePort + "/api/node/ping", String.class);
            }
        } catch (Exception e) {
            System.err.println("Ping failed! Node " + nextID + " is dead. Initiating recovery...");
            handleNodeFailure(nextID);
        }
    }

    private void handleNodeFailure(int failedNodeId) {
        try {
            // Step 1: Request the next and previous parameters of the failed node from the Naming Server
            int[] neighbors = restTemplate.getForObject(NAMING_SERVER_URL + "neighbors/" + failedNodeId, int[].class);

            // Step 2: Remove the dead node from the Naming Server
            restTemplate.delete(NAMING_SERVER_URL + "hash/" + failedNodeId);

            if (neighbors != null && neighbors.length == 2) {
                int previousOfFailed = neighbors[0];
                int nextOfFailed = neighbors[1];

                // Step 3: Update the previous node's next parameter
                String prevIp = restTemplate.getForObject(NAMING_SERVER_URL + "ip/" + previousOfFailed, String.class);
                if (prevIp != null) {
                    restTemplate.postForObject("http://" + prevIp + ":" + nodePort + "/api/node/next/" + nextOfFailed, null, String.class);
                }

                // Step 4: Update the next node's previous parameter [cite: 129]
                String nextIp = restTemplate.getForObject(NAMING_SERVER_URL + "ip/" + nextOfFailed, String.class);
                if (nextIp != null) {
                    restTemplate.postForObject("http://" + nextIp + ":" + nodePort + "/api/node/previous/" + previousOfFailed, null, String.class);
                }

                System.out.println("Network successfully recovered from failure of node " + failedNodeId);
            }
        } catch (Exception e) {
            System.err.println("Error during failure recovery: " + e.getMessage());
        }
    }
}