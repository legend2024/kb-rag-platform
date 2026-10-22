package com.kbrag.agent.model;

import java.util.List;

public record Answer(String answer, List<Source> sources, long elapsedMs, Usage usage) {

    public record Source(String chunkId, String title, String section, String text, double score, String from) {
    }

    public record Usage(int promptTokens, int completionTokens, int totalTokens) {
        public static final Usage EMPTY = new Usage(0, 0, 0);
    }
}
