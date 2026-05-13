package be.uantwerpen.fti.node;

import be.uantwerpen.fti.common.HashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;

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

        for (Integer nodeId : topology.keySet()) {
            if (nodeId < fileHash) {
                if (selectedNodeId == null || nodeId > selectedNodeId) {
                    selectedNodeId = nodeId;
                }
            }
        }

        if (selectedNodeId == null) {
            for (Integer nodeId : topology.keySet()) {
                if (selectedNodeId == null || nodeId > selectedNodeId) {
                    selectedNodeId = nodeId;
                }
            }
        }

        String selectedIp = topology.get(selectedNodeId);
        System.out.println("[OWNERSHIP] New owner for '" + filename + "' excluding node " + excludedNodeId +
                " is node " + selectedNodeId + " at " + selectedIp);

        return new AbstractMap.SimpleEntry<>(selectedNodeId, selectedIp);
    }

    public Map.Entry<Integer, String> findBestNewOwnerForFile(String filename, int excludedNodeId) {
        Map<Integer, String> topology = getTopology();
        topology.remove(excludedNodeId);

        if (topology.isEmpty()) {
            System.out.println("[OWNERSHIP] No surviving nodes available for file: " + filename);
            return null;
        }

        for (Map.Entry<Integer, String> entry : topology.entrySet()) {
            int nodeId = entry.getKey();
            String nodeIp = entry.getValue();

            if (remoteNodeHasFile(nodeIp, filename)) {
                System.out.println("[OWNERSHIP] New owner for '" + filename +
                        "' chosen because it already has a physical copy: node " + nodeId + " at " + nodeIp);
                return new AbstractMap.SimpleEntry<>(nodeId, nodeIp);
            }
        }

        return getReplicationOwnerExcludingNode(filename, excludedNodeId);
    }

    public boolean promoteReplicaToLocal(String filename) {
        return promoteReplicaToLocal(filename, -1);
    }

    public boolean promoteReplicaToLocal(String filename, int excludedNodeId) {
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
            nodeState.addOrUpdateOwnedFile(filename);
            replicatePromotedLocalFileToBackupNode(filename, excludedNodeId);
            return true;
        }

        if (!replicatedFile.exists() || !replicatedFile.isFile()) {
            System.out.println("[OWNERSHIP] Cannot promote replica. File not found in replicated_files: " + filename);
            return false;
        }

        try {
            Files.move(replicatedFile.toPath(), localFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[OWNERSHIP] Promoted '" + filename + "' from replicated_files to local_files.");

            /*
             * After promotion this node is the new logical owner.
             * We immediately create a fresh backup replica on another surviving node.
             * This is needed because the old replica was moved into local_files.
             */
            nodeState.addOrUpdateOwnedFile(filename);
            replicatePromotedLocalFileToBackupNode(filename, excludedNodeId);

            return true;
        } catch (Exception e) {
            System.err.println("[OWNERSHIP] Failed to promote replica '" + filename + "': " + e.getMessage());
            return false;
        }
    }

    public boolean replicatePromotedLocalFileToBackupNode(String filename, int excludedNodeId) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }

        File localFile = new File(LOCAL_FOLDER + filename);
        if (!localFile.exists() || !localFile.isFile()) {
            System.out.println("[OWNERSHIP] Cannot create backup replica for '" + filename + "': local file missing.");
            return false;
        }

        Map<Integer, String> topology = getTopology();

        // Do not replicate the backup to ourselves.
        topology.remove(nodeState.getCurrentID());

        // During graceful shutdown, the leaving node may still be present in the Naming Server.
        // Exclude it explicitly, otherwise we may try to replicate back to the node that is stopping.
        if (excludedNodeId != -1) {
            topology.remove(excludedNodeId);
        }

        if (topology.isEmpty()) {
            System.out.println("[OWNERSHIP] No other surviving node available to replicate promoted file: " + filename);
            return false;
        }

        Integer preferredNodeId = chooseBackupNodeId(filename, topology);

        if (preferredNodeId != null) {
            String preferredIp = topology.get(preferredNodeId);
            if (tryReplicatePromotedFileToNode(filename, localFile, preferredNodeId, preferredIp)) {
                return true;
            }
        }

        // Fallback: try every other surviving node until one accepts the file.
        for (Map.Entry<Integer, String> entry : topology.entrySet()) {
            if (preferredNodeId != null && entry.getKey().equals(preferredNodeId)) {
                continue;
            }

            if (tryReplicatePromotedFileToNode(filename, localFile, entry.getKey(), entry.getValue())) {
                return true;
            }
        }

        System.out.println("[OWNERSHIP] Could not create backup replica for promoted file: " + filename);
        return false;
    }

    private Integer chooseBackupNodeId(String filename, Map<Integer, String> candidateTopology) {
        if (candidateTopology == null || candidateTopology.isEmpty()) {
            return null;
        }

        int fileHash = HashUtils.calculateHash(filename);
        Integer selectedNodeId = null;

        for (Integer nodeId : candidateTopology.keySet()) {
            if (nodeId < fileHash) {
                if (selectedNodeId == null || nodeId > selectedNodeId) {
                    selectedNodeId = nodeId;
                }
            }
        }

        if (selectedNodeId == null) {
            for (Integer nodeId : candidateTopology.keySet()) {
                if (selectedNodeId == null || nodeId > selectedNodeId) {
                    selectedNodeId = nodeId;
                }
            }
        }

        return selectedNodeId;
    }

    private boolean tryReplicatePromotedFileToNode(String filename, File localFile, int targetNodeId, String targetIp) {
        if (targetIp == null || targetIp.trim().isEmpty()) {
            return false;
        }

        if (targetIp.equals(nodeState.getIpAddress())) {
            return false;
        }

        try {
            if (remoteNodeHasFile(targetIp, filename)) {
                System.out.println(
                        "[OWNERSHIP] Backup replica for promoted file '" + filename +
                                "' already exists on node " + targetNodeId
                );
                return true;
            }

            System.out.println(
                    "[OWNERSHIP] Replicating promoted local file '" + filename +
                            "' to backup node " + targetNodeId + " at " + targetIp
            );

            tcpService.sendFile(targetIp, localFile);
            return true;

        } catch (Exception e) {
            System.err.println(
                    "[OWNERSHIP] Failed to replicate promoted file '" + filename +
                            "' to node " + targetNodeId + ": " + e.getMessage()
            );
            return false;
        }
    }

    public boolean requestPromotionOnNode(String filename, String targetIp) {
        return requestPromotionOnNode(filename, targetIp, -1);
    }

    public boolean requestPromotionOnNode(String filename, String targetIp, int excludedNodeId) {
        if (filename == null || targetIp == null || targetIp.trim().isEmpty()) {
            return false;
        }

        try {
            Boolean result = restTemplate.postForObject(
                    "http://" + targetIp + ":" + nodePort + "/api/node/files/" + filename +
                            "/promote?excludeNodeId=" + excludedNodeId,
                    null,
                    Boolean.class
            );

            return result != null && result;

        } catch (Exception e) {
            System.err.println("[OWNERSHIP] Could not request promotion of '" + filename +
                    "' on node " + targetIp + ": " + e.getMessage());
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
