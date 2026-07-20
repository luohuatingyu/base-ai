package com.baseai.platform.job;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobRuntimeRegistry {
    private final ConcurrentHashMap<String, JobRuntime> runtimes = new ConcurrentHashMap<>();

    /** 为新任务创建唯一运行时。 */
    public JobRuntime create(String jobId) {
        JobRuntime runtime = new JobRuntime(jobId);
        JobRuntime existing = runtimes.putIfAbsent(jobId, runtime);
        return existing == null ? runtime : existing;
    }

    public Optional<JobRuntime> find(String jobId) { return Optional.ofNullable(runtimes.get(jobId)); }
    public Set<String> activeJobIds() { return Set.copyOf(runtimes.keySet()); }
    public boolean cancel(String jobId) { return find(jobId).map(runtime -> { runtime.cancel(); return true; }).orElse(false); }
    public void remove(String jobId) { runtimes.remove(jobId); }
}
