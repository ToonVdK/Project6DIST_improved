package be.uantwerpen.fti.node;
import be.uantwerpen.fti.common.HashUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.io.File;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/node")
public class NodeController {

    private final NodeState nodeState;

    public NodeController(NodeState nodeState) {
        this.nodeState = nodeState;
    }

    // Endpoint to update this node's nextID
    @PostMapping("/next/{newNextId}")
    public String updateNextId(@PathVariable int newNextId) {
        nodeState.setNextID(newNextId);
        return "Updated nextID to: " + newNextId;
    }

    // Endpoint to update this node's previousID
    @PostMapping("/previous/{newPrevId}")
    public String updatePreviousId(@PathVariable int newPrevId) {
        nodeState.setPreviousID(newPrevId);
        return "Updated previousID to: " + newPrevId;
    }

    @PostMapping("/networkSize/{size}")
    public String setNetworkSize(@PathVariable int size) {
        System.out.println("Received network size from Naming Server: " + size);
        // If size == 1, we are the only node. NodeState handles this by default!
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
        return info;
    }
    @DeleteMapping("/files/{filename}")
    public ResponseEntity<String> deleteReplicatedFile(@PathVariable String filename) {
        File file = new File("replicated_files/" + filename);

        if (file.exists() && file.delete()) {
            System.out.println("🗑️ Successfully deleted replica of: " + filename);
            return ResponseEntity.ok("Deleted");
        }

        System.out.println("⚠️ Replicated file not found for deletion: " + filename);
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/files/{filename}/offline")
    public ResponseEntity<String> localFileWentOffline(@PathVariable String filename) {
        System.out.println("⚠️ Received warning: The local source of '" + filename + "' has shut down.");
        return ResponseEntity.ok("Warning received");
    }
    @GetMapping("/hash/{text}")
    public String getDebugHash(@PathVariable String text) {
        int hashValue = HashUtils.calculateHash(text);
        return "The hash for '" + text + "' is: " + hashValue;
    }
}