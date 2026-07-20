package com.baseai.platform.job;

import java.io.Closeable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public final class JobRuntime {
    private final JobCancellationToken token;
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final Set<Future<?>> futures = ConcurrentHashMap.newKeySet();
    private final Set<Closeable> closeables = ConcurrentHashMap.newKeySet();

    public JobRuntime(String jobId) { this.token = new JobCancellationToken(jobId); }
    public JobCancellationToken token() { return token; }
    public void registerThread(Thread thread) { threads.add(thread); }
    public void unregisterThread(Thread thread) { threads.remove(thread); }
    public void registerFuture(Future<?> future) { futures.add(future); }
    public void registerCloseable(Closeable closeable) { closeables.add(closeable); }

    /** 发出协作取消并中断已登记线程和 Future。 */
    public void cancel() {
        token.cancel();
        futures.forEach(future -> future.cancel(true));
        threads.forEach(Thread::interrupt);
        closeables.forEach(closeable -> {
            try { closeable.close(); } catch (Exception ignored) { }
        });
    }
}
