package net.sievert.modularmobai.gametest.util;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import net.sievert.modularmobai.Constants;
import net.sievert.modularmobai.gametest.GameTestTuning;

import java.util.Locale;

/**
 * Confines the server thread of a test worker to the processors the build asked for, leaving every other thread of the
 * process free to run anywhere.
 *
 * <p>A worker is bound by its one server thread, and on a chip with two kinds of core Windows parks that thread on an
 * efficiency core because it runs at below normal priority; confining the whole process to the performance cores is what
 * the build does about it, and doubles a worker's throughput. The cost of that is the rest of the process: the collector,
 * the chunk workers, netty and the compiler threads are shut out of twelve efficiency cores that sit idle while eight
 * performance ones carry everything. Pinning this one thread instead is the same cure with none of that cost, if it
 * measures as well.
 *
 * <p>Windows sets a thread's affinity through {@code SetThreadAffinityMask}, and a Java thread exposes no native handle,
 * so the thread has to ask for its own: {@code GetCurrentThread} returns a pseudo handle that means "whoever is asking".
 * That is why this is called from a server tick rather than from the build. The game already ships JNA, as one of the
 * libraries Mojang's own hardware reporting pulls in, so reaching the call needs no new dependency.
 *
 * <p>Everything here is best effort and Windows only. A worker that cannot be pinned runs exactly as it did before, so a
 * missing library or a refused call is worth a line in the log and nothing more.
 */
public final class ServerThreadAffinity {

    /**
     * The two calls this needs, declared here rather than taken from JNA's own {@code Kernel32}: that one has
     * {@code GetCurrentThread} but not {@code SetThreadAffinityMask}, and declaring both keeps this to the core library
     * that is certain to be on the class path.
     *
     * <p>The mask is a Java {@code long} where Windows has a {@code DWORD_PTR}, which is the same sixty four bits on the
     * only platform this runs on.
     */
    private interface Kernel32 extends StdCallLibrary {

        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

        Pointer GetCurrentThread();

        long SetThreadAffinityMask(Pointer thread, long mask);

        int GetCurrentProcessorNumber();
    }

    /** Set once the attempt has been made, so a tick hook can call this every tick and pay for it once. */
    private static boolean settled;

    private ServerThreadAffinity() {}

    /**
     * Pins the calling thread to the mask the build asked for, if it asked for one. Call this from the server thread, and
     * only from there: the mask applies to whoever calls it, and nothing here is synchronised because the one caller is
     * the one thread. Every call after the first is a field read.
     */
    public static void pinCallingThread() {

        if (settled) {

            return;
        }

        settled = true;

        final long mask = GameTestTuning.serverThreadCores();

        if (mask == 0L || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {

            return;
        }

        try {

            final Kernel32 kernel32 = Kernel32.INSTANCE;
            final int before = kernel32.GetCurrentProcessorNumber();
            final long previous = kernel32.SetThreadAffinityMask(kernel32.GetCurrentThread(), mask);

            if (previous == 0L) {

                Constants.LOG.warn("Could not pin {} to processor mask 0x{}: the call was refused",
                        Thread.currentThread().getName(), Long.toHexString(mask));
                return;
            }

            // Windows reschedules the thread at once when its current processor is no longer allowed, so the processor
            // this reports is already the one the pin chose. Both are logged because the pair is the evidence that the
            // pin did anything: an unpinned server thread reports one of the efficiency cores here.
            Constants.LOG.info("Pinned {} to processor mask 0x{}, from 0x{}; it was on processor {} and is now on {}",
                    Thread.currentThread().getName(), Long.toHexString(mask), Long.toHexString(previous),
                    before, kernel32.GetCurrentProcessorNumber());
        }

        // Errors as well as exceptions: a class path without JNA fails to link rather than throwing.
        catch (Throwable unavailable) {

            Constants.LOG.info("Not pinning {}: {}", Thread.currentThread().getName(), unavailable);
        }
    }
}
