package be.uantwerpen.fti.node;
import be.uantwerpen.fti.common.HashUtils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;


@Service
public class FileReplicationService {

    private final NodeState nodeState;
    private final TcpFileTransferService tcpService;
    private final RestTemplate restTemplate;
    private final String namingServerUrl;

    @Value("${node.api.port:8080}")
    private String nodePort;

    private final String LOCAL_FOLDER = "local_files/";
    private final String REPLICATED_FOLDER = "replicated_files/";

    public FileReplicationService(
            NodeState nodeState,
            TcpFileTransferService tcpService,
            @Value("${naming.server.url}") String namingServerUrl
    ) {
        this.nodeState = nodeState;
        this.tcpService = tcpService;
        this.namingServerUrl = namingServerUrl;
        this.restTemplate = new RestTemplate();

        new File(LOCAL_FOLDER).mkdirs();
        new File(REPLICATED_FOLDER).mkdirs();
    }

    // ============================================================
    // PHASE 1: STARTUP SYNCHRONIZATION
    // ============================================================

    public void replicateExistingFiles() {
        System.out.println("Starting Phase: Scanning local files for replication...");

        File folder = new File(LOCAL_FOLDER);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null || listOfFiles.length == 0) {
            System.out.println("No local files found to replicate.");
            return;
        }

