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

    /*
     * Important fix:
     * A FailureAgent travels through multiple nodes. Without this set, the same file can be
     * promoted/replicated more than once while the agent moves around the ring. That was the
     * cause of situations where one file suddenly became local on multiple surviving nodes.
     */
    private Set<String> handledFilenames = new HashSet<>();

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
                handleFileOwnedByFailedNodeOnce(fileInfo);
            }
        }

        /*
         * Extra safety: also inspect physical replicas on this node. This helps if metadata
         * synchronization was slightly behind when the failure happened.
         */
        for (File replicatedFile : replicationService.getReplicatedFiles()) {
            if (replicatedFile.isFile()
                    && !replicatedFile.getName().startsWith(".")
                    && !replicatedFile.getName().endsWith("~")) {

                NodeState.FileInfo info = nodeState.getFileInfo(replicatedFile.getName());

                if (info != null && info.getOwnerId() == failedNodeId) {
                    handleFileOwnedByFailedNodeOnce(info);
                }
            }
        }
    }

    private void handleFileOwnedByFailedNodeOnce(NodeState.FileInfo fileInfo) {
        String filename = fileInfo.getFilename();

        if (filename == null || filename.trim().isEmpty()) {
            return;
        }

        if (handledFilenames.contains(filename)) {
            System.out.println("[FAILURE AGENT] File already handled by this agent, skipping: " + filename);
            return;
        }

        handledFilenames.add(filename);
        handleFileOwnedByFailedNode(fileInfo);
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
            boolean promoted = false;

            if (currentNodeIsNewOwner) {
                promoted = replicationService.promoteReplicaToLocal(filename);

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
                /*
                 * Guarantee that the selected new owner itself performs the promotion.
                 * The agent may be running on another node, so promotion must be remote-capable.
                 */
                promoted = replicationService.requestPromotionOnNode(filename, newOwnerIp);

                if (promoted) {
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
                    promoted = replicationService.requestPromotionOnNode(filename, newOwnerIp);

                } else {
                    System.out.println(
                            "[FAILURE AGENT] No physical copy available on current node for '" +
                                    filename + "'. Metadata will still be updated."
                    );
                }
            }

            /*
             * Only after successful promotion do we ask the new owner to create a fresh backup
             * replica on a different surviving node. This transfer writes to replicated_files on
             * the backup node, not to local_files. That prevents the old bug where a third node
             * became local owner by accident.
             */
            if (promoted) {
                replicationService.requestBackupReplicationOnNode(filename, newOwnerIp, failedNodeId);
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

    public Set<String> getHandledFilenames() {
        return handledFilenames;
    }

    public void setHandledFilenames(Set<String> handledFilenames) {
        this.handledFilenames = handledFilenames == null ? new HashSet<>() : handledFilenames;
    }
}
