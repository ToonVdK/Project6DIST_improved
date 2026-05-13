package be.uantwerpen.fti.node;

import be.uantwerpen.fti.common.HashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/node")
public class NodeController {

    private final NodeState nodeState;
    private final FileReplicationService replicationService;
    private final RestTemplate restTemplate;

    @Value("${node.api.port:8080}")
    private String nodePort;

    @Value("${naming.server.url}")
    private String namingServerUrl;

    public NodeController(NodeState nodeState, FileReplicationService replicationService) {
        this.nodeState = nodeState;
        this.replicationService = replicationService;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);
    }

    @PostMapping("/next/{newNextId}")
    public String updateNextId(@PathVariable int newNextId) {
        nodeState.setNextID(newNextId);
        return "Updated nextID to: " + newNextId;
    }

    @PostMapping("/previous/{newPrevId}")
    public String updatePreviousId(@PathVariable int newPrevId) {
        nodeState.setPreviousID(newPrevId);
        return "Updated previousID to: " + newPrevId;
    }

    @PostMapping("/networkSize/{size}")
    public String setNetworkSize(@PathVariable int size) {
        System.out.println("Received network size from Naming Server: " + size);
        return "Network size received";
    }

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }

    @GetMapping("/info")
    public Map<String, Object> getNodeInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", nodeState.getName());
        info.put("ip", nodeState.getIpAddress());
        info.put("currentID", nodeState.getCurrentID());
        info.put("previousID", nodeState.getPreviousID());
        info.put("nextID", nodeState.getNextID());
        info.put("knownFiles", nodeState.getFileListSnapshot());
        return info;
    }

    @GetMapping("/hash/{text}")
    public String getDebugHash(@PathVariable String text) {
        int hashValue = HashUtils.calculateHash(text);
        return "The hash for '" + text + "' is: " + hashValue;
    }

    @DeleteMapping("/files/{filename}")
    public ResponseEntity<String> deleteReplicatedFile(@PathVariable String filename) {
        File file = new File("replicated_files/" + filename);

        if (file.exists() && file.delete()) {
            System.out.println("Successfully deleted replica of: " + filename);
            nodeState.removeFile(filename);
            return ResponseEntity.ok("Deleted");
        }

        System.out.println("Replicated file not found for deletion: " + filename);
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/files/{filename}/offline")
    public ResponseEntity<String> localFileWentOffline(@PathVariable String filename) {
        System.out.println("Received warning: The local source of '" + filename + "' has shut down.");
        return ResponseEntity.ok("Warning received");
    }

    @GetMapping("/files/physical")
    public ResponseEntity<Map<String, Object>> getPhysicalFiles() {
        List<String> localFiles = listFilesFromFirstExistingDirectory("local_files", "/local_files", "/app/local_files");
        List<String> replicatedFiles = listFilesFromFirstExistingDirectory("replicated_files", "/replicated_files", "/app/replicated_files");

        Map<String, Object> result = new HashMap<>();
        result.put("local", localFiles);
        result.put("replicated", replicatedFiles);
        return ResponseEntity.ok(result);
    }

    private List<String> listFilesFromFirstExistingDirectory(String... paths) {
        List<String> result = new ArrayList<>();

        for (String path : paths) {
            File directory = new File(path);

            if (!directory.exists() || !directory.isDirectory()) {
                continue;
            }

            File[] files = directory.listFiles();
            if (files == null) {
                return result;
            }

            for (File file : files) {
                if (file.isFile()) {
                    result.add(file.getName());
                }
            }

            return result;
        }

        return result;
    }

    @GetMapping("/files/list")
    public Map<String, NodeState.FileInfo> getFileList() {
        return nodeState.getFileListSnapshot();
    }

    @PostMapping("/files/list/merge")
    public ResponseEntity<String> mergeFileList(@RequestBody Map<String, ?> incomingList) {
        nodeState.mergeFileList(incomingList);
        return ResponseEntity.ok("File list merged");
    }

    @GetMapping("/files/{filename}/exists")
    public ResponseEntity<Boolean> fileExists(@PathVariable String filename) {
        return ResponseEntity.ok(replicationService.hasLocalOrReplicatedCopy(filename));
    }

    @PostMapping("/files/{filename}/lock")
    public ResponseEntity<String> lockFile(@PathVariable String filename) {
        boolean locked = nodeState.lockFile(filename, nodeState.getCurrentID());
        if (locked) {
            return ResponseEntity.ok("File locked: " + filename);
        }
        return ResponseEntity.status(409).body("File is already locked or unknown: " + filename);
    }

    @PostMapping("/files/{filename}/unlock")
    public ResponseEntity<String> unlockFile(@PathVariable String filename) {
        boolean unlocked = nodeState.unlockFile(filename, nodeState.getCurrentID());
        if (unlocked) {
            return ResponseEntity.ok("File unlocked: " + filename);
        }
        return ResponseEntity.status(409).body("Could not unlock file: " + filename);
    }

    @PostMapping("/files/{filename}/promote")
    public ResponseEntity<Boolean> promoteReplicaToLocal(@PathVariable String filename) {
        boolean promoted = replicationService.promoteReplicaToLocal(filename);

        if (promoted) {
            nodeState.updateOwner(filename, nodeState.getCurrentID(), nodeState.getIpAddress());

            NodeState.FileInfo updated = nodeState.getFileInfo(filename);
            if (updated != null) {
                updated.setLocked(false);
                updated.setLockOwnerId(-1);
                nodeState.addOrUpdateFile(updated);
            }

            System.out.println("[OWNERSHIP] Node " + nodeState.getCurrentID() + " promoted and now owns '" + filename + "'.");
        }

        return ResponseEntity.ok(promoted);
    }

    @PostMapping("/agents/failure")
    public ResponseEntity<String> receiveFailureAgent(@RequestBody FailureAgent agent) {
        try {
            agent.configure(nodeState, replicationService, namingServerUrl, nodePort);

            Thread agentThread = new Thread(agent, "FailureAgent-" + agent.getFailedNodeId());
            agentThread.start();
            agentThread.join();

            int nextId = nodeState.getNextID();

            if (nextId == nodeState.getCurrentID()) {
                return ResponseEntity.ok("Failure Agent completed: single node left");
            }

            if (agent.shouldStopBeforePassingTo(nextId)) {
                System.out.println("Failure Agent completed its ring traversal.");
                return ResponseEntity.ok("Failure Agent completed");
            }

            String nextIp = replicationService.getNodeIp(nextId);
            if (nextIp == null || nextIp.trim().isEmpty()) {
                return ResponseEntity.ok("Failure Agent stopped because next node IP was unknown");
            }

            String nextUrl = "http://" + nextIp + ":" + nodePort + "/api/node/agents/failure";
            System.out.println("Passing Failure Agent to next node " + nextId + " at " + nextIp);
            restTemplate.postForEntity(nextUrl, agent, String.class);

            return ResponseEntity.ok("Failure Agent processed and passed to next node");

        } catch (Exception e) {
            System.err.println("Error while processing Failure Agent: " + e.getMessage());
            return ResponseEntity.internalServerError().body("Failure Agent error: " + e.getMessage());
        }
    }
}
