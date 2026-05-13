package be.uantwerpen.fti.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class TopologyReconciliationService {

    private final NodeState nodeState;
    private final RestTemplate restTemplate = new RestTemplate();
    private final String namingServerUrl;

    public TopologyReconciliationService(
            NodeState nodeState,
            @Value("${naming.server.url}") String namingServerUrl
    ) {
        this.nodeState = nodeState;
        this.namingServerUrl = namingServerUrl;
    }

    /*
     * Fix for race conditions when many nodes start nearly at the same time:
     * discovery multicast may temporarily give wrong previous/next pointers. The naming
     * server has the authoritative sorted topology, so each node periodically reconciles
     * its local previousID/nextID with the naming server.
     */
    @Scheduled(initialDelay = 8000, fixedRate = 10000)
    public void reconcileTopologyWithNamingServer() {
        try {
            int currentId = nodeState.getCurrentID();
            int[] neighbours = restTemplate.getForObject(
                    namingServerUrl + "neighbors/" + currentId,
                    int[].class
            );

            if (neighbours == null || neighbours.length != 2) {
                return;
            }

            int newPrevious = neighbours[0];
            int newNext = neighbours[1];

            if (nodeState.getPreviousID() != newPrevious || nodeState.getNextID() != newNext) {
                System.out.println(
                        "[TOPOLOGY RECONCILE] Correcting node " + currentId +
                                " | previous " + nodeState.getPreviousID() + " -> " + newPrevious +
                                " | next " + nodeState.getNextID() + " -> " + newNext
                );

                nodeState.setPreviousID(newPrevious);
                nodeState.setNextID(newNext);
            }

        } catch (Exception e) {
            // Naming server may not be reachable during startup/shutdown; ignore quietly.
        }
    }
}
