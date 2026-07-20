package com.baseai.platform.logging;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class JobLogQueue {
    private static volatile BlockingQueue<JobLogRecord> queue = new ArrayBlockingQueue<>(10000);
    private JobLogQueue() {}

    /** 在 Logback 启动时设置统一有界日志队列。 */
    public static synchronized void configure(int capacity) {
        if (queue.isEmpty()) queue = new ArrayBlockingQueue<>(Math.max(100, capacity));
    }

    public static boolean offer(JobLogRecord record) { return queue.offer(record); }
    public static int drainTo(java.util.Collection<JobLogRecord> target, int maximum) { return queue.drainTo(target, maximum); }
}
