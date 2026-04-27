package be.uantwerpen.fti.namingserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

@Component
public class NamingServerDiscoveryListener {

    private final String MULTICAST_GROUP;
    private final NamingServerService namingServerService;
    private final int PORT;
    private final RestTemplate restTemplate = new RestTemplate();
    @Value("${node.api.port}") String nodePort;

    public NamingServerDiscoveryListener(NamingServerService namingServerService,
                                         @Value("${multicast.group}") String multicastGroup,
                                         @Value("${multicast.port}") int multicastPort) {
        this.namingServerService = namingServerService;
        this.MULTICAST_GROUP = multicastGroup;
        this.PORT = multicastPort;
    }

    @PostConstruct
    public void startListening() {
        new Thread(() -> {
            try (MulticastSocket socket = new MulticastSocket(PORT)) {
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                socket.joinGroup(group);

                while (true) {
                    byte[] buffer = new byte[256];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength());
                    String[] parts = message.split(";");

                    if (parts.length == 3 && parts[0].equals("DISCOVER")) {
                        String nodeName = parts[1];
                        String nodeIp = parts[2]; // Or packet.getAddress().getHostAddress()

                        // 1. Add to map
                        namingServerService.addNode(nodeName, nodeIp);

                        // 2. Get network size
                        int networkSize = namingServerService.getNumberOfNodes();

                        // 3. Send Unicast back to the node with networkSize
                        try {
                            restTemplate.postForObject("http://" + nodeIp + ":"+ nodePort + "/api/node/networkSize/" + networkSize, null, String.class);
                            System.out.println("Registered node " + nodeName + ". Total nodes: " + networkSize);
                        } catch (Exception e) {
                            System.err.println("Failed to send network size to node: " + e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}