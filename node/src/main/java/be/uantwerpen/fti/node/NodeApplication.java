package be.uantwerpen.fti.node;

import org.springframework.beans.factory.annotation.Value;
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

    @Value("${node.api.port:8080}")
    private String nodePort;

    public NodeApplication(
            NodeState nodeState,
            DiscoveryService discoveryService,
            FileReplicationService replicationService
    ) {
        this.nodeState = nodeState;
        this.discoveryService = discoveryService;
        this.replicationService = replicationService;
    }

    public static void main(String[] args) {
        SpringApplication.run(NodeApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        String nodeName = System.getenv("NODE_NAME");

        if (nodeName == null || nodeName.trim().isEmpty()) {
            nodeName = "Node-" + UUID.randomUUID().toString().substring(0, 5);
        }

        String nodeIp = InetAddress.getLocalHost().getHostAddress();
        nodeState.init(nodeName, nodeIp);

        System.out.println("Starting node: " + nodeName + " at IP: " + nodeIp);

        discoveryService.listenForMulticast();

        int randomDelay = new java.util.Random().nextInt(4000);
        Thread.sleep(randomDelay);

        discoveryService.bootstrap();

        Thread.sleep(3000);

        replicationService.replicateExistingFiles();

        SyncAgent syncAgent = new SyncAgent(
                nodeState,
                replicationService,
                5000,
                nodePort
        );

        Thread syncAgentThread = new Thread(
                syncAgent,
                "SyncAgent-" + nodeState.getCurrentID()
        );

        syncAgentThread.setDaemon(true);
        syncAgentThread.start();

        System.out.println("[SYNC AGENT] Started for node " + nodeState.getCurrentID());
    }
}