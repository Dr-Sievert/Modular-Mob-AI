package net.sievert.modularmobai.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class GameTestBenchmark {

    private GameTestBenchmark() {}

    private static long arenaTicks;
    private static int arenas;

    /**
     * Called by the duration collector once the whole suite has reported.
     */
    public static synchronized void recordArenas(long totalTicks, int count) {

        arenaTicks = totalTicks;
        arenas = count;
    }

    /**
     * Called when the run finishes, with what the server itself did.
     */
    public static synchronized void report(long serverTicks, double seconds) {

        // The suite is over, so whatever a brain has been collecting goes over to the training side now, while this is
        // still on the server thread and the process is still alive.
        net.sievert.modularmobai.brain.Brains.close();

        if (serverTicks <= 0L || seconds <= 0.0D) {

            return;
        }

        System.out.println("========= Game test benchmark =========");
        System.out.println(line("server ticks", "%,d in %.2f s", serverTicks, seconds));

        if (arenas > 0) {

            // A batch holds fifty arenas unless that was overridden, and every server tick offers that many slots. The
            // ones no fight was using are dead.
            final long slots = Math.min(GameTestTuning.batchSize() > 0 ? GameTestTuning.batchSize() : 50, arenas);
            final double dead = 1.0D - arenaTicks / (double) (serverTicks * slots);

            System.out.println(line("arena ticks", "%,d (%,.1f per second)", arenaTicks, arenaTicks / seconds));
            System.out.println(line("dead ticks", "%.1f%% of %,d batch slots", dead * 100.0D, slots));
        }

        if (ArenaRecorder.rows() > 0L) {

            System.out.println(line("data rows", "%,d captured", ArenaRecorder.rows()));
        }

        System.out.println("======================================");

        ArenaRecorder.close();

        writeForCoordinator(serverTicks, seconds);
    }

    private static String line(String label, String format, Object... arguments) {

        return "  %-13s%s".formatted(label, String.format(Locale.ROOT, format, arguments));
    }

    /**
     * When this process is one worker of a parallel run, hands its server side totals to whoever started it so the run can
     * be reported as a whole. The durations were already written; these go on the end as comments.
     */
    private static void writeForCoordinator(long serverTicks, double seconds) {

        final String path = GameTestTuning.statsFile();

        if (path == null) {

            return;
        }

        try {

            Files.writeString(
                    Path.of(path),
                    String.format(Locale.ROOT, "#serverTicks %d%n#seconds %.3f%n", serverTicks, seconds),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND
            );
        }

        catch (IOException exception) {

            System.err.println("[GameTestBenchmark] Could not append to " + path + ": " + exception);
        }
    }
}
