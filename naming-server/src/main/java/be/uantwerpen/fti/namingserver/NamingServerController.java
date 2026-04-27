package be.uantwerpen.fti.namingserver;

import org.springframework.web.bind.annotation.*;
import java.util.TreeMap;

@RestController
@RequestMapping("/api")
public class NamingServerController {

    private final NamingServerService namingServerService;

    public NamingServerController(NamingServerService namingServerService) {
        this.namingServerService = namingServerService;
    }

    @GetMapping("/nodes/files/replicate/{filename}")
    public String getReplicationLocation(@PathVariable String filename) {
        return namingServerService.getReplicationLocation(filename);
    }

    @GetMapping("/topology")
    public TreeMap<Integer, String> getTopology() {
        return namingServerService.getNetworkTopology();
    }

    // Endpoint to remove a node
    @DeleteMapping("/nodes/{nodeName}")
    public String removeNode(@PathVariable String nodeName) {
        namingServerService.removeNode(nodeName);
        return "Removed node: " + nodeName;
    }

    // Endpoint to get an IP address by a node's Hash ID
    @GetMapping("/nodes/ip/{hashId}")
    public String getNodeIpByHash(@PathVariable int hashId) {
        return namingServerService.getIpByHash(hashId);
    }

    @DeleteMapping("/nodes/hash/{hash}")
    public String removeNodeByHash(@PathVariable int hash) {
        namingServerService.removeNodeByHash(hash);
        return "Removed dead node with hash: " + hash;
    }

    @GetMapping("/nodes/neighbors/{hash}")
    public int[] getNeighbors(@PathVariable int hash) {
        return namingServerService.getNeighbors(hash);
    }
}