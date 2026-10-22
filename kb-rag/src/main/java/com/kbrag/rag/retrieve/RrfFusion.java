package com.kbrag.rag.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion。
 * 只用排名不用分数，天然规避向量相似度与 BM25 分数不可比的问题，
 * 因此不需要为两路检索分别做归一化。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    public static List<ScoredChunk> fuse(List<List<ScoredChunk>> rankedLists, int k) {
        Map<String, Double> fusedScore = new LinkedHashMap<>();
        Map<String, ScoredChunk> byId = new LinkedHashMap<>();

        for (List<ScoredChunk> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                ScoredChunk chunk = list.get(rank);
                byId.putIfAbsent(chunk.chunkId(), chunk);
                fusedScore.merge(chunk.chunkId(), 1.0 / (k + rank + 1), Double::sum);
            }
        }

        List<ScoredChunk> result = new ArrayList<>(fusedScore.size());
        fusedScore.forEach((id, score) -> result.add(byId.get(id).withScore(score, "rrf")));
        result.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return result;
    }
}
