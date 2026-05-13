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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
    // Lab 5: startup replication
    // ============================================================

    public void replicateExistingFiles() {
        System.out.println("Starting Phase: Scanning local files for replication...");

        File[] listOfFiles = getLocalFiles();

        if (listOfFiles.length == 0) {
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

            if (targetIp == null || targetIp.trim().isEmpty()) {
                System.out.println("No replication target found for " + file.getName());
                return;
            }

            if (targetIp.equals(nodeState.getIpAddress())) {
                System.out.println("File " + file.getName() + " maps to local node. No transfer needed.");
                return;
            }

            tcpService.sendFile(targetIp, file);

        } catch (Exception e) {
            System.err.println("Error replicating file " + file.getName() + ": " + e.getMessage());
        }
    }

    // ============================================================
    // Lab 5: live update / delete synchronization
    // ============================================================

    public void notifyReplicaDeletion(String filename) {
        try {
            nodeState.removeFile(filename);

            String targetIp = getReplicationOwnerIp(filename);

            if (targetIp == null || targetIp.trim().isEmpty()) {
                return;
            }

            if (targetIp.equals(nodeState.getIpAddress())) {
                System.out.println("File " + filename + " was local only. No remote replica to delete.");
                return;
            }

            restTemplate.delete("http://" + targetIp + ":" + nodePort + "/api/node/files/" + filename);

        } catch (Exception e) {
            System.err.println("Error notifying replica deletion for " + filename + ": " + e.getMessage());
        }
    }

    // ============================================================
    // Lab 5: shutdown replica shifting
    // ============================================================

    public void transferReplicasOnShutdown() {
        System.out.println("Initiating Phase 3: Shifting replicated files to previous neighbor...");

        File[] listOfFiles = getReplicatedFiles();

        if (listOfFiles.length == 0) {
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

        for (File file : getLocalFiles()) {
            if (isValidFile(file)) {
                try {
                    String targetIp = getReplicationOwnerIp(file.getName());

                    if (targetIp != null && !targetIp.equals(nodeState.getIpAddress())) {
                        System.out.println("Telling Node " + targetIp + " that local file '" + file.getName() + "' is offline.");

                        restTemplate.postForObject(
                                "http://" + targetIp + ":" + nodePort + "/api/node/files/" + file.getName() + "/offline",
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
    // Physical file helpers
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

    public boolean hasLocalCopy(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }
        return new File(LOCAL_FOLDER + filename).exists();
    }

    public boolean hasReplicatedCopy(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }
        return new File(REPLICATED_FOLDER + filename).exists();
    }

    public boolean hasLocalOrReplicatedCopy(String filename) {
        return hasLocalCopy(filename) || hasReplicatedCopy(filename);
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

    // ============================================================
    // Naming server / topology helpers
    // ============================================================

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
        Map<Integer, String> topology = new TreeMap<>();

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

    public boolean remoteNodeHasReplicatedFile(String targetIp, String filename) {
        try {
            Boolean exists = restTemplate.getForObject(
                    "http://" + targetIp + ":" + nodePort + "/api/node/files/" + filename + "/replica-exists",
                    Boolean.class
            );

            return exists != null && exists;

        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================
    // Ownership handoff / promotion logic
    // ============================================================

    public Map.Entry<Integer, String> getReplicationOwnerExcludingNode(String filename, int excludedNodeId) {
        Set<Integer> excluded = new HashSet<>();
        excluded.add(excludedNodeId);
        return getRingOwnerExcluding(filename, excluded);
    }

    public Map.Entry<Integer, String> findBestNewOwnerForFile(String filename, int excludedNodeId) {
        Map<Integer, String> topology = new TreeMap<>(getTopology());
        topology.remove(excludedNodeId);

        if (topology.isEmpty()) {
            System.out.println("[OWNERSHIP] No surviving nodes available for file: " + filename);
            return null;
        }

        /*
         * First preference: choose a surviving node that already has the file as a REPLICA.
         * This avoids promoting random nodes that merely received a backup later.
         */
        for (Map.Entry<Integer, String> entry : topology.entrySet()) {
            int nodeId = entry.getKey();
            String nodeIp = entry.getValue();

            if (remoteNodeHasReplicatedFile(nodeIp, filename)) {
                System.out.println(
                        "[OWNERSHIP] New owner for '" + filename +
                                "' chosen because it has the replicated copy: node " + nodeId + " at " + nodeIp
                );
                return new AbstractMap.SimpleEntry<>(nodeId, nodeIp);
            }
        }

        /*
         * Second preference: any surviving node that physically has the file.
         */
        for (Map.Entry<Integer, String> entry : topology.entrySet()) {
            int nodeId = entry.getKey();
            String nodeIp = entry.getValue();

            if (remoteNodeHasFile(nodeIp, filename)) {
                System.out.println(
                        "[OWNERSHIP] New owner for '" + filename +
                                "' chosen because it has a physical copy: node " + nodeId + " at " + nodeIp
                );
                return new AbstractMap.SimpleEntry<>(nodeId, nodeIp);
            }
        }

        /*
         * Fallback: use the normal hash-ring rule, excluding the leaving/failed node.
         */
        Set<Integer> excluded = new HashSet<>();
        excluded.add(excludedNodeId);
        return getRingOwnerExcluding(filename, excluded);
    }

    private Map.Entry<Integer, String> getRingOwnerExcluding(String filename, Set<Integer> excludedNodeIds) {
        Map<Integer, String> topology = new TreeMap<>(getTopology());

        for (Integer excluded : excludedNodeIds) {
            topology.remove(excluded);
        }

        if (topology.isEmpty()) {
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
            selectedNodeId = topology.keySet().stream().max(Integer::compareTo).orElse(null);
        }

        if (selectedNodeId == null) {
            return null;
        }

        return new AbstractMap.SimpleEntry<>(selectedNodeId, topology.get(selectedNodeId));
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
            if (replicatedFile.exists() && replicatedFile.isFile()) {
                boolean deleted = replicatedFile.delete();
                System.out.println("[OWNERSHIP] Local file already exists. Removed duplicate replica " + filename + ": " + deleted);
            }
            nodeState.addOrUpdateOwnedFile(filename);
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

            System.out.println("[OWNERSHIP] Promoted '" + filename + "' from replicated_files to local_files.");
            nodeState.addOrUpdateOwnedFile(filename);
            return true;

        } catch (Exception e) {
            System.err.println("[OWNERSHIP] Failed to promote replica '" + filename + "': " + e.getMessage());
            return false;
        }
    }

    public boolean requestPromotionOnNode(String filename, String targetIp) {
        if (filename == null || targetIp == null || targetIp.trim().isEmpty()) {
            return false;
        }

        try {
            Boolean result = restTemplate.postForObject(
                    "http://" + targetIp + ":" + nodePort + "/api/node/files/" + filename + "/promote",
                    null,
                    Boolean.class
            );

            return result != null && result;

        } catch (Exception e) {
            System.err.println("[OWNERSHIP] Could not request promotion of '" + filename + "' on node " + targetIp + ": " + e.getMessage());
            return false;
        }
    }

    public boolean requestBackupReplicationOnNode(String filename, String ownerIp, int oldOwnerId) {
        if (filename == null || ownerIp == null || ownerIp.trim().isEmpty()) {
            return false;
        }

        try {
            Boolean result = restTemplate.postForObject(
                    "http://" + ownerIp + ":" + nodePort + "/api/node/files/" + filename + "/replicate-promoted?oldOwnerId=" + oldOwnerId,
                    null,
                    Boolean.class
            );

            return result != null && result;

        } catch (Exception e) {
            System.err.println("[OWNERSHIP] Could not request backup replication of '" + filename + "' on owner node " + ownerIp + ": " + e.getMessage());
            return false;
        }
    }

    public boolean replicatePromotedLocalFile(String filename, int oldOwnerId) {
        File localFile = new File(LOCAL_FOLDER + filename);

        if (!localFile.exists() || !localFile.isFile()) {
            System.out.println("[OWNERSHIP] Cannot replicate promoted file. It is not in local_files: " + filename);
            return false;
        }

        Set<Integer> excluded = new HashSet<>();
        excluded.add(nodeState.getCurrentID());
        excluded.add(oldOwnerId);

        Map.Entry<Integer, String> backupTarget = getRingOwnerExcluding(filename, excluded);

        if (backupTarget == null) {
            System.out.println("[OWNERSHIP] No backup target available for promoted file '" + filename + "'. Probably only one surviving node.");
            return false;
        }

        String backupIp = backupTarget.getValue();

        if (backupIp == null || backupIp.equals(nodeState.getIpAddress())) {
            return false;
        }

        if (remoteNodeHasFile(backupIp, filename)) {
            System.out.println("[OWNERSHIP] Backup target already has '" + filename + "'. No duplicate transfer needed.");
            return true;
        }

        System.out.println(
                "[OWNERSHIP] Replicating promoted local file '" + filename +
                        "' from owner " + nodeState.getCurrentID() +
                        " to backup node " + backupTarget.getKey()
        );

        tcpService.sendFile(backupIp, localFile);
        return true;
    }

    private boolean isValidFile(File file) {
        return file != null
                && file.isFile()
                && !file.getName().startsWith(".")
                && !file.getName().endsWith("~");
    }
}
