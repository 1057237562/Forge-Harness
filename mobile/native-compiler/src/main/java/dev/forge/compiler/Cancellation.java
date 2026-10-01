package dev.forge.compiler;

import java.io.InterruptedIOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Cancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> hooks = new CopyOnWriteArrayList<>();
    public boolean isCancelled() { return cancelled.get() || Thread.currentThread().isInterrupted(); }
    public void check() throws InterruptedIOException {
        if (isCancelled()) throw new InterruptedIOException("Build cancelled");
    }
    public void cancel() {
        cancelled.set(true);
        for (Runnable hook : hooks) hook.run();
    }
    public AutoCloseable onCancel(Runnable hook) {
        hooks.add(hook);
        if (cancelled.get()) hook.run();
        return () -> hooks.remove(hook);
    }
}
