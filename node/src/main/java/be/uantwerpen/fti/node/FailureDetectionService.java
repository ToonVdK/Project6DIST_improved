package be.uantwerpen.fti.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@EnableScheduling
public class FailureDetectionService {

    private int lastKnownNextID = -1;
    private int lastKnownPreviousID = -1;

    private final NodeState nodeState;
    private final RestTemplate restTemplate;
    private final String namingServerUrl;

    private final Set<Integer> failuresBeingHandled = ConcurrentHashMap.newKeySet();

    @Value("${node.api.port:8080}")
    private String nodePort;

    public FailureDetectionService(
            NodeState nodeState,
            @Value("${naming.server.url}") String namingServerUrl
    ) {
        this.nodeState = nodeState;
        this.namingServerUrl = namingServerUrl;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);

        this.restTemplate = new RestTemplate(factory);
    }

    /*
     * Initial delay avoids false failure detection while many nodes are bootstrapping and
     * the topology reconciliation service is still correcting previous/next IDs.
     */
    @Scheduled(initialDelay = 12000, fixedRate = 5000)
    public void pingNextNode() {
        int previousID = nodeState.getPreviousID();
        int currentID = nodeState.getCurrentID();
        int nextID = nodeState.getNextID();

        if (nextID != lastKnownNextID || previousID != lastKnownPreviousID) {
            System.out.println(
                    "[TOPOLOGY CHANGE] Node " + currentID +
                            " | Previous: " + previousID +
                            " | Next: " + nextID
            );

            lastKnownNextID = nextID;
            lastKnownPreviousID = previousID;
        }

        if (nextID == currentID) {
            return;
        }

        try {
            String nextIp = restTemplate.getForObject(namingServerUrl + "ip/" + nextID, String.class);

            if (nextIp != null) {
                restTemplate.getForObject(
                        "http://" + nextIp + ":" + nodePort + "/api/node/ping",
                        String.class
                );
            }

        } catch (Exception e) {
            System.err.println("Ping failed! Node " + nextID + " is dead. Initiating recovery...");
            handleNodeFailure(nextID);
        }
    }

    private void handleNodeFailure(int failedNodeId) {
        if (!failuresBeingHandled.add(failedNodeId)) {
            System.out.println("Failure for node " + failedNodeId + " is already being handled.");
            return;
        }

        try {
            int[] neighbors = restTemplate.getForObject(
                    namingServerUrl + "neighbors/" + failedNodeId,
                    int[].class
            );

            restTemplate.delete(namingServerUrl + "hash/" + failedNodeId);

            if (neighbors != null && neighbors.length == 2) {
                int previousOfFailed = neighbors[0];
                int nextOfFailed = neighbors[1];

                String prevIp = restTemplate.getForObject(
                        namingServerUrl + "ip/" + previousOfFailed,
                        String.class
                );

                if (prevIp != null) {
                    restTemplate.postForObject(
                            "http://" + prevIp + ":" + nodePort + "/api/node/next/" + nextOfFailed,
                            null,
                            String.class
                    );
                }

                String nextIp = restTemplate.getForObject(
                        namingServerUrl + "ip/" + nextOfFailed,
                        String.class
                );

                if (nextIp != null) {
                    restTemplate.postForObject(
                            "http://" + nextIp + ":" + nodePort + "/api/node/previous/" + previousOfFailed,
                            null,
                            String.class
                    );
                }

                System.out.println("Network topology recovered from failure of node " + failedNodeId);

                FailureAgent failureAgent = new FailureAgent(failedNodeId, nodeState.getCurrentID());

                restTemplate.postForObject(
                        "http://localhost:" + nodePort + "/api/node/agents/failure",
                        failureAgent,
                        String.class
                );

                System.out.println("Failure Agent started for failed node " + failedNodeId);
            }

        } catch (Exception e) {
            System.err.println("Error during failure recovery: " + e.getMessage());

        } finally {
            failuresBeingHandled.remove(failedNodeId);
        }
    }
}
