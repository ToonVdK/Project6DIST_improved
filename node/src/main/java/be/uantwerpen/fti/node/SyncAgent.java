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

    private String nodePort = "8080";

    public SyncAgent() {
        // Needed for serialization/deserialization.
    }

    public SyncAgent(
            NodeState nodeState,
            FileReplicationService replicationService,
            long intervalMs,
            String nodePort
    ) {
        this.nodeState = nodeState;
        this.replicationService = replicationService;
        this.intervalMs = intervalMs;
        this.nodePort = nodePort;
        this.restTemplate = createRestTemplate();
    }

    public void configure(
            NodeState nodeState,
            FileReplicationService replicationService,
            long intervalMs,
            String nodePort
    ) {
        this.nodeState = nodeState;
        this.replicationService = replicationService;
        this.intervalMs = intervalMs;
        this.nodePort = nodePort;
        this.restTemplate = createRestTemplate();
    }

    @Override
    public void run() {
        if (nodeState == null || replicationService == null) {
            throw new IllegalStateException("SyncAgent was not configured before running.");
        }

        if (restTemplate == null) {
            restTemplate = createRestTemplate();
        }

        System.out.println("[SYNC AGENT] Started on node " + nodeState.getCurrentID());

        while (running) {
            try {
                scanLocalOwnedFiles();
                synchronizeWithNextNode();

                Thread.sleep(intervalMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;

            } catch (Exception e) {
                System.err.println("[SYNC AGENT] Error: " + e.getMessage());

                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }

        System.out.println("[SYNC AGENT] Stopped on node " + nodeState.getCurrentID());
    }

    private void scanLocalOwnedFiles() {
        File[] localFiles = replicationService.getLocalFiles();

        int count = 0;

        for (File file : localFiles) {
            if (isValidFile(file)) {
                nodeState.addOrUpdateOwnedFile(file.getName());
                count++;
            }
        }

        System.out.println("[SYNC AGENT] Scanned " + count + " local owned file(s).");
    }

    private void synchronizeWithNextNode() {
        int nextId = nodeState.getNextID();

        if (nextId == nodeState.getCurrentID()) {
            return;
        }

        String nextIp = replicationService.getNodeIp(nextId);

        if (nextIp == null || nextIp.trim().isEmpty()) {
            System.out.println("[SYNC AGENT] Could not sync. Next node IP unknown for ID " + nextId);
            return;
        }

        String nextListUrl = "http://" + nextIp + ":" + nodePort + "/api/node/files/list";
        String nextMergeUrl = "http://" + nextIp + ":" + nodePort + "/api/node/files/list/merge";

        try {
            @SuppressWarnings("unchecked")
            Map<String, ?> nextFileList = restTemplate.getForObject(nextListUrl, Map.class);

            if (nextFileList != null) {
                nodeState.mergeFileList(nextFileList);
            }

            restTemplate.postForObject(
                    nextMergeUrl,
                    nodeState.getFileListSnapshot(),
                    String.class
            );

            System.out.println("[SYNC AGENT] Synced file list with next node " + nextId);

        } catch (Exception e) {
            System.err.println(
                    "[SYNC AGENT] Could not sync with next node " +
                            nextId + " at " + nextIp + ": " + e.getMessage()
            );
        }
    }

    private boolean isValidFile(File file) {
        return file != null
                && file.isFile()
                && !file.getName().startsWith(".")
                && !file.getName().endsWith("~");
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);
        return new RestTemplate(factory);
    }

    public void stop() {
        this.running = false;
    }
}