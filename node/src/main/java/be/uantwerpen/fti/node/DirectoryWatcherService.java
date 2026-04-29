package be.uantwerpen.fti.node;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.*;

@Service
public class DirectoryWatcherService {

    private final String LOCAL_FOLDER = "local_files/";
    private final FileReplicationService replicationService;

    public DirectoryWatcherService(FileReplicationService replicationService) {
        this.replicationService = replicationService;
        new File(LOCAL_FOLDER).mkdirs(); // Ensure folder exists
    }

    @PostConstruct
    public void startWatching() {
        new Thread(() -> {
            try {
                WatchService watchService = FileSystems.getDefault().newWatchService();
                Path path = Paths.get(LOCAL_FOLDER);

                // Register the watcher for Create, Delete, and Modify events
                path.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);

                System.out.println("👁 Directory Watcher started on: " + LOCAL_FOLDER);

                while (true) {
                    WatchKey key = watchService.take(); // Blocks until an event occurs

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        // Prevent the watcher from triggering on temporary OS files
                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                        String fileName = event.context().toString();

                        if (fileName.startsWith(".") || fileName.endsWith("~")) {
                            continue;
                        }

                        File changedFile = new File(LOCAL_FOLDER + fileName);

                        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                            System.out.println("File added: " + fileName);
                            // Wait a tiny bit to ensure the file is fully written before sending
                            Thread.sleep(100);
                            replicationService.replicateSingleFile(changedFile);

                        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            System.out.println("File modified: " + fileName);
                            // Wait a tiny bit to ensure the file is fully written before sending
                            Thread.sleep(100);
                            replicationService.replicateSingleFile(changedFile);

                        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            System.out.println("File deleted locally: " + fileName);
                            replicationService.notifyReplicaDeletion(fileName);
                        }
                    }

                    boolean valid = key.reset();
                    if (!valid) {
                        break; // Exit if the directory becomes inaccessible
                    }
                }
            } catch (Exception e) {
                System.err.println("Directory Watcher Error: " + e.getMessage());
            }
        }).start();
    }
}