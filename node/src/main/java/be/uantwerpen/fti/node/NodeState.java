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

    private volatile int previousID;
    private volatile int nextID;

    /*
     * Lab 6:
     * Global file list known by this node.
     *
     * key   = filename
     * value = owner/location/lock metadata
     */
    private final Map<String, FileInfo> fileList = new ConcurrentHashMap<>();

    public void init(String name, String ipAddress) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.currentID = HashUtils.calculateHash(name);

        this.previousID = this.currentID;
        this.nextID = this.currentID;
    }

    public String getName() {
        return name;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getCurrentID() {
        return currentID;
    }

    public int getPreviousID() {
        return previousID;
    }

    public int getNextID() {
        return nextID;
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

    /*
     * Important:
     * This method accepts Map<String, ?> instead of Map<String, FileInfo>
     * because REST JSON often deserializes values as LinkedHashMap.
     */
    public void mergeFileList(Map<String, ?> incomingList) {
        if (incomingList == null) {
            return;
        }

        for (Map.Entry<String, ?> entry : incomingList.entrySet()) {
            String filename = entry.getKey();
            FileInfo incoming = toFileInfo(entry.getValue(), filename);

            if (filename == null || incoming == null || incoming.getFilename() == null) {
                continue;
            }

            fileList.merge(filename, incoming, this::mergeFileInfo);
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
         * Ownership:
         * If incoming ownership is known, accept it.
         * This is needed when the Failure Agent changes the owner.
         */
        if (incoming.getOwnerId() != -1) {
            merged.setOwnerId(incoming.getOwnerId());
            merged.setOwnerIp(incoming.getOwnerIp());
        }

        if (incoming.getLastKnownLocationIp() != null) {
            merged.setLastKnownLocationIp(incoming.getLastKnownLocationIp());
        }

        /*
         * Locking:
         * Only accept newer lock information.
         * This prevents old "locked=false" values from undoing a newer lock.
         */
        if (incoming.getLockVersion() > merged.getLockVersion()) {
            merged.setLocked(incoming.isLocked());
            merged.setLockOwnerId(incoming.isLocked() ? incoming.getLockOwnerId() : -1);
            merged.setLockVersion(incoming.getLockVersion());
        }

        return merged;
    }

    /*
     * This is called by SyncAgent when scanning local_files/.
     * It must NOT reset lock state every time the file is scanned.
     */
    public void addOrUpdateOwnedFile(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return;
        }

        FileInfo existing = fileList.get(filename);

        FileInfo info = new FileInfo();
        info.setFilename(filename);
        info.setOwnerId(currentID);
        info.setOwnerIp(ipAddress);
        info.setLastKnownLocationIp(ipAddress);

        if (existing != null) {
            info.setLocked(existing.isLocked());
            info.setLockOwnerId(existing.getLockOwnerId());
            info.setLockVersion(existing.getLockVersion());
        } else {
            info.setLocked(false);
            info.setLockOwnerId(-1);
            info.setLockVersion(0L);
        }

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
            info.setLockVersion(System.currentTimeMillis());

            System.out.println("[LOCK] File locked: " + filename + " by node " + lockOwnerId);
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
            info.setLockVersion(System.currentTimeMillis());

            System.out.println("[LOCK] File unlocked: " + filename + " by node " + lockOwnerId);
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
        if (filename == null) {
            return;
        }

        FileInfo info = fileList.get(filename);

        if (info == null) {
            info = new FileInfo();
            info.setFilename(filename);
            fileList.put(filename, info);
        }

        info.setOwnerId(newOwnerId);
        info.setOwnerIp(newOwnerIp);
        info.setLastKnownLocationIp(newOwnerIp);

        System.out.println("[FILE LIST] Owner of '" + filename + "' updated to node " + newOwnerId);
    }

    // ============================================================
    // JSON/REST conversion helpers
    // ============================================================

    @SuppressWarnings("unchecked")
    private FileInfo toFileInfo(Object raw, String fallbackFilename) {
        if (raw == null) {
            return null;
        }

        if (raw instanceof FileInfo fileInfo) {
            return new FileInfo(fileInfo);
        }

        if (raw instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;

            FileInfo info = new FileInfo();

            Object filenameValue = map.get("filename");
            info.setFilename(filenameValue == null ? fallbackFilename : filenameValue.toString());

            info.setOwnerId(asInt(map.get("ownerId"), -1));
            info.setOwnerIp(asString(map.get("ownerIp")));
            info.setLastKnownLocationIp(asString(map.get("lastKnownLocationIp")));
            info.setLocked(asBoolean(map.get("locked"), false));
            info.setLockOwnerId(asInt(map.get("lockOwnerId"), -1));
            info.setLockVersion(asLong(map.get("lockVersion"), 0L));

            return info;
        }

        return null;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private int asInt(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Number number) {
            return number.intValue();
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        if (value instanceof Boolean bool) {
            return bool;
        }

        return Boolean.parseBoolean(value.toString());
    }

    // ============================================================
    // Inner class to avoid adding an extra file
    // ============================================================

    public static class FileInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        private String filename;
        private int ownerId = -1;
        private String ownerIp;
        private String lastKnownLocationIp;

        private boolean locked;
        private int lockOwnerId = -1;

        /*
         * Used to know whether incoming lock information is newer.
         * Lock/unlock changes update this value.
         */
        private long lockVersion = 0L;

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
            this.lockVersion = other.lockVersion;
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

        public long getLockVersion() {
            return lockVersion;
        }

        public void setLockVersion(long lockVersion) {
            this.lockVersion = lockVersion;
        }
    }
}