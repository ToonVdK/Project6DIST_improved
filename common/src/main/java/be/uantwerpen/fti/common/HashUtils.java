package be.uantwerpen.fti.common;

public class HashUtils {
    /**
     * Calculates the hash based on the node or file name.
     * Maps the default interval to the required interval (0, 32768).
     */
    public static int calculateHash(String name) {
        long max = 2147483647L;
        long min = -2147483648L;
        long hash = name.hashCode();

        // Using double to prevent overflow during max + abs(min) calculation
        double divisor = max + Math.abs((double) min);
        return (int) (((hash + max) * (32768.0 / divisor)));
    }
}
