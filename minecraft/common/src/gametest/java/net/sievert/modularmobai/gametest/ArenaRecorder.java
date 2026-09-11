package net.sievert.modularmobai.gametest;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ArenaRecorder {

    private ArenaRecorder() {}

    /**
     * Positions are written in hundredths of a block and health in tenths, both as whole numbers.
     */
    private static final String HEADER =
            "arena,tick,villager_x,villager_y,villager_z,villager_health,vindicator_x,vindicator_y,vindicator_z,vindicator_health";

    private static final StringBuilder ROW = new StringBuilder(96);

    private static BufferedWriter writer;
    private static boolean unavailable;
    private static long rows;

    /**
     * Records the state of one arena on one tick. Positions are relative to the arena's own corner, so every arena reads
     * the same way no matter where in the world it was placed.
     */
    public static void record(
            int arena, long tick,
            double villagerX, double villagerY, double villagerZ, float villagerHealth,
            double vindicatorX, double vindicatorY, double vindicatorZ, float vindicatorHealth) {

        final BufferedWriter out = open();

        if (out == null) {

            return;
        }

        ROW.setLength(0);
        ROW.append(arena).append(',').append(tick);
        append(ROW, villagerX);
        append(ROW, villagerY);
        append(ROW, villagerZ);
        ROW.append(',').append(Math.round(villagerHealth * 10.0F));
        append(ROW, vindicatorX);
        append(ROW, vindicatorY);
        append(ROW, vindicatorZ);
        ROW.append(',').append(Math.round(vindicatorHealth * 10.0F));
        ROW.append('\n');

        try {

            out.append(ROW);
            rows++;
        }

        catch (IOException exception) {

            fail("Could not write a row", exception);
        }
    }

    public static long rows() {

        return rows;
    }

    /**
     * Flushes and closes the file. Called when the suite finishes.
     */
    public static synchronized void close() {

        if (writer == null) {

            return;
        }

        try {

            writer.close();
        }

        catch (IOException exception) {

            System.err.println("[ArenaRecorder] Could not close the capture file: " + exception);
        }

        writer = null;
    }

    private static void append(StringBuilder row, double value) {

        row.append(',').append(Math.round(value * 100.0D));
    }

    private static BufferedWriter open() {

        if (writer != null || unavailable) {

            return writer;
        }

        final String path = GameTestTuning.captureFile();

        if (path == null) {

            unavailable = true;
            return null;
        }

        try {

            final Path file = Path.of(path).toAbsolutePath();

            if (file.getParent() != null) {

                Files.createDirectories(file.getParent());
            }

            writer = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
            );

            writer.write(HEADER);
            writer.write('\n');

            System.out.println("[ArenaRecorder] Writing per tick data to " + file);
        }

        catch (IOException exception) {

            fail("Could not open " + path, exception);
        }

        return writer;
    }

    private static void fail(String message, IOException exception) {

        System.err.println("[ArenaRecorder] " + message + ": " + exception);
        unavailable = true;
        writer = null;
    }
}
