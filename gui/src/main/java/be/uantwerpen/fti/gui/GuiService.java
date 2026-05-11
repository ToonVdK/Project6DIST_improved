package be.uantwerpen.fti.gui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class GuiService {

    private final RestTemplate restTemplate;

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

    @Value("${gui.node-data-dir:./gui-data}")
    private String nodeDataDir;

    public GuiService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(2500);
        this.restTemplate = new RestTemplate(factory);
    }

    public DashboardView loadDashboard(Integer selectedId) {
        DashboardView view = new DashboardView();
        view.setNamingServerBaseUrl(namingServerBaseUrl);

        TreeMap<Integer, String> topology = getTopology();
        view.setTopology(topology);
        view.setNamingServerOnline(true);
        view.setDiscoveryEnabled(true);

        List<NodeView> nodes = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : topology.entrySet()) {
            nodes.add(loadNode(entry.getKey(), entry.getValue()));
        }

        view.setNodes(nodes);
        view.setNodeCount(nodes.size());

        if (!nodes.isEmpty()) {
            NodeView selected = null;

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

            view.setSelectedNode(selected);
        }

        view.setGlobalKnownFiles(collectGlobalKnownFiles(nodes));
        return view;
    }

    public String addNode(String nodeName) throws Exception {
        String safeName = sanitizeNodeName(nodeName);
        String containerName = nodeContainerPrefix + safeName.toLowerCase();

        Path nodeDir = Path.of(nodeDataDir, containerName).toAbsolutePath();
        Path localDir = nodeDir.resolve("local_files");
        Path replicatedDir = nodeDir.resolve("replicated_files");

        Files.createDirectories(localDir);
        Files.createDirectories(replicatedDir);

        List<String> command = List.of(
                dockerCommand,
                "run",
                "-d",
                "--rm",
                "--name", containerName,
                "--network", dockerNetwork,
                "-e", "NODE_NAME=" + safeName,
                "-v", localDir + ":/local_files",
                "-v", replicatedDir + ":/replicated_files",
                nodeImage
        );

        return runCommand(command);
    }

    public String removeNode(String nodeName) throws Exception {
        String safeName = sanitizeNodeName(nodeName);
        String containerName = nodeContainerPrefix + safeName.toLowerCase();

        List<String> command = List.of(
                dockerCommand,
                "stop",
                containerName
        );

        return runCommand(command);
    }

    @SuppressWarnings("unchecked")
    private TreeMap<Integer, String> getTopology() {
        try {
            String url = trimSlash(namingServerBaseUrl) + "/topology";
            Map<String, String> rawTopology = restTemplate.getForObject(url, Map.class);
            TreeMap<Integer, String> topology = new TreeMap<>();

            if (rawTopology == null) {
                return topology;
            }

            for (Map.Entry<String, String> entry : rawTopology.entrySet()) {
                topology.put(Integer.parseInt(entry.getKey()), entry.getValue());
            }

            return topology;
        } catch (Exception e) {
            return new TreeMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private NodeView loadNode(int id, String ip) {
        NodeView node = new NodeView();
        node.setId(id);
        node.setIp(ip);
        node.setOnline(false);
        node.setStatus("offline");
        node.setName("node-" + id);
        node.setPreviousID(-1);
        node.setNextID(-1);
        node.setLocalFiles(Collections.emptyList());
        node.setReplicatedFiles(Collections.emptyList());
        node.setKnownFiles(Collections.emptyMap());

        try {
            Map<String, Object> info = restTemplate.getForObject(nodeUrl(ip, "/api/node/info"), Map.class);

            if (info != null) {
                node.setOnline(true);
                node.setStatus("online");
                node.setName(asString(info.get("name"), node.getName()));
                node.setIp(asString(info.get("ip"), ip));
                node.setId(asInt(info.get("currentID"), id));
                node.setPreviousID(asInt(info.get("previousID"), -1));
                node.setNextID(asInt(info.get("nextID"), -1));

                Object knownFiles = info.get("knownFiles");
                if (knownFiles instanceof Map<?, ?> map) {
                    node.setKnownFiles((Map<String, Object>) map);
                }
            }

            Map<String, Object> physical = restTemplate.getForObject(nodeUrl(ip, "/api/node/files/physical"), Map.class);
            if (physical != null) {
                node.setLocalFiles(asStringList(physical.get("local")));
                node.setReplicatedFiles(asStringList(physical.get("replicated")));
            }

        } catch (Exception ignored) {
            node.setOnline(false);
            node.setStatus("offline");
        }

        return node;
    }

    private List<FileRow> collectGlobalKnownFiles(List<NodeView> nodes) {
        Map<String, FileRow> rows = new LinkedHashMap<>();

        for (NodeView node : nodes) {
            for (Map.Entry<String, Object> entry : node.getKnownFiles().entrySet()) {
                String filename = entry.getKey();

                if (!rows.containsKey(filename)) {
                    rows.put(filename, toFileRow(filename, entry.getValue(), node));
                }
            }
        }

        return new ArrayList<>(rows.values());
    }

    @SuppressWarnings("unchecked")
    private FileRow toFileRow(String filename, Object rawValue, NodeView discoveredOnNode) {
        FileRow row = new FileRow();
        row.setFilename(filename);
        row.setDiscoveredOn(discoveredOnNode.getName());
        row.setOwnerId(-1);
        row.setOwnerIp("-");
        row.setLastKnownLocationIp("-");
        row.setLocked(false);

        if (rawValue instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            row.setFilename(asString(map.get("filename"), filename));
            row.setOwnerId(asInt(map.get("ownerId"), -1));
            row.setOwnerIp(asString(map.get("ownerIp"), "-"));
            row.setLastKnownLocationIp(asString(map.get("lastKnownLocationIp"), "-"));
            row.setLocked(asBoolean(map.get("locked"), false));
        }

        return row;
    }

    private String runCommand(List<String> command) throws Exception {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed with exit code " + exitCode + ": " + output);
        }

        if (output == null || output.trim().isEmpty()) {
            return String.join(" ", command);
        }

        return output.trim();
    }

    private String sanitizeNodeName(String nodeName) {
        if (nodeName == null || nodeName.trim().isEmpty()) {
            throw new IllegalArgumentException("Node name cannot be empty");
        }

        String safeName = nodeName.trim().replaceAll("[^A-Za-z0-9_-]", "");
        if (safeName.isEmpty()) {
            throw new IllegalArgumentException("Node name must contain letters or numbers");
        }

        return safeName;
    }

    private String nodeUrl(String ip, String path) {
        return "http://" + ip + ":" + nodeApiPort + path;
    }

    private String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String asString(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private int asInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean asBoolean(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(item.toString());
            }
        }

        return result;
    }

    public static class DashboardView {
        private String namingServerBaseUrl;
        private boolean namingServerOnline;
        private boolean discoveryEnabled;
        private int nodeCount;
        private TreeMap<Integer, String> topology = new TreeMap<>();
        private List<NodeView> nodes = new ArrayList<>();
        private NodeView selectedNode;
        private List<FileRow> globalKnownFiles = new ArrayList<>();

        public String getNamingServerBaseUrl() { return namingServerBaseUrl; }
        public void setNamingServerBaseUrl(String namingServerBaseUrl) { this.namingServerBaseUrl = namingServerBaseUrl; }
        public boolean isNamingServerOnline() { return namingServerOnline; }
        public void setNamingServerOnline(boolean namingServerOnline) { this.namingServerOnline = namingServerOnline; }
        public boolean isDiscoveryEnabled() { return discoveryEnabled; }
        public void setDiscoveryEnabled(boolean discoveryEnabled) { this.discoveryEnabled = discoveryEnabled; }
        public int getNodeCount() { return nodeCount; }
        public void setNodeCount(int nodeCount) { this.nodeCount = nodeCount; }
        public TreeMap<Integer, String> getTopology() { return topology; }
        public void setTopology(TreeMap<Integer, String> topology) { this.topology = topology; }
        public List<NodeView> getNodes() { return nodes; }
        public void setNodes(List<NodeView> nodes) { this.nodes = nodes; }
        public NodeView getSelectedNode() { return selectedNode; }
        public void setSelectedNode(NodeView selectedNode) { this.selectedNode = selectedNode; }
        public List<FileRow> getGlobalKnownFiles() { return globalKnownFiles; }
        public void setGlobalKnownFiles(List<FileRow> globalKnownFiles) { this.globalKnownFiles = globalKnownFiles; }
    }

    public static class NodeView {
        private String name;
        private int id;
        private String ip;
        private String status;
        private boolean online;
        private int previousID;
        private int nextID;
        private List<String> localFiles = new ArrayList<>();
        private List<String> replicatedFiles = new ArrayList<>();
        private Map<String, Object> knownFiles = new HashMap<>();

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public boolean isOnline() { return online; }
        public void setOnline(boolean online) { this.online = online; }
        public int getPreviousID() { return previousID; }
        public void setPreviousID(int previousID) { this.previousID = previousID; }
        public int getNextID() { return nextID; }
        public void setNextID(int nextID) { this.nextID = nextID; }
        public List<String> getLocalFiles() { return localFiles; }
        public void setLocalFiles(List<String> localFiles) { this.localFiles = localFiles; }
        public List<String> getReplicatedFiles() { return replicatedFiles; }
        public void setReplicatedFiles(List<String> replicatedFiles) { this.replicatedFiles = replicatedFiles; }
        public Map<String, Object> getKnownFiles() { return knownFiles; }
        public void setKnownFiles(Map<String, Object> knownFiles) { this.knownFiles = knownFiles; }
    }

    public static class FileRow {
        private String filename;
        private int ownerId;
        private String ownerIp;
        private String lastKnownLocationIp;
        private boolean locked;
        private String discoveredOn;

        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public int getOwnerId() { return ownerId; }
        public void setOwnerId(int ownerId) { this.ownerId = ownerId; }
        public String getOwnerIp() { return ownerIp; }
        public void setOwnerIp(String ownerIp) { this.ownerIp = ownerIp; }
        public String getLastKnownLocationIp() { return lastKnownLocationIp; }
        public void setLastKnownLocationIp(String lastKnownLocationIp) { this.lastKnownLocationIp = lastKnownLocationIp; }
        public boolean isLocked() { return locked; }
        public void setLocked(boolean locked) { this.locked = locked; }
        public String getDiscoveredOn() { return discoveredOn; }
        public void setDiscoveredOn(String discoveredOn) { this.discoveredOn = discoveredOn; }
    }
}
