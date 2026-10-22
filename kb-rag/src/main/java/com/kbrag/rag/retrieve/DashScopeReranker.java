package com.kbrag.rag.retrieve;

import com.kbrag.rag.RagProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 百炼专用重排序模型（text-rerank）。相比用 chat 模型逐个打分：
 * 一次请求返回全部候选分数、延迟更低、单价低一个数量级。
 *
 * <p>接口走百炼原生路径 /api/v1/services/rerank/text-rerank/text-rerank，
 * 该协议不属于 OpenAI 兼容规范，因此单独实现而非复用 Spring AI 的 ChatClient。
 */
@Component
@ConditionalOnProperty(prefix = "kb.rag.rerank", name = "provider", havingValue = "dashscope", matchIfMissing = true)
public class DashScopeReranker implements ChunkReranker {

    private static final Logger log = LoggerFactory.getLogger(DashScopeReranker.class);
    private static final int MAX_DOC_CHARS = 1000;

    private final RagProperties props;
    private final RestClient client;

    public DashScopeReranker(RagProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(Math.max(props.getRerank().getTimeoutMs(), 500));
        this.client = RestClient.builder()
                .baseUrl(props.getRerank().getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        if (props.getRerank().getApiKey() == null || props.getRerank().getApiKey().isBlank()) {
            log.warn("rerank skipped: api key not configured, keep rrf order");
            return limit(candidates, topK);
        }
        try {
            List<String> documents = new ArrayList<>(candidates.size());
            for (ScoredChunk c : candidates) {
                String text = c.text();
                documents.add(text.length() > MAX_DOC_CHARS ? text.substring(0, MAX_DOC_CHARS) : text);
            }

            Map<String, Object> input = new HashMap<>();
            input.put("query", query);
            input.put("documents", documents);

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("return_documents", false);
            parameters.put("top_n", Math.min(topK, candidates.size()));

            Map<String, Object> body = new HashMap<>();
            body.put("model", props.getRerank().getModel());
            body.put("input", input);
            body.put("parameters", parameters);

            RerankResponse response = client.post()
                    .uri(props.getRerank().getPath())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.getRerank().getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(RerankResponse.class);

            if (response == null || response.output() == null || response.output().results() == null) {
                log.warn("rerank returned empty result, keep rrf order");
                return limit(candidates, topK);
            }

            List<ScoredChunk> ranked = new ArrayList<>(response.output().results().size());
            for (RerankResponse.Result r : response.output().results()) {
                if (r.index() == null || r.index() < 0 || r.index() >= candidates.size()) {
                    continue;
                }
                double score = r.score() == null ? 0.0 : r.score();
                ranked.add(candidates.get(r.index()).withScore(score, "rerank"));
            }
            if (ranked.isEmpty()) {
                return limit(candidates, topK);
            }
            ranked.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
            log.debug("rerank ok model={} in={} out={}", props.getRerank().getModel(), candidates.size(), ranked.size());
            return limit(ranked, topK);
        } catch (Exception e) {
            log.warn("rerank failed, keep rrf order: {}", e.getMessage());
            return limit(candidates, topK);
        }
    }

    private List<ScoredChunk> limit(List<ScoredChunk> list, int topK) {
        return list.size() > topK ? new ArrayList<>(list.subList(0, topK)) : list;
    }

    public record RerankResponse(Output output) {

        public record Output(List<Result> results) {
        }

        public record Result(Integer index, @JsonProperty("relevance_score") Double score) {
        }
    }
}
