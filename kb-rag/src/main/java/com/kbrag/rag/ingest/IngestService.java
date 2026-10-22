package com.kbrag.rag.ingest;

import com.kbrag.rag.RagProperties;
import com.kbrag.rag.chunk.TextChunker;
import com.kbrag.rag.retrieve.Bm25Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    public record IngestResult(String docId, int chunks) {
    }

    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final TextChunker chunker;
    private final RagProperties props;
    private final Map<String, List<Bm25Index.Entry>> docEntries = new ConcurrentHashMap<>();

    public IngestService(VectorStore vectorStore, Bm25Index bm25Index,
                         TextChunker chunker, RagProperties props) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.chunker = chunker;
        this.props = props;
    }

    public IngestResult ingest(String docId, String title, String content) {
        List<TextChunker.Segment> segments = chunker.split(docId, content);
        if (segments.isEmpty()) {
            return new IngestResult(docId, 0);
        }

        List<Document> documents = new ArrayList<>(segments.size());
        List<Bm25Index.Entry> entries = new ArrayList<>(segments.size());

        for (TextChunker.Segment segment : segments) {
            String text = props.getChunk().isPrependHeading() && !segment.section().isBlank()
                    ? "[" + segment.section() + "] " + segment.text()
                    : segment.text();

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunkId", segment.chunkId());
            metadata.put("docId", docId);
            metadata.put("title", title);
            metadata.put("section", segment.section());
            metadata.put("seq", segment.seq());

            // pgvector 的主键列是 uuid，可读的 chunkId 不能直接当主键。
            // 用 nameUUIDFromBytes 从 chunkId 派生确定性 UUID：重复导入同一个 chunk 会得到相同主键，
            // 天然幂等，避免重复行。可读 ID 保留在 metadata.chunkId 里。
            String vectorId = UUID.nameUUIDFromBytes(
                    segment.chunkId().getBytes(StandardCharsets.UTF_8)).toString();
            documents.add(new Document(vectorId, text, metadata));
            entries.add(new Bm25Index.Entry(segment.chunkId(), text, metadata));
        }

        // 同名文档重复导入时先清理旧向量，避免知识库出现重复片段
        deleteQuietly(docId);
        vectorStore.add(documents);

        docEntries.put(docId, entries);
        bm25Index.rebuild(allEntries());

        log.info("ingested docId={} title={} chunks={} indexSize={}", docId, title, segments.size(), bm25Index.size());
        return new IngestResult(docId, segments.size());
    }

    public int indexSize() {
        return bm25Index.size();
    }

    private void deleteQuietly(String docId) {
        try {
            vectorStore.delete("docId == '" + docId + "'");
        } catch (Exception e) {
            log.debug("skip delete for docId={}: {}", docId, e.getMessage());
        }
    }

    private List<Bm25Index.Entry> allEntries() {
        List<Bm25Index.Entry> all = new ArrayList<>();
        docEntries.values().forEach(all::addAll);
        return all;
    }
}
