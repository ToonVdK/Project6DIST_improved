package be.uantwerpen.fti.node;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.util.UUID;

@SpringBootApplication
@EnableScheduling
public class NodeApplication implements CommandLineRunner {

    private final NodeState nodeState;
    private final DiscoveryService discoveryService;
    private final FileReplicationService replicationService;

    public NodeApplication(NodeState nodeState,
                           DiscoveryService discoveryService,
                           FileReplicationService replicationService) {
        this.nodeState = nodeState;
        this.discoveryService = discoveryService;
        this.replicationService = replicationService;
    }

    public static void main(String[] args) {
        SpringApplication.run(NodeApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // 1. Try to get the name from a Docker Environment Variable
        String nodeName = System.getenv("NODE_NAME");

        // 2. If no name was provided, generate a random one
        if (nodeName == null || nodeName.trim().isEmpty()) {
            nodeName = "Node-" + UUID.randomUUID().toString().substring(0, 5);
        }

        String nodeIp = InetAddress.getLocalHost().getHostAddress();

        nodeState.init(nodeName, nodeIp);
        System.out.println("Starting node: " + nodeName + " at IP: " + nodeIp);

        discoveryService.listenForMulticast();

        // Keep the random delay to prevent a stampede
        int randomDelay = new java.util.Random().nextInt(4000);
        Thread.sleep(randomDelay);

        discoveryService.bootstrap();

        // Wait a few seconds to ensure the network ring has stabilized,
        // then start replicating our local files!
        Thread.sleep(3000);
        replicationService.replicateExistingFiles();
    }
}