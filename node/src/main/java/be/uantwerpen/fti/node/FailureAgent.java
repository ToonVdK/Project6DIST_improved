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
            String newOwnerIp = replicationService.getReplicationOwnerIp(filename);

            if (newOwnerIp == null || newOwnerIp.trim().isEmpty()) {
                System.out.println("Failure Agent could not find new owner for file: " + filename);
                return;
            }

            Integer newOwnerId = replicationService.getNodeIdByIp(newOwnerIp);

            if (newOwnerId == null) {
                System.out.println("Failure Agent could not resolve new owner ID for file: " + filename);
                return;
            }

            boolean currentNodeHasCopy = replicationService.hasLocalOrReplicatedCopy(filename);

            if (currentNodeHasCopy) {
                if (newOwnerIp.equals(nodeState.getIpAddress())) {
                    System.out.println(
                            "File '" + filename + "' is already on its new owner node " + newOwnerId
                    );

                } else if (replicationService.remoteNodeHasFile(newOwnerIp, filename)) {
                    System.out.println(
                            "New owner already has file '" + filename +
                                    "'. Only updating file list."
                    );

                } else {
                    System.out.println(
                            "Transferring file '" + filename +
                                    "' from node " + nodeState.getCurrentID() +
                                    " to new owner " + newOwnerId
                    );

                    replicationService.transferFileToNode(filename, newOwnerIp);
                }

            } else {
                System.out.println(
                        "Node " + nodeState.getCurrentID() +
                                " has no copy of '" + filename +
                                "'. Only updating known ownership."
                );
            }

            nodeState.updateOwner(filename, newOwnerId, newOwnerIp);

            NodeState.FileInfo updated = nodeState.getFileInfo(filename);

            if (updated != null) {
                updated.setLocked(false);
                updated.setLockOwnerId(-1);
                nodeState.addOrUpdateFile(updated);
            }

        } catch (Exception e) {
            System.err.println(
                    "Failure Agent error while handling file '" +
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