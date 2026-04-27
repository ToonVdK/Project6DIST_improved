package be.uantwerpen.fti.namingserver;

import be.uantwerpen.fti.common.HashUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.TreeMap;

@Service
public class NamingServerService {

    // TreeMap keeps the node hashes sorted
    private final TreeMap<Integer, String> nodeMap = new TreeMap<>();
    private final String MAP_FILE = "nameserver_map.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    public void init() {
        File file = new File(MAP_FILE);
        if (file.exists()) {
            try {
                TreeMap<Integer, String> loadedMap = objectMapper.readValue(file, new TypeReference<TreeMap<Integer, String>>() {});
                nodeMap.putAll(loadedMap);
                System.out.println("Loaded " + nodeMap.size() + " nodes from disk.");
            } catch (IOException e) {
                System.err.println("Could not load map from disk: " + e.getMessage());
            }
        }
    }

    public void addNode(String nodeName, String ipAddress) {
        int hash = HashUtils.calculateHash(nodeName);
        nodeMap.put(hash, ipAddress);
        saveMapToDisk();
    }

    public void removeNode(String nodeName) {
        int hash = HashUtils.calculateHash(nodeName);
        nodeMap.remove(hash);
        saveMapToDisk();
    }

    public int getNumberOfNodes() {
        return nodeMap.size();
    }

    public String getIpByHash(int hash) {
        return nodeMap.get(hash);
    }

    public void removeNodeByHash(int hash) {
        nodeMap.remove(hash);
        saveMapToDisk();
    }

    public TreeMap<Integer, String> getNetworkTopology() {
        return nodeMap;
    }

    /**
     * Finds the previous and next neighbors of a given node hash.
     * Returns an array where index 0 is previous, index 1 is next.
     */
    public int[] getNeighbors(int failedNodeHash) {
        if (nodeMap.size() <= 1) {
            return null; // Not enough nodes to have neighbors
        }

        // Find previous node (wrap around to the highest hash if it's the lowest node)
        Integer previous = nodeMap.lowerKey(failedNodeHash);
        if (previous == null) {
            previous = nodeMap.lastKey();
        }

        // Find next node (wrap around to the lowest hash if it's the highest node)
        Integer next = nodeMap.higherKey(failedNodeHash);
        if (next == null) {
            next = nodeMap.firstKey();
        }

        return new int[]{previous, next};
    }

    /**
     * Determines the node ID (IP) where a file should be replicated.
     * Logic: node.hash < file.hash
     */
    public String getReplicationLocation(String filename) {
        if (nodeMap.isEmpty()) return null;

        int fileHash = HashUtils.calculateHash(filename);

        // Find the node with the hash strictly smaller than the file hash
        Integer targetNodeHash = nodeMap.lowerKey(fileHash);

        // Wrap around: If no smaller hash exists, it goes to the largest hash in the ring
        if (targetNodeHash == null) {
            targetNodeHash = nodeMap.lastKey();
        }

        return nodeMap.get(targetNodeHash);
    }

    private void saveMapToDisk() {
        try {
            objectMapper.writeValue(new File(MAP_FILE), nodeMap);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}