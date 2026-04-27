package be.uantwerpen.fti.node;

import be.uantwerpen.fti.common.HashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;

@Service
public class FileReplicationService {

    private final NodeState nodeState;
    private final TcpFileTransferService tcpService;
    private final RestTemplate restTemplate;
    private final String namingServerUrl;

    // We define our local storage folder
    private final String LOCAL_FOLDER = "local_files/";

    public FileReplicationService(NodeState nodeState,
                                  TcpFileTransferService tcpService,
                                  @Value("${naming.server.url}") String namingServerUrl) {
        this.nodeState = nodeState;
        this.tcpService = tcpService;
        this.namingServerUrl = namingServerUrl;
        this.restTemplate = new RestTemplate();

        // Ensure the local folder exists
        new File(LOCAL_FOLDER).mkdirs();
    }

    /**
     * Called during the Starting Phase to replicate all existing local files.
     */
    public void replicateExistingFiles() {
        System.out.println("Starting Phase: Scanning local files for replication...");

        File folder = new File(LOCAL_FOLDER);
        File[] listOfFiles = folder.listFiles();

        if (listOfFiles == null || listOfFiles.length == 0) {
            System.out.println("No local files found to replicate.");
            return;
        }

        for (File file : listOfFiles) {
            if (file.isFile()) {
                replicateSingleFile(file);
            }
        }
    }

    /**
     * Replicates a single file based on the Naming Server's instruction.
     */
    public void replicateSingleFile(File file) {
        try {
            // 1. Ask the Naming Server where this file belongs
            String targetIp = restTemplate.getForObject(
                    namingServerUrl + "files/replicate/" + file.getName(), String.class);

            if (targetIp != null) {
                // Edge Case: Check if we are supposed to replicate it to ourselves!
                // (e.g. if we are the only node in the network)
                if (targetIp.equals(nodeState.getIpAddress())) {
                    System.out.println("File " + file.getName() + " maps to local node. No transfer needed.");
                    return;
                }

                System.out.println("Naming Server maps '" + file.getName() + "' to replicated node: " + targetIp);

                // 2. Transfer the file via TCP
                tcpService.sendFile(targetIp, file);

            } else {
                System.err.println("Naming server could not find a target for " + file.getName());
            }

        } catch (Exception e) {
            System.err.println("Error replicating file " + file.getName() + ": " + e.getMessage());
        }
    }
}