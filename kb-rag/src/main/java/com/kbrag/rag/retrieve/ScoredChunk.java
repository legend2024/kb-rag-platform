package com.kbrag.rag.retrieve;

import java.util.Map;

public record ScoredChunk(String chunkId, String text, Map<String, Object> metadata, double score, String source) {

    public String title() {
        Object t = metadata == null ? null : metadata.get("title");
        return t == null ? "" : t.toString();
    }

    public ScoredChunk withScore(double newScore, String newSource) {
        return new ScoredChunk(chunkId, text, metadata, newScore, newSource);
    }
}
