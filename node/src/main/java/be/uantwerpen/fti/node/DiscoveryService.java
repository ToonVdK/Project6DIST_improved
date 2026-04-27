package be.uantwerpen.fti.node;

import be.uantwerpen.fti.common.HashUtils;
import be.uantwerpen.fti.node.NodeState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

@Service
public class DiscoveryService {

    private final NodeState nodeState;
    private final String MULTICAST_GROUP;
    private final int PORT;
    private final RestTemplate restTemplate = new RestTemplate();
    @Value("${node.api.port}") String nodePort;

    public DiscoveryService(NodeState nodeState,
                            @Value("${multicast.group}") String multicastGroup,
                            @Value("${multicast.port}") int multicastPort) {
        this.nodeState = nodeState;
        this.MULTICAST_GROUP = multicastGroup;
        this.PORT = multicastPort;
    }

    /**
     * Sends multicast message to existing nodes and Naming server.
     */
    public void bootstrap() {
        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
            String message = "DISCOVER;" + nodeState.getName() + ";" + nodeState.getIpAddress();
            byte[] buffer = message.getBytes();

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, group, PORT);
            socket.send(packet);

            // Here you would start a thread to listen for the Unicast response
            // containing previousID, nextID, and number of existing nodes.
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Listens for multicast messages from other new nodes.
     */
    public void listenForMulticast() {
        new Thread(() -> {
            try (MulticastSocket socket = new MulticastSocket(PORT)) {
                InetAddress group = InetAddress.getByName(MULTICAST_GROUP);
                socket.joinGroup(group);

                while (true) {
                    byte[] buffer = new byte[256];
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength());
                    handleIncomingDiscovery(message, packet.getAddress().getHostAddress());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void handleIncomingDiscovery(String message, String senderIp) {
        String[] parts = message.split(";");
        if (parts.length == 3 && parts[0].equals("DISCOVER")) {
            String newNodeName = parts[1];
            int newHash = HashUtils.calculateHash(newNodeName);
            int currentID = nodeState.getCurrentID();

            // Ignore our own multicast messages
            if (currentID == newHash) return;

            boolean isNext = false;
            boolean isPrevious = false;

            // Logic from PDF: Update nextID if new node falls between current and next
            // (Including ring wrap-around logic where nextID < currentID)
            if ((currentID < newHash && newHash < nodeState.getNextID()) ||
                    (nodeState.getNextID() <= currentID && (newHash > currentID || newHash < nodeState.getNextID()))) {
                nodeState.setNextID(newHash);
                isNext = true;
            }

            // Logic from PDF: Update previousID if new node falls between previous and current
            if ((nodeState.getPreviousID() < newHash && newHash < currentID) ||
                    (currentID <= nodeState.getPreviousID() && (newHash < currentID || newHash > nodeState.getPreviousID()))) {
                nodeState.setPreviousID(newHash);
                isPrevious = true;
            }

            // If we updated our state, we must tell the new node about it via Unicast
            if (isNext || isPrevious) {
                sendUnicastResponse(senderIp, currentID, isNext, isPrevious);
            }
        }
    }

    private void sendUnicastResponse(String targetIp, int currentID, boolean isNext, boolean isPrevious) {
        String baseUrl = "http://" + targetIp + ":" + nodePort + "/api/node";

        try {
            if (isNext) {
                // If the new node is our next node, it means WE are its previous node.
                restTemplate.postForObject(baseUrl + "/previous/" + currentID, null, String.class);
            }
            if (isPrevious) {
                // If the new node is our previous node, it means WE are its next node.
                restTemplate.postForObject(baseUrl + "/next/" + currentID, null, String.class);
            }
            System.out.println("Successfully sent topology update to " + targetIp);
        } catch (Exception e) {
            System.err.println("Failed to send unicast update to " + targetIp + ": " + e.getMessage());
        }
    }
}
