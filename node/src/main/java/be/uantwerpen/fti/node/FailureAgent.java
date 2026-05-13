package be.uantwerpen.fti.node;

import java.io.File;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class FailureAgent implements Runnable, Serializable {

    private static final long serialVersionUID = 1L;

    private int failedNodeId;
    private int starterNodeId;
    private Set<Integer> visitedNodeIds = new HashSet<>();

    private transient NodeState nodeState;
    private transient FileReplicationService replicationService;
    private transient String namingServerUrl;
    private transient String nodePort;

    public FailureAgent() {
        // Needed for serialization/deserialization.
    }

    public FailureAgent(int failedNodeId, int starterNodeId) {
        this.failedNodeId = failedNodeId;
        this.starterNodeId = starterNodeId;
    }

    public void configure(
            NodeState nodeState,
            FileReplicationService replicationService,
            String namingServerUrl,
            String nodePort
    ) {
        this.nodeState = nodeState;
        this.replicationService = replicationService;
        this.namingServerUrl = namingServerUrl;
        this.nodePort = nodePort;
    }

    @Override
    public void run() {
        if (nodeState == null || replicationService == null) {
            throw new IllegalStateException("FailureAgent was not configured before running.");
        }

        int currentNodeId = nodeState.getCurrentID();
        visitedNodeIds.add(currentNodeId);

        System.out.println(
                "Failure Agent running on node " + currentNodeId +
                        " for failed node " + failedNodeId
        );

        Map<String, NodeState.FileInfo> fileList = nodeState.getFileListSnapshot();

        for (NodeState.FileInfo fileInfo : fileList.values()) {
            if (fileInfo == null || fileInfo.getFilename() == null) {
                continue;
            }

            if (fileInfo.getOwnerId() == failedNodeId) {
                handleFileOwnedByFailedNode(fileInfo);
            }
        }

        /*
         * Also check physical replicated files.
         * This helps when the local file list was not perfectly synchronized yet.
         */
        for (File replicatedFile : replicationService.getReplicatedFiles()) {
            if (replicatedFile.isFile()
                    && !replicatedFile.getName().startsWith(".")
                    && !replicatedFile.getName().endsWith("~")) {

                NodeState.FileInfo info = nodeState.getFileInfo(replicatedFile.getName());

                if (info != null && info.getOwnerId() == failedNodeId) {
                    handleFileOwnedByFailedNode(info);
                }
            }
        }
    }

    private void handleFileOwnedByFailedNode(NodeState.FileInfo fileInfo) {
        String filename = fileInfo.getFilename();

        try {
            Map.Entry<Integer, String> newOwner =
                    replicationService.getReplicationOwnerExcludingNode(filename, failedNodeId);

            if (newOwner == null) {
                System.out.println("[FAILURE AGENT] No new owner found for file: " + filename);
                return;
            }

            int newOwnerId = newOwner.getKey();
            String newOwnerIp = newOwner.getValue();

            boolean currentNodeHasCopy = replicationService.hasLocalOrReplicatedCopy(filename);
            boolean currentNodeIsNewOwner = newOwnerIp.equals(nodeState.getIpAddress());

            if (currentNodeIsNewOwner) {
                /*
                 * This node becomes the new official owner.
                 * If it only had the file as a replica, promote it to local_files.
                 */
                boolean promoted = replicationService.promoteReplicaToLocal(filename);

                if (promoted) {
                    System.out.println(
                            "[FAILURE AGENT] Node " + nodeState.getCurrentID() +
                                    " is now owner of '" + filename +
                                    "' and has it in local_files."
                    );
                } else {
                    System.out.println(
                            "[FAILURE AGENT] Node " + nodeState.getCurrentID() +
                                    " is new owner of '" + filename +
                                    "', but no replica was available to promote."
                    );
                }

            } else if (currentNodeHasCopy) {
                /*
                 * This node has a copy, but another node is the new owner.
                 * If the new owner does not have the file yet, transfer it.
                 */
                if (replicationService.remoteNodeHasFile(newOwnerIp, filename)) {
                    System.out.println(
                            "[FAILURE AGENT] New owner already has '" +
                                    filename + "'. Only updating metadata."
                    );

                } else {
                    System.out.println(
                            "[FAILURE AGENT] Transferring '" + filename +
                                    "' from node " + nodeState.getCurrentID() +
                                    " to new owner " + newOwnerId
                    );

                    replicationService.transferFileToNode(filename, newOwnerIp);
                }

            } else {
                System.out.println(
                        "[FAILURE AGENT] Node " + nodeState.getCurrentID() +
                                " has no physical copy of '" + filename +
                                "'. Only updating metadata."
                );
            }

            nodeState.updateOwner(filename, newOwnerId, newOwnerIp);

            NodeState.FileInfo updated = nodeState.getFileInfo(filename);

            if (updated != null) {
                updated.setLocked(false);
                updated.setLockOwnerId(-1);
                nodeState.addOrUpdateFile(updated);
            }

            System.out.println(
                    "[FAILURE AGENT] Ownership of '" + filename +
                            "' changed from failed/leaving node " + failedNodeId +
                            " to node " + newOwnerId
            );

        } catch (Exception e) {
            System.err.println(
                    "[FAILURE AGENT] Error while handling file '" +
                            filename + "': " + e.getMessage()
            );
        }
    }

    public boolean shouldStopBeforePassingTo(int nextNodeId) {
        if (nextNodeId == starterNodeId && !visitedNodeIds.isEmpty()) {
            return true;
        }

        return visitedNodeIds.contains(nextNodeId);
    }

    public int getFailedNodeId() {
        return failedNodeId;
    }

    public void setFailedNodeId(int failedNodeId) {
        this.failedNodeId = failedNodeId;
    }

    public int getStarterNodeId() {
        return starterNodeId;
    }

    public void setStarterNodeId(int starterNodeId) {
        this.starterNodeId = starterNodeId;
    }

    public Set<Integer> getVisitedNodeIds() {
        return visitedNodeIds;
    }

    public void setVisitedNodeIds(Set<Integer> visitedNodeIds) {
        this.visitedNodeIds = visitedNodeIds;
    }
}