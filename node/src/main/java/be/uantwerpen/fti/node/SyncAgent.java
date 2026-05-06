package be.uantwerpen.fti.node;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.Serializable;
import java.util.Map;

public class SyncAgent implements Runnable, Serializable {

    private static final long serialVersionUID = 1L;

    private transient NodeState nodeState;
    private transient FileReplicationService replicationService;
    private transient RestTemplate restTemplate;

    private long intervalMs = 5000;
    private boolean running = true;

    public SyncAgent() {
        // Needed for serialization/deserialization.
    }

    public SyncAgent(
            NodeState nodeState,
            FileReplicationService replicationService,
            long intervalMs
    ) {
        this.nodeState = nodeState;
        this.replicationService = replicationService;
        this.restTemplate = new RestTemplate();
        this.intervalMs = intervalMs;
    }

    public void configure(
            NodeState nodeState,
            FileReplicationService replicationService,
            long intervalMs
    ) {
        this.nodeState = nodeState;
        this.replicationService = replicationService;
        this.intervalMs = intervalMs;

        // Add the timeout factory here!
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public void run() {
        if (nodeState == null || replicationService == null) {
            throw new IllegalStateException("SyncAgent was not configured before running.");
        }

        if (restTemplate == null) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(2000);
            factory.setReadTimeout(2000);
            restTemplate = new RestTemplate(factory);
        }

        System.out.println("Sync Agent running on node " + nodeState.getCurrentID());

        while (running) {
            try {
                scanLocalOwnedFiles();
                synchronizeWithNextNode();

                Thread.sleep(intervalMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;

            } catch (Exception e) {
                System.err.println("Sync Agent error: " + e.getMessage());

                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }

    private void scanLocalOwnedFiles() {
        File[] localFiles = replicationService.getLocalFiles();

        for (File file : localFiles) {
            if (file.isFile()
                    && !file.getName().startsWith(".")
                    && !file.getName().endsWith("~")) {
                nodeState.addOrUpdateOwnedFile(file.getName());
            }
        }
    }

    private void synchronizeWithNextNode() {
        int nextId = nodeState.getNextID();

        if (nextId == nodeState.getCurrentID()) {
            return;
        }

        String nextIp = replicationService.getNodeIp(nextId);

        if (nextIp == null || nextIp.trim().isEmpty()) {
            return;
        }

        String nextUrl = "http://" + nextIp + ":8080/api/node/files/list";

        try {
            @SuppressWarnings("unchecked")
            Map<String, NodeState.FileInfo> nextFileList =
                    restTemplate.getForObject(nextUrl, Map.class);

            if (nextFileList != null) {
                nodeState.mergeFileList(nextFileList);
            }

            /*
             * Push our merged view back to the next node.
             * This makes convergence faster and keeps all nodes synchronized.
             */
            String mergeUrl = "http://" + nextIp + ":8080/api/node/files/list/merge";
            restTemplate.postForObject(mergeUrl, nodeState.getFileListSnapshot(), String.class);

        } catch (Exception e) {
            System.err.println("Sync Agent could not sync with next node " + nextId + ": " + e.getMessage());
        }
    }

    public void stop() {
        this.running = false;
    }
}