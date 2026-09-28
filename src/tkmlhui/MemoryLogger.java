package tkmlhui;

import java.lang.management.*;
import java.util.*;

/**
 * Peak memory measurement using the JVM's standard JMX API
 * (MemoryPoolMXBean.getPeakUsage()), reset before each run, as required
 * by the technical notes. This is far more accurate than snapshotting
 * Runtime.totalMemory()-freeMemory() before/after.
 */
public class MemoryLogger {
    /** The process-wide singleton instance, lazily created by {@link #getInstance()}. */
    private static MemoryLogger instance;
    /** Highest peak heap usage (in megabytes) observed since the last {@link #reset()}. */
    private double peakMB = 0;
    /** Every heap memory pool exposed by the JVM's management API. */
    private final List<MemoryPoolMXBean> pools;

    /** Not directly instantiable: use {@link #getInstance()}. */
    private MemoryLogger() {
        pools = ManagementFactory.getMemoryPoolMXBeans();
    }

    /**
     * Returns the process-wide singleton instance, creating it on first use.
     *
     * @return the shared {@link MemoryLogger} instance
     */
    public static MemoryLogger getInstance() {
        if (instance == null) instance = new MemoryLogger();
        return instance;
    }

    /** Resets the recorded peak (both this logger's own {@link #peakMB} and every heap pool's JMX peak-usage counter), ready for a fresh run. */
    public void reset() {
        peakMB = 0;
        for (MemoryPoolMXBean pool : pools) {
            if (pool.isValid() && pool.getType() == MemoryType.HEAP) {
                try {
                    pool.resetPeakUsage();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Call periodically during mining to sample current peak usage. */
    public void checkMemory() {
        long total = 0;
        for (MemoryPoolMXBean pool : pools) {
            if (pool.isValid() && pool.getType() == MemoryType.HEAP) {
                MemoryUsage usage = pool.getPeakUsage();
                if (usage != null) total += usage.getUsed();
            }
        }
        double mb = total / (1024.0 * 1024.0);
        if (mb > peakMB) peakMB = mb;
    }

    /**
     * Returns the highest peak heap usage observed since the last {@link #reset()}.
     *
     * @return peak heap usage, in megabytes
     */
    public double getPeakMB() {
        return peakMB;
    }
}
