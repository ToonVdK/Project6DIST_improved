package be.uantwerpen.fti.node;

import java.io.File;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
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
                        " for failed/leaving node " + failedNodeId
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
                    replicationService.findBestNewOwnerForFile(filename, failedNodeId);

            if (newOwner == null) {
                System.out.println("[FAILURE AGENT] No new owner found for file: " + filename);
                return;
            }

            int newOwnerId = newOwner.getKey();
            String newOwnerIp = newOwner.getValue();

            boolean currentNodeHasCopy = replicationService.hasLocalOrReplicatedCopy(filename);
            boolean currentNodeIsNewOwner = newOwnerIp.equals(nodeState.getIpAddress());

            if (currentNodeIsNewOwner) {
                boolean promoted = replicationService.promoteReplicaToLocal(filename);

                if (promoted) {
                    System.out.println(
                            "[FAILURE AGENT] Current node " + nodeState.getCurrentID() +
                                    " promoted '" + filename + "' to local_files."
                    );
                } else {
                    System.out.println(
                            "[FAILURE AGENT] Current node is new owner of '" + filename +
                                    "', but promotion failed because no replica was found locally."
                    );
                }

            } else {
                boolean promotedRemotely = replicationService.requestPromotionOnNode(filename, newOwnerIp);

                if (promotedRemotely) {
                    System.out.println(
                            "[FAILURE AGENT] Requested new owner node " +
                                    newOwnerId + " to promote '" + filename + "'."
                    );

                } else if (currentNodeHasCopy) {
                    System.out.println(
                            "[FAILURE AGENT] Remote promotion failed. Transferring '" +
                                    filename + "' to new owner " + newOwnerId
                    );

                    replicationService.transferFileToNode(filename, newOwnerIp);
                    replicationService.requestPromotionOnNode(filename, newOwnerIp);

                } else {
                    System.out.println(
                            "[FAILURE AGENT] No physical copy available on current node for '" +
                                    filename + "'. Metadata will still be updated."
                    );
                }
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
