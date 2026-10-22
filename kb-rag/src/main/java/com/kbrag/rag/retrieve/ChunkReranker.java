package com.kbrag.rag.retrieve;

import java.util.List;

/**
 * 精排抽象。实现必须保证：失败时返回原始顺序，绝不抛出异常中断检索链路。
 */
public interface ChunkReranker {

    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK);
}
