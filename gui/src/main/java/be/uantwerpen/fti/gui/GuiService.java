package be.uantwerpen.fti.gui;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    public DashboardView buildDashboard(Integer selectedId, String editNode, String editLocation, String editFile) {
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
        List<FileRow> globalFiles = new ArrayList<>();
        Set<String> globalFileKeys = new HashSet<>();

        for (Map.Entry<Integer, String> entry : topology.entrySet()) {
            int nodeId = entry.getKey();
            String nodeIp = entry.getValue();

            NodeView node = fetchNodeView(nodeId, nodeIp);
            nodes.add(node);

            for (FileRow row : node.getKnownFiles()) {
                String key = row.getFilename() + "|" + row.getOwnerId() + "|" + row.getDiscoveredOn();
                if (globalFileKeys.add(key)) {
                    globalFiles.add(row);
                }
            }
        }

        nodes.sort(Comparator.comparingInt(NodeView::getId));
        globalFiles.sort(Comparator.comparing(FileRow::getFilename, Comparator.nullsLast(String::compareToIgnoreCase)));

        view.setNodes(nodes);
        view.setNodeCount(nodes.size());
        view.setGlobalKnownFiles(globalFiles);

        NodeView selected = selectNode(nodes, selectedId);
        view.setSelectedNode(selected);

        if (selected != null) {
            view.setPreviousNode(findNodeById(nodes, selected.getPreviousID()));
            view.setNextNode(findNodeById(nodes, selected.getNextID()));
        }

        if (editNode != null && editLocation != null && editFile != null) {
            try {
                EditFileView editFileView = new EditFileView();
                editFileView.setNodeName(editNode);
                editFileView.setLocation(editLocation);
                editFileView.setFileName(editFile);
                editFileView.setContent(readTextFileFromNode(editNode, editFile, editLocation));

                Integer editSelectedId = selectedId;
                if (editSelectedId == null && selected != null) {
                    editSelectedId = selected.getId();
                }
                editFileView.setSelectedId(editSelectedId);

                view.setEditFile(editFileView);
            } catch (Exception e) {
                view.setEditFileError("Could not open text file: " + e.getMessage());
            }
        }

        return view;
    }

    public void addNode(String nodeName) {
        String cleanNodeName = sanitizeNodeNameForDisplay(nodeName);
        String containerName = containerNameForNode(cleanNodeName);

        runCommand(List.of(
                dockerCommand, "run", "-d", "--rm",
                "--name", containerName,
                "--network", dockerNetwork,
                "-e", "NODE_NAME=" + cleanNodeName,
                nodeImage
        ));
    }

    public void shutdownNode(String nodeName) {
        runCommand(List.of(
                dockerCommand, "stop", containerNameForNode(nodeName)
        ));
    }

    public void killNode(String nodeName) {
        runCommand(List.of(
                dockerCommand, "kill", containerNameForNode(nodeName)
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
                dockerCommand, "run", "-d", "--rm",
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
        if (file == null || file.getOriginalFilename() == null || file.getOriginalFilename().trim().isEmpty()) {
            throw new IllegalArgumentException("Please choose a file to upload.");
        }

        try {
            uploadFileToNode(nodeName, file.getOriginalFilename(), file.getBytes());
        } catch (Exception e) {
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);
        }
    }

    public void uploadFileToNode(String nodeName, String originalFilename, byte[] bytes) {
        String filename = sanitizeFileName(originalFilename);
        if (filename.isBlank()) {
            throw new IllegalArgumentException("Invalid file name.");
        }

        String containerName = containerNameForNode(nodeName);
        Path temporaryDirectory = null;
        Path temporaryFile = null;

        try {
            /*
             * Robust upload flow:
             * 1. Store the browser upload as a temporary file inside the GUI container.
             * 2. docker cp that temporary file into the selected node's /local_files.
             *
             * This works for text files, binary files and zero-byte files.
             */
            temporaryDirectory = Files.createTempDirectory("system-y-upload-");
            temporaryFile = temporaryDirectory.resolve(filename);
            Files.write(temporaryFile, bytes == null ? new byte[0] : bytes);

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
                    temporaryFile.toAbsolutePath().toString(),
                    containerName + ":/local_files/" + filename
            ));

            /*
             * Touch the file after docker cp so the node's DirectoryWatcherService
             * sees a create/modify event and triggers replication.
             */
            runCommand(List.of(
                    dockerCommand,
                    "exec",
                    containerName,
                    "sh",
                    "-c",
                    "touch /local_files/" + filename + " && test -f /local_files/" + filename
            ));

        } catch (Exception e) {
            throw new RuntimeException("Upload failed: " + e.getMessage(), e);

        } finally {
            try {
                if (temporaryFile != null) {
                    Files.deleteIfExists(temporaryFile);
                }
                if (temporaryDirectory != null) {
                    Files.deleteIfExists(temporaryDirectory);
                }
            } catch (Exception ignored) {
                // Cleanup failure should not hide the real upload result.
            }
        }
    }

    public void deleteFileOnNode(String nodeName, String fileName, String location) {
        String filename = sanitizeFileName(fileName);
        String folder = folderForLocation(location);

        runCommand(List.of(
                dockerCommand,
                "exec",
                containerNameForNode(nodeName),
                "sh",
                "-c",
                "rm -f " + folder + "/" + filename
        ));
    }

    public String readTextFileFromNode(String nodeName, String fileName, String location) {
        String filename = sanitizeFileName(fileName);
        String folder = folderForLocation(location);

        CommandResult result = runCommandForResult(List.of(
                dockerCommand,
                "exec",
                containerNameForNode(nodeName),
                "sh",
                "-c",
                "cat " + folder + "/" + filename
        ));

        return result.output();
    }

    public void updateTextFileOnNode(String nodeName, String fileName, String location, String content) {
        String filename = sanitizeFileName(fileName);
        writeBytesToNodeFile(nodeName, filename, location, content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytesToNodeFile(String nodeName, String filename, String location, byte[] bytes) {
        String folder = folderForLocation(location);

        runCommandWithInput(
                List.of(
                        dockerCommand,
                        "exec",
                        "-i",
                        containerNameForNode(nodeName),
                        "sh",
                        "-c",
                        "mkdir -p " + folder + " && cat > " + folder + "/" + filename
                ),
                bytes == null ? new byte[0] : bytes
        );
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

    private void runCommandWithInput(List<String> command, byte[] input) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            try (OutputStream outputStream = process.getOutputStream()) {
                outputStream.write(input);
                outputStream.flush();
            }

            ByteArrayOutputStream processOutput = new ByteArrayOutputStream();
            process.getInputStream().transferTo(processOutput);
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Command failed with exit code " + exitCode + ": " + processOutput.toString(StandardCharsets.UTF_8));
            }
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
        private EditFileView editFile;
        private String editFileError;

        public boolean isNamingServerOnline() { return namingServerOnline; }
        public void setNamingServerOnline(boolean namingServerOnline) { this.namingServerOnline = namingServerOnline; }
        public String getNamingServerBaseUrl() { return namingServerBaseUrl; }
        public void setNamingServerBaseUrl(String namingServerBaseUrl) { this.namingServerBaseUrl = namingServerBaseUrl; }
        public boolean isDiscoveryEnabled() { return discoveryEnabled; }
        public void setDiscoveryEnabled(boolean discoveryEnabled) { this.discoveryEnabled = discoveryEnabled; }
        public int getNodeCount() { return nodeCount; }
        public void setNodeCount(int nodeCount) { this.nodeCount = nodeCount; }
        public List<NodeView> getNodes() { return nodes; }
        public void setNodes(List<NodeView> nodes) { this.nodes = nodes; }
        public NodeView getSelectedNode() { return selectedNode; }
        public void setSelectedNode(NodeView selectedNode) { this.selectedNode = selectedNode; }
        public NodeView getPreviousNode() { return previousNode; }
        public void setPreviousNode(NodeView previousNode) { this.previousNode = previousNode; }
        public NodeView getNextNode() { return nextNode; }
        public void setNextNode(NodeView nextNode) { this.nextNode = nextNode; }
        public List<FileRow> getGlobalKnownFiles() { return globalKnownFiles; }
        public void setGlobalKnownFiles(List<FileRow> globalKnownFiles) { this.globalKnownFiles = globalKnownFiles; }
        public EditFileView getEditFile() { return editFile; }
        public void setEditFile(EditFileView editFile) { this.editFile = editFile; }
        public String getEditFileError() { return editFileError; }
        public void setEditFileError(String editFileError) { this.editFileError = editFileError; }
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

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }
        public boolean isOnline() { return online; }
        public void setOnline(boolean online) { this.online = online; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getPreviousID() { return previousID; }
        public void setPreviousID(int previousID) { this.previousID = previousID; }
        public int getNextID() { return nextID; }
        public void setNextID(int nextID) { this.nextID = nextID; }
        public List<String> getLocalFiles() { return localFiles; }
        public void setLocalFiles(List<String> localFiles) { this.localFiles = localFiles; }
        public List<String> getReplicatedFiles() { return replicatedFiles; }
        public void setReplicatedFiles(List<String> replicatedFiles) { this.replicatedFiles = replicatedFiles; }
        public List<FileRow> getKnownFiles() { return knownFiles; }
        public void setKnownFiles(List<FileRow> knownFiles) { this.knownFiles = knownFiles; }
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

    public static class EditFileView {
        private String nodeName;
        private String fileName;
        private String location;
        private String content;
        private Integer selectedId;

        public String getNodeName() { return nodeName; }
        public void setNodeName(String nodeName) { this.nodeName = nodeName; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public Integer getSelectedId() { return selectedId; }
        public void setSelectedId(Integer selectedId) { this.selectedId = selectedId; }
    }
}
