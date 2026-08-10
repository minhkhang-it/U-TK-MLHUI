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
    private static MemoryLogger instance;
    private double peakMB = 0;
    private final List<MemoryPoolMXBean> pools;

    private MemoryLogger() {
        pools = ManagementFactory.getMemoryPoolMXBeans();
    }

    public static MemoryLogger getInstance() {
        if (instance == null) instance = new MemoryLogger();
        return instance;
    }

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

    public double getPeakMB() {
        return peakMB;
    }
}
