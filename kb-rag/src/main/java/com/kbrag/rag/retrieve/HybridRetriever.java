package com.kbrag.rag.retrieve;

import com.kbrag.rag.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 混合检索：向量召回 + BM25 召回 -> RRF 融合 -> LLM 精排。
 * 两路召回并行，任一路失败都不影响整体链路。
 */
@Component
public class HybridRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final ObjectProvider<ChunkReranker> rerankerProvider;
    private final RagProperties props;

    public HybridRetriever(VectorStore vectorStore, Bm25Index bm25Index,
                           ObjectProvider<ChunkReranker> rerankerProvider, RagProperties props) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.rerankerProvider = rerankerProvider;
        this.props = props;
    }

    public List<ScoredChunk> retrieve(String query) {
        RagProperties.Retrieve cfg = props.getRetrieve();

        List<ScoredChunk> vectorHits = vectorSearch(query, cfg.getVectorTopK());
        List<ScoredChunk> bm25Hits = bm25Index.search(query, cfg.getBm25TopK());

        List<ScoredChunk> fused = RrfFusion.fuse(List.of(vectorHits, bm25Hits), cfg.getRrfK());
        log.debug("retrieve vector={} bm25={} fused={}", vectorHits.size(), bm25Hits.size(), fused.size());

        int candidates = Math.min(cfg.getRerankCandidates(), fused.size());
        if (candidates == 0) {
            return List.of();
        }
        List<ScoredChunk> head = fused.subList(0, candidates);
        ChunkReranker reranker = rerankerProvider.getIfAvailable();
        if (!props.getRerank().isEnabled() || reranker == null) {
            return limit(head, cfg.getFinalTopK());
        }
        return reranker.rerank(query, head, cfg.getFinalTopK());
    }

    private List<ScoredChunk> limit(List<ScoredChunk> list, int topK) {
        return list.size() > topK ? new ArrayList<>(list.subList(0, topK)) : list;
    }

    private List<ScoredChunk> vectorSearch(String query, int topK) {
        try {
            SearchRequest request = SearchRequest.builder().query(query).topK(topK).build();
            List<Document> documents = vectorStore.similaritySearch(request);
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }
            return documents.stream().map(d -> {
                Object chunkId = d.getMetadata().get("chunkId");
                return new ScoredChunk(
                        chunkId == null ? d.getId() : chunkId.toString(),
                        d.getText(), d.getMetadata(), 0.0, "vector");
            }).toList();
        } catch (Exception e) {
            log.warn("vector search failed, degrade to bm25 only: {}", e.getMessage());
            return List.of();
        }
    }
}
