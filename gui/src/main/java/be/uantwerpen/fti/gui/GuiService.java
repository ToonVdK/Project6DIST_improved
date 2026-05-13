package be.uantwerpen.fti.gui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GuiService {

    @Value("${gui.naming-server-base-url:http://localhost:8080/api}")
    private String namingServerBaseUrl;

    @Value("${gui.node-api-port:8080}")
    private String nodeApiPort;

    @Value("${gui.docker-command:docker}")
    private String dockerCommand;

    @Value("${gui.docker-network:system-y-net}")
    private String dockerNetwork;

    @Value("${gui.node-image:node-img}")
    private String nodeImage;

    @Value("${gui.node-container-prefix:node-}")
    private String nodeContainerPrefix;

    @Value("${gui.nameserver-container-name:naming-server}")
    private String namingServerContainerName;

    @Value("${gui.nameserver-image:naming-server-img}")
    private String namingServerImage;

    private final RestTemplate restTemplate;

    public GuiService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
    }

    public DashboardView buildDashboardView(Integer selectedId) {
        DashboardView view = new DashboardView();
        view.setNamingServerBaseUrl(namingServerBaseUrl);
        view.setDiscoveryEnabled(true);

        Map<Integer, String> topology = fetchTopology();
        view.setNamingServerOnline(topology != null);

        List<NodeView> nodes = new ArrayList<>();

        if (topology != null) {
            for (Map.Entry<Integer, String> entry : topology.entrySet()) {
                NodeView node = fetchNode(entry.getKey(), entry.getValue());
                nodes.add(node);
            }
        }

        nodes.sort(Comparator.comparingInt(NodeView::getId));
        view.setNodes(nodes);
        view.setNodeCount(nodes.size());

        NodeView selected = null;

        if (!nodes.isEmpty()) {
            if (selectedId != null) {
                for (NodeView node : nodes) {
                    if (node.getId() == selectedId) {
                        selected = node;
                        break;
                    }
                }
            }

            if (selected == null) {
                selected = nodes.get(0);
            }
        }

        view.setSelectedNode(selected);
        view.setGlobalKnownFiles(collectGlobalKnownFiles(nodes));

        return view;
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, String> fetchTopology() {
        try {
            Map<String, String> raw = restTemplate.getForObject(
                    namingServerBaseUrl + "/topology",
                    Map.class
            );

            if (raw == null) {
                return new LinkedHashMap<>();
            }

            Map<Integer, String> result = new LinkedHashMap<>();

            for (Map.Entry<String, String> entry : raw.entrySet()) {
                result.put(Integer.parseInt(entry.getKey()), entry.getValue());
            }

            return result;

        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private NodeView fetchNode(int id, String ip) {
        NodeView node = new NodeView();
        node.setId(id);
        node.setIp(ip);
        node.setName("node-" + id);
        node.setStatus("offline");
        node.setOnline(false);

        try {
            String nodeBaseUrl = "http://" + ip + ":" + nodeApiPort + "/api/node";

            Map<String, Object> info = restTemplate.getForObject(
                    nodeBaseUrl + "/info",
                    Map.class
            );

            if (info != null) {
                node.setName(asString(info.get("name"), node.getName()));
                node.setIp(asString(info.get("ip"), ip));
                node.setId(asInt(info.get("currentID"), id));
                node.setPreviousID(asInt(info.get("previousID"), -1));
                node.setNextID(asInt(info.get("nextID"), -1));
                node.setStatus("online");
                node.setOnline(true);

                Object knownFiles = info.get("knownFiles");
                if (knownFiles instanceof Map<?, ?> knownMap) {
                    Map<String, Object> normalized = new LinkedHashMap<>();

                    for (Map.Entry<?, ?> entry : knownMap.entrySet()) {
                        normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                    }

                    node.setKnownFiles(normalized);
                }
            }

            try {
                Map<String, Object> physical = restTemplate.getForObject(
                        nodeBaseUrl + "/files/physical",
                        Map.class
                );

                if (physical != null) {
                    node.setLocalFiles(toStringList(physical.get("local")));
                    node.setReplicatedFiles(toStringList(physical.get("replicated")));
                }

            } catch (Exception ignored) {
                // Older node image without /files/physical endpoint.
            }

        } catch (Exception e) {
            node.setOnline(false);
            node.setStatus("unreachable");
        }

        return node;
    }

    @SuppressWarnings("unchecked")
    private List<KnownFileView> collectGlobalKnownFiles(List<NodeView> nodes) {
        Map<String, KnownFileView> files = new LinkedHashMap<>();

        for (NodeView node : nodes) {
            Map<String, Object> knownFiles = node.getKnownFiles();

            for (Map.Entry<String, Object> entry : knownFiles.entrySet()) {
                String filename = entry.getKey();

                if (files.containsKey(filename)) {
                    continue;
                }

                KnownFileView file = new KnownFileView();
                file.setFilename(filename);
                file.setDiscoveredOn(node.getName());

                Object rawInfo = entry.getValue();

                if (rawInfo instanceof Map<?, ?> rawMap) {
                    Map<String, Object> info = (Map<String, Object>) rawMap;
                    file.setOwnerId(asInt(info.get("ownerId"), -1));
                    file.setOwnerIp(asString(info.get("ownerIp"), "-"));
                    file.setLastKnownLocationIp(asString(info.get("lastKnownLocationIp"), "-"));
                    file.setLocked(asBoolean(info.get("locked"), false));
                }

                files.put(filename, file);
            }
        }

        List<KnownFileView> result = new ArrayList<>(files.values());
        result.sort(Comparator.comparing(KnownFileView::getFilename));
        return result;
    }

    public void addNode(String nodeName) {
        String cleanedNodeName = sanitizeName(nodeName);
        String containerName = nodeContainerPrefix + cleanedNodeName;

        ensureDockerNetworkExists();

        runCommand(List.of(
                dockerCommand,
                "run",
                "-d",
                "--rm",
                "--name", containerName,
                "--network", dockerNetwork,
                "-e", "NODE_NAME=" + nodeName.trim(),
                nodeImage
        ));
    }

    public void removeNode(String nodeName) {
        String cleanedNodeName = sanitizeName(nodeName);
        String containerName = nodeContainerPrefix + cleanedNodeName;

        runCommand(List.of(
                dockerCommand,
                "stop",
                containerName
        ));
    }

    public void startNameserver() {
        ensureDockerNetworkExists();

        try {
            runCommand(List.of(
                    dockerCommand,
                    "start",
                    namingServerContainerName
            ));
            return;
        } catch (Exception ignored) {
            // If the container does not exist, create a new one below.
        }

        runCommand(List.of(
                dockerCommand,
                "run",
                "-d",
                "--rm",
                "--name", namingServerContainerName,
                "--network", dockerNetwork,
                "-p", "8080:8080",
                namingServerImage
        ));
    }

    public void stopNameserver() {
        runCommand(List.of(
                dockerCommand,
                "stop",
                namingServerContainerName
        ));
    }

    public void createFileOnNode(String nodeName, String fileName, String content) {
        String cleanedNodeName = sanitizeName(nodeName);
        String containerName = nodeContainerPrefix + cleanedNodeName;

        String safeFileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");

        if (safeFileName.isBlank()) {
            throw new IllegalArgumentException("Invalid file name.");
        }

        runCommandWithInput(
                List.of(
                        dockerCommand,
                        "exec",
                        "-i",
                        containerName,
                        "sh",
                        "-c",
                        "mkdir -p /local_files && cat > /local_files/" + safeFileName
                ),
                content == null ? "" : content
        );
    }

    private void ensureDockerNetworkExists() {
        try {
            runCommand(List.of(
                    dockerCommand,
                    "network",
                    "inspect",
                    dockerNetwork
            ));
        } catch (Exception e) {
            runCommand(List.of(
                    dockerCommand,
                    "network",
                    "create",
                    dockerNetwork
            ));
        }
    }

    private void runCommand(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Command failed with exit code " + exitCode + ": " + output);
            }

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void runCommandWithInput(List<String> command, String input) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (OutputStream outputStream = process.getOutputStream()) {
                outputStream.write(input.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Command failed with exit code " + exitCode + ": " + output);
            }

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private String sanitizeName(String nodeName) {
        if (nodeName == null || nodeName.trim().isEmpty()) {
            throw new IllegalArgumentException("Node name may not be empty.");
        }

        return nodeName
                .trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9._-]", "-");
    }

    private List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();

        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }

        return result;
    }

    private String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Number number) {
            return number.intValue();
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean bool) {
            return bool;
        }

        return Boolean.parseBoolean(value.toString());
    }

    public static class DashboardView {
        private boolean namingServerOnline;
        private String namingServerBaseUrl;
        private boolean discoveryEnabled;
        private int nodeCount;
        private List<NodeView> nodes = new ArrayList<>();
        private NodeView selectedNode;
        private List<KnownFileView> globalKnownFiles = new ArrayList<>();

        public boolean isNamingServerOnline() {
            return namingServerOnline;
        }

        public void setNamingServerOnline(boolean namingServerOnline) {
            this.namingServerOnline = namingServerOnline;
        }

        public String getNamingServerBaseUrl() {
            return namingServerBaseUrl;
        }

        public void setNamingServerBaseUrl(String namingServerBaseUrl) {
            this.namingServerBaseUrl = namingServerBaseUrl;
        }

        public boolean isDiscoveryEnabled() {
            return discoveryEnabled;
        }

        public void setDiscoveryEnabled(boolean discoveryEnabled) {
            this.discoveryEnabled = discoveryEnabled;
        }

        public int getNodeCount() {
            return nodeCount;
        }

        public void setNodeCount(int nodeCount) {
            this.nodeCount = nodeCount;
        }

        public List<NodeView> getNodes() {
            return nodes;
        }

        public void setNodes(List<NodeView> nodes) {
            this.nodes = nodes;
        }

        public NodeView getSelectedNode() {
            return selectedNode;
        }

        public void setSelectedNode(NodeView selectedNode) {
            this.selectedNode = selectedNode;
        }

        public List<KnownFileView> getGlobalKnownFiles() {
            return globalKnownFiles;
        }

        public void setGlobalKnownFiles(List<KnownFileView> globalKnownFiles) {
            this.globalKnownFiles = globalKnownFiles;
        }
    }

    public static class NodeView {
        private int id;
        private String name;
        private String ip;
        private int previousID = -1;
        private int nextID = -1;
        private boolean online;
        private String status = "unknown";
        private List<String> localFiles = new ArrayList<>();
        private List<String> replicatedFiles = new ArrayList<>();
        private Map<String, Object> knownFiles = new LinkedHashMap<>();

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public int getPreviousID() {
            return previousID;
        }

        public void setPreviousID(int previousID) {
            this.previousID = previousID;
        }

        public int getNextID() {
            return nextID;
        }

        public void setNextID(int nextID) {
            this.nextID = nextID;
        }

        public boolean isOnline() {
            return online;
        }

        public void setOnline(boolean online) {
            this.online = online;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public List<String> getLocalFiles() {
            return localFiles;
        }

        public void setLocalFiles(List<String> localFiles) {
            this.localFiles = localFiles;
        }

        public List<String> getReplicatedFiles() {
            return replicatedFiles;
        }

        public void setReplicatedFiles(List<String> replicatedFiles) {
            this.replicatedFiles = replicatedFiles;
        }

        public Map<String, Object> getKnownFiles() {
            return knownFiles;
        }

        public void setKnownFiles(Map<String, Object> knownFiles) {
            this.knownFiles = knownFiles;
        }
    }

    public static class KnownFileView {
        private String filename;
        private int ownerId;
        private String ownerIp;
        private String lastKnownLocationIp;
        private boolean locked;
        private String discoveredOn;

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public int getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(int ownerId) {
            this.ownerId = ownerId;
        }

        public String getOwnerIp() {
            return ownerIp;
        }

        public void setOwnerIp(String ownerIp) {
            this.ownerIp = ownerIp;
        }

        public String getLastKnownLocationIp() {
            return lastKnownLocationIp;
        }

        public void setLastKnownLocationIp(String lastKnownLocationIp) {
            this.lastKnownLocationIp = lastKnownLocationIp;
        }

        public boolean isLocked() {
            return locked;
        }

        public void setLocked(boolean locked) {
            this.locked = locked;
        }

        public String getDiscoveredOn() {
            return discoveredOn;
        }

        public void setDiscoveredOn(String discoveredOn) {
            this.discoveredOn = discoveredOn;
        }
    }
}
