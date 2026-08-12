package perf.harness;

import com.intellij.openapi.ui.playback.PlaybackContext;
import com.intellij.openapi.ui.playback.commands.AbstractCommand;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.jetbrains.performancePlugin.CommandProvider;
import com.jetbrains.performancePlugin.CreateCommand;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Black-box whole-IDE heap and idle CPU markers for this local harness. */
public final class HarnessMetricCommandProvider implements CommandProvider {
    private static final Logger LOG = Logger.getInstance(HarnessMetricCommandProvider.class);
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final com.sun.management.OperatingSystemMXBean OPERATING_SYSTEM = operatingSystem();
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9._-]+");

    public HarnessMetricCommandProvider() {
        // Resolve and warm both MXBean calls before any post-GC/idle marker.
        MEMORY.getHeapMemoryUsage();
        OPERATING_SYSTEM.getProcessCpuTime();
    }

    @Override
    public Map<String, CreateCommand> getCommands() {
        return Map.of(
            "%harnessHeapUsed", HeapUsedCommand::new,
            "%harnessIdleProcessCpu", IdleCpuCommand::new
        );
    }

    private static void emitTeamCity(String key, long value) {
        System.out.printf(
            "##teamcity[buildStatisticValue key='%s' value='%d']%n",
            key,
            value
        );
    }

    private static final class HeapUsedCommand extends AbstractCommand {
        private static final String PREFIX = "%harnessHeapUsed";
        private HeapUsedCommand(String text, int line) {
            super(text, line, false);
        }

        @Override
        protected Promise<Object> _execute(PlaybackContext context) {
            String label = validLabel(extractCommandArgument(PREFIX), "post_gc");
            // Do not allocate or format output until after this exact sample.
            MemoryUsage heap = MEMORY.getHeapMemoryUsage();
            long used = heap.getUsed();
            long committed = heap.getCommitted();
            long max = heap.getMax();
            LOG.info("PERF_HARNESS_METRIC type=heap label=" + label
                + " used_bytes=" + used
                + " committed_bytes=" + committed
                + " max_bytes=" + max);
            emitTeamCity("perfHarness." + label + ".heapUsedBytes", used);
            emitTeamCity("perfHarness." + label + ".heapCommittedBytes", committed);
            emitTeamCity("perfHarness." + label + ".heapMaxBytes", max);
            return Promises.resolvedPromise();
        }
    }

    private static final class IdleCpuCommand extends AbstractCommand {
        private static final String PREFIX = "%harnessIdleProcessCpu";

        private IdleCpuCommand(String text, int line) {
            super(text, line, false);
        }

        @Override
        protected Promise<Object> _execute(PlaybackContext context) {
            String[] arguments = extractCommandArgument(PREFIX).split("\\s+");
            String label = validLabel(arguments.length > 0 ? arguments[0] : "", "idle_30s");
            long durationMillis = parseDuration(arguments);
            // Warmed above; this read defines the nested measurement start.
            AsyncPromise<Object> result = new AsyncPromise<>();
            long wallStartNanos = System.nanoTime();
            long cpuStartNanos = OPERATING_SYSTEM.getProcessCpuTime();
            if (cpuStartNanos < 0) return Promises.rejectedPromise("processCpuTime is unavailable");
            int processors = Runtime.getRuntime().availableProcessors();
            AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                try {
                    long cpuEndNanos = OPERATING_SYSTEM.getProcessCpuTime();
                    long wallEndNanos = System.nanoTime();
                    if (cpuEndNanos < 0) {
                        result.setError("processCpuTime became unavailable");
                        return;
                    }
                    long cpuNanos = cpuEndNanos - cpuStartNanos;
                    long wallNanos = wallEndNanos - wallStartNanos;
                    LOG.info("PERF_HARNESS_METRIC type=idle_cpu label=" + label
                        + " requested_ms=" + durationMillis
                        + " wall_ns=" + wallNanos
                        + " process_cpu_ns=" + cpuNanos
                        + " available_processors=" + processors);
                    emitTeamCity("perfHarness." + label + ".processCpuNs", cpuNanos);
                    emitTeamCity("perfHarness." + label + ".wallNs", wallNanos);
                    emitTeamCity("perfHarness." + label + ".requestedMs", durationMillis);
                    emitTeamCity("perfHarness." + label + ".availableProcessors", processors);
                    result.setResult(null);
                } catch (Throwable exception) {
                    result.setError(exception);
                }
            }, durationMillis, TimeUnit.MILLISECONDS);
            return result;
        }

        private long parseDuration(String[] arguments) {
            long value = arguments.length < 2 ? 30_000L : Long.parseLong(arguments[1]);
            if (value < 1_000L || value > 120_000L) {
                throw new IllegalArgumentException("idle duration must be 1000..120000 ms");
            }
            return value;
        }
    }

    private static String validLabel(String raw, String fallback) {
        String value = raw == null || raw.isBlank() ? fallback : raw;
        if (!LABEL.matcher(value).matches()) {
            throw new IllegalArgumentException("metric label must match " + LABEL.pattern());
        }
        return value;
    }

    private static com.sun.management.OperatingSystemMXBean operatingSystem() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        if (!(bean instanceof com.sun.management.OperatingSystemMXBean result)) {
            throw new IllegalStateException("processCpuTime is unavailable");
        }
        return result;
    }
}
