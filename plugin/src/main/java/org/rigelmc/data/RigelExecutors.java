package org.rigelmc.data;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.NotNull;

/**
 * A small, dedicated thread pool every DAO runs its blocking JDBC calls on, so nothing
 * in the data layer ever touches Bukkit's main thread - see CONTRIBUTING.md's "never
 * call Bukkit API off the main thread" rule for why that boundary matters in the other
 * direction too (DAO callbacks must hop back to the main thread before touching a
 * {@code Player}/{@code World}/etc).
 */
public final class RigelExecutors {

    private RigelExecutors() {}

    @NotNull
    public static ExecutorService newDatabaseExecutor() {
        AtomicInteger count = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "RigelMCMod DB Worker #" + count.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(4, factory);
    }
}