        for (File file : listOfFiles) {
            if (isValidFile(file)) {
                nodeState.addOrUpdateOwnedFile(file.getName());
                replicateSingleFile(file);
            }
        }
    }

    public void replicateSingleFile(File file) {
        if (file == null || !isValidFile(file)) {
            return;
        }

        try {
            nodeState.addOrUpdateOwnedFile(file.getName());

            String targetIp = getReplicationOwnerIp(file.getName());

            if (targetIp != null) {
                if (targetIp.equals(nodeState.getIpAddress())) {
                    System.out.println("File " + file.getName() + " maps to local node. No transfer needed.");
                    return;
                }

                tcpService.sendFile(targetIp, file);
            }

        } catch (Exception e) {
            System.err.println("Error replicating file " + file.getName() + ": " + e.getMessage());
        }
    }

    // ============================================================
    // PHASE 2: LIVE FOLDER UPDATES
    // ============================================================

    public void notifyReplicaDeletion(String filename) {
        try {
            nodeState.removeFile(filename);

            String targetIp = getReplicationOwnerIp(filename);

            if (targetIp != null) {
                if (targetIp.equals(nodeState.getIpAddress())) {
                    System.out.println("File " + filename + " was local only. No remote replica to delete.");
                    return;
                }

                restTemplate.delete("http://" + targetIp + ":" + nodePort + "/api/node/files/" + filename);
            }

        } catch (Exception e) {
            System.err.println("Error notifying replica deletion for " + filename + ": " + e.getMessage());
        }
    }

    // ============================================================
    // PHASE 3: SHUTDOWN LOGIC
    // ============================================================

    public void transferReplicasOnShutdown() {
        System.out.println("Initiating Phase 3: Shifting replicated files to previous neighbor...");

        File folder = new File(REPLICATED_FOLDER);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null || listOfFiles.length == 0) {
            System.out.println("No replicated files to transfer.");
            return;
        }

        try {
            int previousNodeId = nodeState.getPreviousID();

            if (previousNodeId == nodeState.getCurrentID()) {
                System.out.println("Only node in network. No need to transfer replicas.");
                return;
            }

            String previousIp = getNodeIp(previousNodeId);

            if (previousIp != null) {
                for (File file : listOfFiles) {
                    if (isValidFile(file)) {
                        System.out.println("Transferring replica '" + file.getName() + "' to Node " + previousNodeId);
                        tcpService.sendFile(previousIp, file);
                        Thread.sleep(50);
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("Error transferring replicas during shutdown: " + e.getMessage());
        }
    }

    public void warnLocalFilesOffline() {
        System.out.println("Warning network: Local files are going offline...");

        File folder = new File(LOCAL_FOLDER);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null || listOfFiles.length == 0) {
            return;
        }

        for (File file : listOfFiles) {
            if (isValidFile(file)) {
                try {
                    String targetIp = getReplicationOwnerIp(file.getName());

                    if (targetIp != null && !targetIp.equals(nodeState.getIpAddress())) {
                        System.out.println(
                                "Telling Node " + targetIp +
                                        " that local file '" + file.getName() + "' is offline."
                        );

                        restTemplate.postForObject(
                                "http://" + targetIp + ":" + nodePort +
                                        "/api/node/files/" + file.getName() + "/offline",
                                null,
                                String.class
                        );
                    }

                } catch (Exception e) {
                    System.err.println("Failed to warn about offline file " + file.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    // ============================================================
    // Lab 6 helper methods
    // ============================================================

    public File[] getLocalFiles() {
        File folder = new File(LOCAL_FOLDER);
        File[] files = folder.listFiles();

        return files == null ? new File[0] : files;
    }

    public File[] getReplicatedFiles() {
        File folder = new File(REPLICATED_FOLDER);
        File[] files = folder.listFiles();

        return files == null ? new File[0] : files;
    }

    public boolean hasLocalOrReplicatedCopy(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }

        return new File(LOCAL_FOLDER + filename).exists()
                || new File(REPLICATED_FOLDER + filename).exists();
    }

    public File getFileByName(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return null;
        }

        File local = new File(LOCAL_FOLDER + filename);

        if (local.exists() && local.isFile()) {
            return local;
        }

        File replicated = new File(REPLICATED_FOLDER + filename);

        if (replicated.exists() && replicated.isFile()) {
            return replicated;
        }

        return null;
    }

    public void transferFileToNode(String filename, String targetIp) {
        File file = getFileByName(filename);

        if (file == null) {
            System.out.println("Cannot transfer '" + filename + "': no local or replicated copy found.");
            return;
        }

        if (targetIp == null || targetIp.trim().isEmpty()) {
            System.out.println("Cannot transfer '" + filename + "': target IP is unknown.");
            return;
        }

        if (targetIp.equals(nodeState.getIpAddress())) {
            System.out.println("Transfer skipped for '" + filename + "': target is this node.");
            return;
        }

        tcpService.sendFile(targetIp, file);
    }

    public String getReplicationOwnerIp(String filename) {
        return restTemplate.getForObject(
                namingServerUrl + "files/replicate/" + filename,
                String.class
        );
    }

    public String getNodeIp(int nodeId) {
        return restTemplate.getForObject(
                namingServerUrl + "ip/" + nodeId,
                String.class
        );
    }

    public Integer getNodeIdByIp(String ip) {
        if (ip == null) {
            return null;
        }

        try {
            Map<Integer, String> topology = getTopology();

            for (Map.Entry<Integer, String> entry : topology.entrySet()) {
                if (ip.equals(entry.getValue())) {
                    return entry.getKey();
                }
            }

        } catch (Exception e) {
            System.err.println("Could not resolve node id for IP " + ip + ": " + e.getMessage());
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    public Map<Integer, String> getTopology() {
        String topologyUrl = namingServerUrl.replace("/nodes/", "/topology");

        Map<String, String> rawTopology = restTemplate.getForObject(topologyUrl, Map.class);
        Map<Integer, String> topology = new HashMap<>();

        if (rawTopology == null) {
            return topology;
        }

        for (Map.Entry<String, String> entry : rawTopology.entrySet()) {
            topology.put(Integer.parseInt(entry.getKey()), entry.getValue());
        }

        return topology;
    }

    public boolean remoteNodeHasFile(String targetIp, String filename) {
        try {
            Boolean exists = restTemplate.getForObject(
                    "http://" + targetIp + ":" + nodePort + "/api/node/files/" + filename + "/exists",
                    Boolean.class
            );

            return exists != null && exists;

        } catch (Exception e) {
            return false;
        }
    }

    public Map.Entry<Integer, String> getReplicationOwnerExcludingNode(String filename, int excludedNodeId) {
        Map<Integer, String> topology = getTopology();

        topology.remove(excludedNodeId);

        if (topology.isEmpty()) {
            System.out.println("[OWNERSHIP] No surviving nodes available for file: " + filename);
            return null;
        }

        int fileHash = HashUtils.calculateHash(filename);

        Integer selectedNodeId = null;

        /*
         * Same rule as the naming server:
         * choose the node with the largest hash smaller than the file hash.
         */
        for (Integer nodeId : topology.keySet()) {
            if (nodeId < fileHash) {
                if (selectedNodeId == null || nodeId > selectedNodeId) {
                    selectedNodeId = nodeId;
                }
            }
        }

        /*
         * Wrap-around case:
         * if no node hash is smaller than the file hash,
         * choose the largest surviving node hash.
         */
        if (selectedNodeId == null) {
            for (Integer nodeId : topology.keySet()) {
                if (selectedNodeId == null || nodeId > selectedNodeId) {
                    selectedNodeId = nodeId;
                }
            }
        }

        String selectedIp = topology.get(selectedNodeId);

        System.out.println(
                "[OWNERSHIP] New owner for '" + filename +
                        "' excluding node " + excludedNodeId +
                        " is node " + selectedNodeId +
                        " at " + selectedIp
        );

        return new AbstractMap.SimpleEntry<>(selectedNodeId, selectedIp);
    }

    public boolean promoteReplicaToLocal(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }

        File localFolder = new File(LOCAL_FOLDER);
        File replicatedFolder = new File(REPLICATED_FOLDER);

        localFolder.mkdirs();
        replicatedFolder.mkdirs();

        File localFile = new File(LOCAL_FOLDER + filename);
        File replicatedFile = new File(REPLICATED_FOLDER + filename);

        if (localFile.exists() && localFile.isFile()) {
            System.out.println("[OWNERSHIP] File already exists in local_files: " + filename);
            return true;
        }

        if (!replicatedFile.exists() || !replicatedFile.isFile()) {
            System.out.println("[OWNERSHIP] Cannot promote replica. File not found in replicated_files: " + filename);
            return false;
        }

        try {
            Files.move(
                    replicatedFile.toPath(),
                    localFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );

            System.out.println(
                    "[OWNERSHIP] Promoted '" + filename +
                            "' from replicated_files to local_files."
            );

            nodeState.addOrUpdateOwnedFile(filename);

            return true;

        } catch (Exception e) {
            System.err.println(
                    "[OWNERSHIP] Failed to promote replica '" +
                            filename + "': " + e.getMessage()
            );

            return false;
        }
    }

    private boolean isValidFile(File file) {
        return file != null
                && file.isFile()
                && !file.getName().startsWith(".")
                && !file.getName().endsWith("~");
    }
}