package com.kbrag.rag.retrieve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 精排。粗排召回后让模型对候选逐条打分，解决向量检索偏向语义相似、
 * 但回答不直接命中的问题。
 *
 * <p>降级策略：模型超时、返回格式异常时直接放弃精排、沿用 RRF 顺序，
 * 保证 RAG 链路不因精排环节整体失败。
 */
@Component
@ConditionalOnProperty(prefix = "kb.rag.rerank", name = "provider", havingValue = "llm")
public class LlmReranker implements ChunkReranker {

    private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+)\\s*[:：]\\s*(\\d+(?:\\.\\d+)?)");

    private static final String PROMPT = """
            你是检索结果排序器。给定用户问题和若干候选文档片段，判断每段与问题的相关程度。
            评分 0-10：10 表示直接回答了问题，0 表示完全无关。只输出评分，不要解释。

            用户问题：%s

            候选片段：
            %s

            输出格式（每行一条，不要有多余文字）：
            序号:分数
            """;

    private final ChatClient chatClient;

    public LlmReranker(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topK) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        try {
            String response = chatClient.prompt().user(buildPrompt(query, candidates)).call().content();
            Map<Integer, Double> scores = parse(response);
            if (scores.isEmpty()) {
                log.warn("rerank returned unparsable response, fallback to rrf order");
                return limit(candidates, topK);
            }
            List<ScoredChunk> ranked = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                ranked.add(candidates.get(i).withScore(scores.getOrDefault(i, 0.0), "rerank"));
            }
            ranked.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
            return limit(ranked, topK);
        } catch (Exception e) {
            log.warn("rerank failed, fallback to rrf order: {}", e.getMessage());
            return limit(candidates, topK);
        }
    }

    private String buildPrompt(String query, List<ScoredChunk> candidates) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String text = candidates.get(i).text();
            sb.append(i).append(". ").append(text.length() > 300 ? text.substring(0, 300) : text).append('\n');
        }
        return PROMPT.formatted(query, sb);
    }

    private Map<Integer, Double> parse(String response) {
        Map<Integer, Double> scores = new HashMap<>();
        if (response == null) {
            return scores;
        }
        Matcher m = SCORE_PATTERN.matcher(response);
        while (m.find()) {
            scores.put(Integer.parseInt(m.group(1)), Double.parseDouble(m.group(2)));
        }
        return scores;
    }

    private List<ScoredChunk> limit(List<ScoredChunk> list, int topK) {
        return list.size() > topK ? new ArrayList<>(list.subList(0, topK)) : list;
    }
}
