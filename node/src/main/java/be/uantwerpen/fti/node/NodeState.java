package be.uantwerpen.fti.node;

import be.uantwerpen.fti.common.HashUtils;
import org.springframework.stereotype.Component;

@Component
public class NodeState {
    private String name;
    private String ipAddress;
    private int currentID;
    // The volatile keyword forces all threads to read the live, updated values!
    private volatile int previousID;
    private volatile int nextID;

    public void init(String name, String ipAddress) {
        this.name = name;
        this.ipAddress = ipAddress;
        this.currentID = HashUtils.calculateHash(name);
        // Initially, a node is its own previous and next node
        this.previousID = this.currentID;
        this.nextID = this.currentID;
    }

    public String getName() { return this.name; }
    public String getIpAddress() { return this.ipAddress; }
    public int getCurrentID() { return this.currentID; }
    public int getPreviousID() { return this.previousID; }
    public int getNextID() { return this.nextID; }

    public void setName(String name) { this.name = name; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public void setCurrentID(int currentID) { this.currentID = currentID; }
    public void setPreviousID(int previousID) { this.previousID = previousID; }
    public void setNextID(int nextID) { this.nextID = nextID; }
}