package com.kbrag.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kb.rag")
public class RagProperties {

    private final Chunk chunk = new Chunk();
    private final Retrieve retrieve = new Retrieve();
    private final Rerank rerank = new Rerank();

    public Chunk getChunk() { return chunk; }
    public Retrieve getRetrieve() { return retrieve; }
    public Rerank getRerank() { return rerank; }

    public static class Chunk {
        private int maxChars = 600;
        private int overlapChars = 120;
        private boolean prependHeading = true;
        public int getMaxChars() { return maxChars; }
        public void setMaxChars(int v) { this.maxChars = v; }
        public int getOverlapChars() { return overlapChars; }
        public void setOverlapChars(int v) { this.overlapChars = v; }
        public boolean isPrependHeading() { return prependHeading; }
        public void setPrependHeading(boolean v) { this.prependHeading = v; }
    }

    public static class Retrieve {
        private int vectorTopK = 24;
        private int bm25TopK = 24;
        private int finalTopK = 6;
        private int rrfK = 60;
        private int rerankCandidates = 12;
        public int getVectorTopK() { return vectorTopK; }
        public void setVectorTopK(int v) { this.vectorTopK = v; }
        public int getBm25TopK() { return bm25TopK; }
        public void setBm25TopK(int v) { this.bm25TopK = v; }
        public int getFinalTopK() { return finalTopK; }
        public void setFinalTopK(int v) { this.finalTopK = v; }
        public int getRrfK() { return rrfK; }
        public void setRrfK(int v) { this.rrfK = v; }
        public int getRerankCandidates() { return rerankCandidates; }
        public void setRerankCandidates(int v) { this.rerankCandidates = v; }
    }

    public static class Rerank {
        /** dashscope = 专用重排模型；llm = 用 chat 模型逐个打分（贵、慢，仅作对照） */
        private String provider = "dashscope";
        private boolean enabled = true;
        private String model = "qwen3.7-text-rerank";
        private String baseUrl = "https://dashscope.aliyuncs.com";
        private String path = "/api/v1/services/rerank/text-rerank/text-rerank";
        private String apiKey = "";
        private int timeoutMs = 3000;

        public String getProvider() { return provider; }
        public void setProvider(String v) { this.provider = v; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getPath() { return path; }
        public void setPath(String v) { this.path = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int v) { this.timeoutMs = v; }
    }
}
