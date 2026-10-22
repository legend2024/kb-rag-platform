package com.kbrag.agent;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型调用计量。对外暴露 /api/stats，用于成本核算与容量评估。
 */
@Component
public class UsageTracker {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    public void record(int prompt, int completion) {
        requests.incrementAndGet();
        promptTokens.addAndGet(Math.max(prompt, 0));
        completionTokens.addAndGet(Math.max(completion, 0));
    }

    public Snapshot snapshot() {
        long p = promptTokens.get();
        long c = completionTokens.get();
        return new Snapshot(requests.get(), p, c, p + c);
    }

    public record Snapshot(long requests, long promptTokens, long completionTokens, long totalTokens) {
    }
}
