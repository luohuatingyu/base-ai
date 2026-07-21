package com.baseai.platform.logging;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class JobLogQueue {
    private static volatile BlockingQueue<JobLogRecord> queue = new ArrayBlockingQueue<>(10000);
    private static final AtomicLong droppedCount = new AtomicLong();
    private JobLogQueue() {}

    /** 在 Logback 启动时设置统一有界日志队列。 */
    public static synchronized void configure(int capacity) {
        if (queue.isEmpty()) queue = new ArrayBlockingQueue<>(Math.max(100, capacity));
    }

    /** 非阻塞写入日志，队列满载时统计丢弃数量。 */
    public static boolean offer(JobLogRecord record) {
        boolean accepted = queue.offer(record);
        if (!accepted) droppedCount.incrementAndGet();
        return accepted;
    }

    /** 将数据库失败批次尽力放回队列，不能阻塞业务线程。 */
    public static int requeue(Iterable<JobLogRecord> records) {
        int accepted = 0;
        for (JobLogRecord record : records) {
            if (queue.offer(record)) accepted++;
            else droppedCount.incrementAndGet();
        }
        return accepted;
    }

    /** 读取并清零最近一次统计周期的丢弃数量。 */
    public static long drainDroppedCount() { return droppedCount.getAndSet(0); }

    public static int drainTo(java.util.Collection<JobLogRecord> target, int maximum) { return queue.drainTo(target, maximum); }
}
