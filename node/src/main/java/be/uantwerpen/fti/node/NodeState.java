package be.uantwerpen.fti.node;

import be.uantwerpen.fti.common.HashUtils;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class NodeState {

    private String name;
    private String ipAddress;
    private int currentID;

    // Volatile forces all threads to read the live, updated values.
    private volatile int previousID;
    private volatile int nextID;

    /*
     * Lab 6:
     * Every node keeps a synchronized list of all files in System Y.
     *
     * Key   = filename
     * Value = file metadata: owner, location, lock state
     */
    private final Map<String, FileInfo> fileList = new ConcurrentHashMap<>();

    public void init(String name, String ipAddress) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.currentID = HashUtils.calculateHash(name);

        // Initially, a node is its own previous and next node.
        this.previousID = this.currentID;
        this.nextID = this.currentID;
    }

    public String getName() {
        return this.name;
    }

    public String getIpAddress() {
        return this.ipAddress;
    }

    public int getCurrentID() {
        return this.currentID;
    }

    public int getPreviousID() {
        return this.previousID;
    }

    public int getNextID() {
        return this.nextID;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setCurrentID(int currentID) {
        this.currentID = currentID;
    }

    public void setPreviousID(int previousID) {
        this.previousID = previousID;
    }

    public void setNextID(int nextID) {
        this.nextID = nextID;
    }

    // ============================================================
    // Lab 6 file list helpers
    // ============================================================

    public Map<String, FileInfo> getFileListSnapshot() {
        Map<String, FileInfo> snapshot = new ConcurrentHashMap<>();

        for (Map.Entry<String, FileInfo> entry : fileList.entrySet()) {
            snapshot.put(entry.getKey(), new FileInfo(entry.getValue()));
        }

        return snapshot;
    }

    public void mergeFileList(Map<String, FileInfo> incomingList) {
        if (incomingList == null) {
            return;
        }

        for (Map.Entry<String, FileInfo> entry : incomingList.entrySet()) {
            String filename = entry.getKey();
            FileInfo incoming = entry.getValue();

            if (filename == null || incoming == null) {
                continue;
            }

            fileList.merge(filename, new FileInfo(incoming), this::mergeFileInfo);
        }
    }

    private FileInfo mergeFileInfo(FileInfo current, FileInfo incoming) {
        if (current == null) {
            return new FileInfo(incoming);
        }

        if (incoming == null) {
            return current;
        }

        FileInfo merged = new FileInfo(current);

        /*
         * Prefer the incoming ownership if it is known.
         * This is important when the Failure Agent changes ownership.
         */
        if (incoming.getOwnerId() != -1) {
            merged.setOwnerId(incoming.getOwnerId());
            merged.setOwnerIp(incoming.getOwnerIp());
        }

        /*
         * If any node knows the file is locked, keep it locked.
         * Unlocking is done explicitly through unlockFile().
         */
        if (incoming.isLocked()) {
            merged.setLocked(true);
            merged.setLockOwnerId(incoming.getLockOwnerId());
        }

        if (incoming.getLastKnownLocationIp() != null) {
            merged.setLastKnownLocationIp(incoming.getLastKnownLocationIp());
        }

        return merged;
    }

    public void addOrUpdateOwnedFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return;
        }

        FileInfo info = new FileInfo();
        info.setFilename(filename);
        info.setOwnerId(currentID);
        info.setOwnerIp(ipAddress);
        info.setLastKnownLocationIp(ipAddress);
        info.setLocked(false);
        info.setLockOwnerId(-1);

        fileList.merge(filename, info, this::mergeFileInfo);
    }

    public void addOrUpdateFile(FileInfo info) {
        if (info == null || info.getFilename() == null) {
            return;
        }

        fileList.merge(info.getFilename(), new FileInfo(info), this::mergeFileInfo);
    }

    public void removeFile(String filename) {
        if (filename != null) {
            fileList.remove(filename);
        }
    }

    public boolean lockFile(String filename, int lockOwnerId) {
        FileInfo info = fileList.get(filename);

        if (info == null) {
            return false;
        }

        synchronized (info) {
            if (info.isLocked()) {
                return false;
            }

            info.setLocked(true);
            info.setLockOwnerId(lockOwnerId);
            return true;
        }
    }

    public boolean unlockFile(String filename, int lockOwnerId) {
        FileInfo info = fileList.get(filename);

        if (info == null) {
            return false;
        }

        synchronized (info) {
            if (!info.isLocked()) {
                return true;
            }

            if (info.getLockOwnerId() != lockOwnerId && lockOwnerId != currentID) {
                return false;
            }

            info.setLocked(false);
            info.setLockOwnerId(-1);
            return true;
        }
    }

    public boolean isLocked(String filename) {
        FileInfo info = fileList.get(filename);
        return info != null && info.isLocked();
    }

    public FileInfo getFileInfo(String filename) {
        FileInfo info = fileList.get(filename);
        return info == null ? null : new FileInfo(info);
    }

    public void updateOwner(String filename, int newOwnerId, String newOwnerIp) {
        FileInfo info = fileList.get(filename);

        if (info == null) {
            info = new FileInfo();
            info.setFilename(filename);
            fileList.put(filename, info);
        }

        info.setOwnerId(newOwnerId);
        info.setOwnerIp(newOwnerIp);
        info.setLastKnownLocationIp(newOwnerIp);
    }

    // ============================================================
    // Inner class to avoid adding another file
    // ============================================================

    public static class FileInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private String filename;
        private int ownerId = -1;
        private String ownerIp;
        private String lastKnownLocationIp;
        private boolean locked;
        private int lockOwnerId = -1;

        public FileInfo() {
        }

        public FileInfo(FileInfo other) {
            if (other == null) {
                return;
            }

            this.filename = other.filename;
            this.ownerId = other.ownerId;
            this.ownerIp = other.ownerIp;
            this.lastKnownLocationIp = other.lastKnownLocationIp;
            this.locked = other.locked;
            this.lockOwnerId = other.lockOwnerId;
        }

        public String getFilename() {
            return filename;
        }

        public void setFilename(String filename) {
            this.filename = filename;
        }

        public int getOwnerId() {
            return ownerId;
        }

        public void setOwnerId(int ownerId) {
            this.ownerId = ownerId;
        }

        public String getOwnerIp() {
            return ownerIp;
        }

        public void setOwnerIp(String ownerIp) {
            this.ownerIp = ownerIp;
        }

        public String getLastKnownLocationIp() {
            return lastKnownLocationIp;
        }

        public void setLastKnownLocationIp(String lastKnownLocationIp) {
            this.lastKnownLocationIp = lastKnownLocationIp;
        }

        public boolean isLocked() {
            return locked;
        }

        public void setLocked(boolean locked) {
            this.locked = locked;
        }

        public int getLockOwnerId() {
            return lockOwnerId;
        }

        public void setLockOwnerId(int lockOwnerId) {
            this.lockOwnerId = lockOwnerId;
        }
    }
}