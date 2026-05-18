package be.uantwerpen.fti.gui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    @Value("${gui.nameserver-container-name:naming-server}")
    private String namingServerContainerName;

    @Value("${gui.nameserver-image:naming-server-img}")
    private String namingServerImage;

    public GuiService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1500);
        factory.setReadTimeout(2500);
        this.restTemplate = new RestTemplate(factory);
    }

    public DashboardView buildDashboard(Integer selectedId) {
        DashboardView view = new DashboardView();
        view.setNamingServerBaseUrl(namingServerBaseUrl);
        view.setDiscoveryEnabled(true);

        Map<Integer, String> topology = fetchTopology();

        view.setNamingServerOnline(topology != null);

        if (topology == null) {
            view.setNodes(new ArrayList<>());
            view.setNodeCount(0);
            view.setGlobalKnownFiles(new ArrayList<>());
            return view;
        }

        List<NodeView> nodes = new ArrayList<>();

        for (Map.Entry<Integer, String> entry : topology.entrySet()) {
            nodes.add(fetchNodeView(entry.getKey(), entry.getValue()));
        }

        nodes.sort(Comparator.comparingInt(NodeView::getId));
        view.setNodes(nodes);
        view.setNodeCount(nodes.size());

        NodeView selected = selectNode(nodes, selectedId);
        view.setSelectedNode(selected);

        if (selected != null) {
            view.setPreviousNode(findNodeById(nodes, selected.getPreviousID()));
            view.setNextNode(findNodeById(nodes, selected.getNextID()));
        }

        view.setGlobalKnownFiles(buildUniqueGlobalFileList(nodes));

        return view;
    }

    public void addNode(String nodeName) {
        String cleanNodeName = sanitizeNodeNameForDisplay(nodeName);
        String containerName = containerNameForNode(cleanNodeName);

        runCommand(List.of(
                dockerCommand,
                "run",
                "-d",
                "--rm",
                "--name", containerName,
                "--network", dockerNetwork,
                "-e", "NODE_NAME=" + cleanNodeName,
                nodeImage
        ));
    }

    public void shutdownNode(String nodeName) {
        runCommand(List.of(
                dockerCommand,
                "stop",
                containerNameForNode(nodeName)
        ));
    }

    public void killNode(String nodeName) {
        runCommand(List.of(
                dockerCommand,
                "kill",
                containerNameForNode(nodeName)
        ));
    }

    public void startNameserver() {
        try {
            runCommand(List.of(dockerCommand, "start", namingServerContainerName));
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
        runCommand(List.of(dockerCommand, "stop", namingServerContainerName));
    }

    public void uploadFileToNode(String nodeName, MultipartFile file) {
        if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new IllegalArgumentException("Please choose a file to upload.");
        }

        String filename = sanitizeFileName(file.getOriginalFilename());

        try {
            Path tempFile = Files.createTempFile("system-y-upload-", "-" + filename);
            try {
                file.transferTo(tempFile);

                String containerName = containerNameForNode(nodeName);

                runCommand(List.of(
                        dockerCommand,
                        "exec",
                        containerName,
                        "sh",
                        "-c",
                        "mkdir -p /local_files"
                ));

                runCommand(List.of(
                        dockerCommand,
                        "cp",
                        tempFile.toString(),
                        containerName + ":/local_files/" + filename
                ));

                runCommand(List.of(
                        dockerCommand,
                        "exec",
                        containerName,
                        "sh",
                        "-c",
                        "touch /local_files/" + filename
                ));
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    public void deleteFileOnNode(String nodeName, String fileName, String location) {
        String filename = sanitizeFileName(fileName);

        if (!"local".equalsIgnoreCase(location)) {
            throw new IllegalArgumentException("Replicated files are managed by System Y and cannot be deleted manually.");
        }

        /*
         * Only local/original files may be deleted from the GUI.
         * After deleting the physical local file, we explicitly remove the file metadata
         * and any remaining replica copies from every reachable node. This prevents
         * stale entries from staying visible in the global synchronized file list.
         */
        runCommand(List.of(
                dockerCommand,
                "exec",
                containerNameForNode(nodeName),
                "sh",
                "-c",
                "rm -f /local_files/" + filename
        ));

        removeFileEverywhere(filename);
    }

    private void removeFileEverywhere(String filename) {
        Map<Integer, String> topology = fetchTopology();

        if (topology == null || topology.isEmpty()) {
            return;
        }

        String encodedFilename = encodePathSegment(filename);

        for (String nodeIp : topology.values()) {
            try {
                restTemplate.delete(nodeUrl(nodeIp) + "/files/" + encodedFilename + "/metadata");
            } catch (Exception ignored) {
                // Keep cleaning the other nodes even if one node is temporarily unreachable.
            }

            try {
                restTemplate.delete(nodeUrl(nodeIp) + "/files/" + encodedFilename);
            } catch (Exception ignored) {
                // This endpoint returns 404 when the node does not have a replica. That is fine.
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<Integer, String> fetchTopology() {
        try {
            Map<String, String> raw = restTemplate.getForObject(namingServerBaseUrl + "/topology", Map.class);
            Map<Integer, String> topology = new LinkedHashMap<>();

            if (raw == null) {
                return topology;
            }

            raw.entrySet().stream()
                    .sorted(Comparator.comparingInt(e -> Integer.parseInt(e.getKey())))
                    .forEach(e -> topology.put(Integer.parseInt(e.getKey()), e.getValue()));

            return topology;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private NodeView fetchNodeView(int nodeId, String nodeIp) {
        NodeView node = new NodeView();
        node.setId(nodeId);
        node.setIp(nodeIp);
        node.setName("node-" + nodeId);
        node.setOnline(false);
        node.setStatus("offline");
        node.setPreviousID(-1);
        node.setNextID(-1);
        node.setLocalFiles(new ArrayList<>());
        node.setReplicatedFiles(new ArrayList<>());
        node.setKnownFiles(new ArrayList<>());

        try {
            Map<String, Object> info = restTemplate.getForObject(nodeUrl(nodeIp) + "/info", Map.class);

            if (info != null) {
                node.setOnline(true);
                node.setStatus("online");
                node.setName(asString(info.get("name"), node.getName()));
                node.setIp(asString(info.get("ip"), nodeIp));
                node.setId(asInt(info.get("currentID"), nodeId));
                node.setPreviousID(asInt(info.get("previousID"), -1));
                node.setNextID(asInt(info.get("nextID"), -1));

                Object knownFilesRaw = info.get("knownFiles");

                if (knownFilesRaw instanceof Map<?, ?> knownMap) {
                    node.setKnownFiles(parseKnownFiles(knownMap, node.getName()));
                }
            }

            Map<String, Object> physical = restTemplate.getForObject(nodeUrl(nodeIp) + "/files/physical", Map.class);

            if (physical != null) {
                node.setLocalFiles(toStringList(physical.get("local")));
                node.setReplicatedFiles(toStringList(physical.get("replicated")));
            }
        } catch (Exception e) {
            node.setStatus("offline");
            node.setOnline(false);
        }

        return node;
    }

    private List<FileRow> parseKnownFiles(Map<?, ?> knownMap, String discoveredOn) {
        List<FileRow> files = new ArrayList<>();

        for (Map.Entry<?, ?> entry : knownMap.entrySet()) {
            String fallbackFilename = Objects.toString(entry.getKey(), "-");
            Object rawValue = entry.getValue();

            FileRow row = new FileRow();
            row.setFilename(fallbackFilename);
            row.setDiscoveredOn(discoveredOn);
            row.setOwnerName("unknown");

            if (rawValue instanceof Map<?, ?> map) {
                row.setFilename(asString(map.get("filename"), fallbackFilename));
                row.setOwnerId(asInt(map.get("ownerId"), -1));
                row.setOwnerIp(asString(map.get("ownerIp"), "-"));
                row.setLastKnownLocationIp(asString(map.get("lastKnownLocationIp"), "-"));
                row.setLocked(asBoolean(map.get("locked"), false));
            }

            files.add(row);
        }

        files.sort(Comparator.comparing(FileRow::getFilename, Comparator.nullsLast(String::compareToIgnoreCase)));
        return files;
    }

    private List<FileRow> buildUniqueGlobalFileList(List<NodeView> nodes) {
        Map<Integer, NodeView> nodeById = new HashMap<>();

        for (NodeView node : nodes) {
            nodeById.put(node.getId(), node);
        }

        Map<String, FileRow> filesByFilename = new LinkedHashMap<>();

        for (NodeView node : nodes) {
            for (FileRow row : node.getKnownFiles()) {
                if (row.getFilename() == null || row.getFilename().isBlank()) {
                    continue;
                }

                FileRow existing = filesByFilename.get(row.getFilename());

                if (existing == null || (existing.getOwnerId() == -1 && row.getOwnerId() != -1)) {
                    FileRow copy = new FileRow(row);
                    NodeView ownerNode = nodeById.get(copy.getOwnerId());
                    copy.setOwnerName(ownerNode == null ? "unknown" : ownerNode.getName());
                    filesByFilename.put(copy.getFilename(), copy);
                }
            }
        }

        List<FileRow> result = new ArrayList<>(filesByFilename.values());
        result.sort(Comparator.comparing(FileRow::getFilename, Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    private String nodeUrl(String nodeIp) {
        return "http://" + nodeIp + ":" + nodeApiPort + "/api/node";
    }

    private NodeView selectNode(List<NodeView> nodes, Integer selectedId) {
        if (nodes == null || nodes.isEmpty()) {
            return null;
        }

        if (selectedId != null) {
            for (NodeView node : nodes) {
                if (node.getId() == selectedId) {
                    return node;
                }
            }
        }

        return nodes.get(0);
    }

    private NodeView findNodeById(List<NodeView> nodes, int id) {
        if (nodes == null || id == -1) {
            return null;
        }

        for (NodeView node : nodes) {
            if (node.getId() == id) {
                return node;
            }
        }

        return null;
    }

    private List<String> toStringList(Object value) {
        List<String> result = new ArrayList<>();

        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
        }

        result.sort(String::compareToIgnoreCase);
        return result;
    }

    private String sanitizeNodeNameForDisplay(String nodeName) {
        if (nodeName == null || nodeName.trim().isEmpty()) {
            throw new IllegalArgumentException("Node name is required.");
        }

        return nodeName.trim();
    }

    private String containerNameForNode(String nodeName) {
        String safe = nodeName == null ? "" : nodeName.trim().toLowerCase().replaceAll("[^a-z0-9._-]", "-");

        if (safe.isBlank()) {
            throw new IllegalArgumentException("Invalid node name.");
        }

        if (safe.startsWith(nodeContainerPrefix)) {
            return safe;
        }

        return nodeContainerPrefix + safe;
    }

    private String sanitizeFileName(String fileName) {
        String safe = fileName == null ? "" : fileName.trim().replaceAll("[^a-zA-Z0-9._-]", "_");

        while (safe.startsWith(".")) {
            safe = safe.substring(1);
        }

        if (safe.isBlank()) {
            throw new IllegalArgumentException("Invalid file name.");
        }

        return safe;
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String folderForLocation(String location) {
        if ("replicated".equalsIgnoreCase(location)) {
            return "/replicated_files";
        }

        if ("local".equalsIgnoreCase(location)) {
            return "/local_files";
        }

        throw new IllegalArgumentException("Invalid file location: " + location);
    }

    private String asString(Object value, String defaultValue) {
        return value == null ? defaultValue : value.toString();
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

    private void runCommand(List<String> command) {
        CommandResult result = runCommandForResult(command);

        if (result.exitCode() != 0) {
            throw new RuntimeException("Command failed with exit code " + result.exitCode() + ": " + result.output());
        }
    }

    private CommandResult runCommandForResult(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            process.getInputStream().transferTo(outputStream);
            int exitCode = process.waitFor();

            return new CommandResult(exitCode, outputStream.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private record CommandResult(int exitCode, String output) {
    }

    public static class DashboardView {
        private boolean namingServerOnline;
        private String namingServerBaseUrl;
        private boolean discoveryEnabled;
        private int nodeCount;
        private List<NodeView> nodes = new ArrayList<>();
        private NodeView selectedNode;
        private NodeView previousNode;
        private NodeView nextNode;
        private List<FileRow> globalKnownFiles = new ArrayList<>();

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

        public NodeView getPreviousNode() {
            return previousNode;
        }

        public void setPreviousNode(NodeView previousNode) {
            this.previousNode = previousNode;
        }

        public NodeView getNextNode() {
            return nextNode;
        }

        public void setNextNode(NodeView nextNode) {
            this.nextNode = nextNode;
        }

        public List<FileRow> getGlobalKnownFiles() {
            return globalKnownFiles;
        }

        public void setGlobalKnownFiles(List<FileRow> globalKnownFiles) {
            this.globalKnownFiles = globalKnownFiles;
        }
    }

    public static class NodeView {
        private String name;
        private int id;
        private String ip;
        private boolean online;
        private String status;
        private int previousID;
        private int nextID;
        private List<String> localFiles = new ArrayList<>();
        private List<String> replicatedFiles = new ArrayList<>();
        private List<FileRow> knownFiles = new ArrayList<>();

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
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

        public List<FileRow> getKnownFiles() {
            return knownFiles;
        }

        public void setKnownFiles(List<FileRow> knownFiles) {
            this.knownFiles = knownFiles;
        }
    }

    public static class FileRow {
        private String filename;
        private int ownerId;
        private String ownerName;
        private String ownerIp;
        private String lastKnownLocationIp;
        private boolean locked;
        private String discoveredOn;

        public FileRow() {
        }

        public FileRow(FileRow other) {
            this.filename = other.filename;
            this.ownerId = other.ownerId;
            this.ownerName = other.ownerName;
            this.ownerIp = other.ownerIp;
            this.lastKnownLocationIp = other.lastKnownLocationIp;
            this.locked = other.locked;
            this.discoveredOn = other.discoveredOn;
        }

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

        public String getOwnerName() {
            return ownerName;
        }

        public void setOwnerName(String ownerName) {
            this.ownerName = ownerName;
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
