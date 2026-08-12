import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GenerateHarness {
    // Keep the fixture below Rainbow Brackets' default 5,000-line safeguard so
    // all competitors are active with their out-of-box settings.
    private static final int METHOD_COUNT = 240;
    private static final int MARKERS_PER_METHOD = 2;
    private static final int WARMUP_MOVES = 200;
    private static final int MEASURED_MOVES = 1_000;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: GenerateHarness <harness-root>");
        }
        Path root = Path.of(args[0]).toAbsolutePath();
        Path project = root.resolve("project");
        String fixture = fixture();
        int[] markerOffsets = markerOffsets(fixture);
        Files.createDirectories(project.resolve("src"));
        Files.writeString(
            project.resolve("src/BracketDense.java"),
            fixture,
            StandardCharsets.UTF_8
        );
        Files.writeString(
            root.resolve("workload.ijperf"),
            workload(markerOffsets),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            root.resolve("caret-only.ijperf"),
            caretOnly(markerOffsets, false),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            root.resolve("caret-near.ijperf"),
            caretOnly(markerOffsets, true),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            root.resolve("prime.ijperf"),
            prime(markerOffsets),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            root.resolve("metrics-smoke.ijperf"),
            metricsSmoke(markerOffsets),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            root.resolve("primary-suite.ijperf"),
            primarySuite(markerOffsets),
            StandardCharsets.UTF_8
        );
        Files.writeString(
            root.resolve("fixture-metadata.txt"),
            fixtureMetadata(fixture),
            StandardCharsets.UTF_8
        );
    }

    private static String fixture() {
        StringBuilder out = new StringBuilder(1_700_000);
        out.append("public final class BracketDense {\n");
        for (int method = 0; method < METHOD_COUNT; method++) {
            String id = String.format("%04d", method);
            out.append("  public int method").append(id).append("(int value) {\n")
                .append("    int total = value;\n")
                .append("    for (int i = 0; i < 8; i++) {\n")
                .append("      if ((i & 1) == 0) {\n")
                .append("        int[] values = new int[] {i, (i + 1), (i + 2), (i + 3)};\n")
                .append("        for (int item : values) {\n")
                .append("          total += ((item * 3) + (value / (i + 1))); // PERF_MARKER_")
                .append(id).append("_A\n")
                .append("          if (total > 100000) { total -= value; } else { total += i; }\n")
                .append("        }\n")
                .append("      } else {\n")
                .append("        while (total < value + 32) {\n")
                .append("          total += ((i + value) * (i + 1)); // PERF_MARKER_")
                .append(id).append("_B\n")
                .append("          if ((total & 3) == 0) { break; }\n")
                .append("        }\n")
                .append("      }\n")
                .append("    }\n")
                .append("    return total;\n")
                .append("  }\n\n");
        }
        out.append("}\n");
        return out.toString();
    }

    private static int[] markerOffsets(String fixture) {
        int[] offsets = new int[METHOD_COUNT * MARKERS_PER_METHOD];
        for (int method = 0; method < METHOD_COUNT; method++) {
            String id = String.format("%04d", method);
            for (int branch = 0; branch < MARKERS_PER_METHOD; branch++) {
                String marker = "PERF_MARKER_" + id + '_' + (branch == 0 ? 'A' : 'B');
                int offset = fixture.indexOf(marker);
                if (offset < 0) {
                    throw new IllegalStateException("missing marker: " + marker);
                }
                // Move into the executable statement immediately before the marker.
                offsets[method * MARKERS_PER_METHOD + branch] = offset - 3;
            }
        }
        return offsets;
    }

    private static String prime(int[] markerOffsets) {
        return "%waitForSmart\n"
            + "%openFile src/BracketDense.java\n"
            + "%sleep 3000\n"
            + "%goto " + markerOffsets[(METHOD_COUNT / 2) * MARKERS_PER_METHOD] + "\n"
            + "%sleep 1000\n"
            + "%takeScreenshot active-brackets\n"
            + "%exitApp\n";
    }

    private static String workload(int[] markerOffsets) {
        StringBuilder out = new StringBuilder(500_000);
        out.append("%waitForSmart\n")
            .append("%openFile src/BracketDense.java\n")
            .append("%sleep 3000\n");

        appendMoves(out, markerOffsets, WARMUP_MOVES, 17);
        out.append("%sleep 1000\n")
            .append("%startProfile caret-workload\n");
        appendMoves(out, markerOffsets, MEASURED_MOVES, 73);
        out.append("%stopProfile\n")
            .append("%sleep 1000\n")
            .append("%memoryDump\n")
            .append("%exitApp\n");
        return out.toString();
    }

    private static String metricsSmoke(int[] markerOffsets) {
        return "%waitForSmart\n"
            + "%openFile src/BracketDense.java\n"
            + "%sleep 5000\n"
            + "%goto " + markerOffsets[(METHOD_COUNT / 2) * MARKERS_PER_METHOD] + "\n"
            + "%sleep 5000\n"
            + "%waitForEDTQueueUnstuck\n"
            + "%performJBRFullGC\n"
            + "%harnessHeapUsed post_gc\n"
            + "%sleep 5000\n"
            + "%waitForEDTQueueUnstuck\n"
            + "%harnessIdleProcessCpu idle_30s 30000\n"
            + "%exitApp\n";
    }

    private static String primarySuite(int[] markerOffsets) {
        StringBuilder out = new StringBuilder(70_000);
        int center = (METHOD_COUNT / 2) * MARKERS_PER_METHOD;
        out.append("%waitForSmart\n")
            .append("%openFile src/BracketDense.java\n")
            .append("%sleep 45000\n")
            .append("%goto ").append(markerOffsets[center]).append('\n')
            .append("%waitForEDTQueueUnstuck\n")
            .append("%sleep 5000\n")
            .append("%performJBRFullGC\n")
            .append("%harnessHeapUsed post_gc\n")
            .append("%harnessIdleProcessCpu idle_30s 30000\n");

        appendNearMoves(out, markerOffsets, WARMUP_MOVES);
        out.append("%waitForEDTQueueUnstuck\n")
            .append("%sleep 1000\n")
            .append("%startProfile caret-near\n");
        appendNearMoves(out, markerOffsets, MEASURED_MOVES);
        out.append("%stopProfile\n");

        appendMoves(out, markerOffsets, WARMUP_MOVES, 17);
        out.append("%waitForEDTQueueUnstuck\n")
            .append("%sleep 1000\n")
            .append("%startProfile caret-far\n");
        appendMoves(out, markerOffsets, MEASURED_MOVES, 73);
        out.append("%stopProfile\n")
            .append("%exitApp\n");
        return out.toString();
    }

    private static String caretOnly(int[] markerOffsets, boolean near) {
        StringBuilder out = new StringBuilder(30_000);
        out.append("%waitForSmart\n")
            .append("%openFile src/BracketDense.java\n")
            .append("%sleep 3000\n");
        if (near) {
            appendNearMoves(out, markerOffsets, WARMUP_MOVES);
        } else {
            appendMoves(out, markerOffsets, WARMUP_MOVES, 17);
        }
        out.append("%sleep 1000\n")
            .append("%startProfile ")
            .append(near ? "caret-near" : "caret-far")
            .append('\n');
        if (near) {
            appendNearMoves(out, markerOffsets, MEASURED_MOVES);
        } else {
            appendMoves(out, markerOffsets, MEASURED_MOVES, 73);
        }
        out.append("%stopProfile\n")
            .append("%exitApp\n");
        return out.toString();
    }

    private static void appendNearMoves(StringBuilder out, int[] markerOffsets, int count) {
        int center = (METHOD_COUNT / 2) * MARKERS_PER_METHOD;
        int[] offsets = {
            markerOffsets[center] - 50,
            markerOffsets[center] - 30,
            markerOffsets[center] - 10,
            markerOffsets[center],
            markerOffsets[center] + 15,
            markerOffsets[center] + 35,
            markerOffsets[center + 1] - 25,
            markerOffsets[center + 1],
        };
        for (int index = 0; index < count; index++) {
            out.append("%goto ").append(offsets[index % offsets.length]).append('\n');
        }
    }

    private static void appendMoves(StringBuilder out, int[] markerOffsets, int count, int stride) {
        int markerCount = markerOffsets.length;
        for (int index = 0; index < count; index++) {
            int marker = Math.floorMod(index * stride, markerCount);
            out.append("%goto ").append(markerOffsets[marker]).append('\n');
        }
    }

    private static String fixtureMetadata(String fixture) {
        long lines = fixture.lines().count();
        long openerCount = fixture.chars()
            .filter(character -> character == '(' || character == '[' || character == '{')
            .count();
        long blocks = (lines + 255L) / 256L;
        long blockLeaves = 1L;
        while (blockLeaves < Math.max(1L, blocks)) {
            blockLeaves <<= 1;
        }
        long guideIndexPayloadBytes = lines * Integer.BYTES + blockLeaves * 2L * Long.BYTES;
        return "bytes=" + fixture.getBytes(StandardCharsets.UTF_8).length + '\n'
            + "lines=" + lines + '\n'
            + "syntactic_pair_upper_bound=" + openerCount + '\n'
            + "pending_open_upper_bound=" + openerCount + '\n'
            + "guide_index_payload_bytes=" + guideIndexPayloadBytes + '\n'
            + "bpg_pair_capacity=100000\n"
            + "bpg_pending_open_capacity=50000\n"
            + "bpg_guide_index_capacity_bytes=4194304\n"
            + "rainbow_default_line_safeguard=5000\n";
    }
}
