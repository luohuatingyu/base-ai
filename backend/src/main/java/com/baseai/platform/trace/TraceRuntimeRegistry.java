package com.baseai.platform.trace;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TraceRuntimeRegistry {
    private final ConcurrentHashMap<String, TraceRuntime> runtimes = new ConcurrentHashMap<>();

    /** 为新任务创建唯一运行时。 */
    public TraceRuntime create(String traceId) {
        TraceRuntime runtime = new TraceRuntime(traceId);
        TraceRuntime existing = runtimes.putIfAbsent(traceId, runtime);
        return existing == null ? runtime : existing;
    }

    public Optional<TraceRuntime> find(String traceId) { return Optional.ofNullable(runtimes.get(traceId)); }
    public Set<String> activeTraceIds() { return Set.copyOf(runtimes.keySet()); }
    public boolean cancel(String traceId) { return find(traceId).map(runtime -> { runtime.cancel(); return true; }).orElse(false); }
    public void remove(String traceId) { runtimes.remove(traceId); }
}
