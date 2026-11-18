package ru.winkiz.lab4;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Executor termination can precede the final return from its worker Thread.run(). */
final class ThreadSupport {
    private ThreadSupport() { }

    static boolean shutdown(ExecutorService executor, List<Thread> threads, Duration timeout) {
        executor.shutdownNow();
        long start = System.nanoTime(), budget = timeout.toNanos();
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                long remaining = budget - (System.nanoTime() - start);
                if (remaining <= 0) return false;
                try { executor.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            // All factories have finished by this point; the tracked list cannot grow again.
            for (Thread thread : List.copyOf(threads)) {
                if (thread == Thread.currentThread()) continue;
                while (thread.isAlive()) {
                    long remaining = budget - (System.nanoTime() - start);
                    if (remaining <= 0) return false;
                    try { TimeUnit.NANOSECONDS.timedJoin(thread, remaining); }
                    catch (InterruptedException e) { interrupted = true; }
                }
            }
            return true;
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
