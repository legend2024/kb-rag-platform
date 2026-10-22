package com.kbrag.rag.retrieve;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版 BM25 倒排索引。
 *
 * <p>知识库规模在万级 chunk 以内时，内存索引的召回质量与延迟都优于引入 ES，
 * 且少一个中间件依赖。真正的取舍在简历里必须讲清楚：超过十万 chunk
 * 需要迁移到 ES / OpenSearch，届时本类只作为降级方案保留。
 */
@Component
public class Bm25Index {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    public record Entry(String chunkId, String text, Map<String, Object> metadata) {
    }

    private Map<String, Map<String, Integer>> postings = Map.of();
    private Map<String, Integer> docLength = Map.of();
    private Map<String, String> docText = Map.of();
    private Map<String, Map<String, Object>> docMeta = Map.of();
    private double avgLength = 1.0;
    private int docCount = 0;

    /** 全量重建。知识库体量下重建成本远低于维护增量一致性。 */
    public synchronized void rebuild(List<Entry> entries) {
        Map<String, Map<String, Integer>> newPostings = new HashMap<>();
        Map<String, Integer> newDocLength = new HashMap<>();
        Map<String, String> newDocText = new HashMap<>();
        Map<String, Map<String, Object>> newDocMeta = new HashMap<>();
        long totalLength = 0;

        for (Entry entry : entries) {
            List<String> tokens = Tokenizer.tokenize(entry.text());
            newDocLength.put(entry.chunkId(), tokens.size());
            newDocText.put(entry.chunkId(), entry.text());
            newDocMeta.put(entry.chunkId(), entry.metadata());
            totalLength += tokens.size();

            Map<String, Integer> tf = new HashMap<>();
            for (String token : tokens) {
                tf.merge(token, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                newPostings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(entry.chunkId(), e.getValue());
            }
        }

        this.postings = newPostings;
        this.docLength = newDocLength;
        this.docText = newDocText;
        this.docMeta = newDocMeta;
        this.docCount = entries.size();
        this.avgLength = docCount == 0 ? 1.0 : (double) totalLength / docCount;
    }

    public synchronized List<ScoredChunk> search(String query, int topK) {
        if (docCount == 0) {
            return List.of();
        }
        List<String> terms = Tokenizer.tokenize(query).stream().distinct().toList();
        if (terms.isEmpty()) {
            return List.of();
        }

        Map<String, Double> scores = new HashMap<>();
        for (String term : terms) {
            Map<String, Integer> posting = postings.get(term);
            if (posting == null) {
                continue;
            }
            double df = posting.size();
            double idf = Math.log(1.0 + (docCount - df + 0.5) / (df + 0.5));
            for (Map.Entry<String, Integer> e : posting.entrySet()) {
                String chunkId = e.getKey();
                double tf = e.getValue();
                int dl = docLength.getOrDefault(chunkId, 1);
                double norm = tf + K1 * (1.0 - B + B * dl / avgLength);
                scores.merge(chunkId, idf * tf * (K1 + 1.0) / norm, Double::sum);
            }
        }

        List<ScoredChunk> result = new ArrayList<>(scores.size());
        scores.forEach((chunkId, score) -> result.add(
                new ScoredChunk(chunkId, docText.get(chunkId), docMeta.get(chunkId), score, "bm25")));
        result.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return result.size() > topK ? new ArrayList<>(result.subList(0, topK)) : result;
    }

    public synchronized int size() {
        return docCount;
    }

    public synchronized List<Entry> entries() {
        List<Entry> list = new ArrayList<>(docCount);
        docText.forEach((id, text) -> list.add(new Entry(id, text, docMeta.get(id))));
        return Collections.unmodifiableList(list);
    }
}
