package com.kbrag.agent;

import com.kbrag.agent.model.Answer;
import com.kbrag.rag.retrieve.HybridRetriever;
import com.kbrag.rag.retrieve.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final String SYSTEM_PROMPT = """
            你是企业知识库助手。请严格依据【参考资料】回答用户问题。

            规则：
            1. 只使用参考资料中的信息作答，不要引入外部知识。
            2. 如果参考资料不足以回答，直接回答“知识库中未找到相关信息”，不要猜测。
            3. 回答要具体，涉及数字、期限、流程时原样引用。
            4. 不要提及“参考资料”这个词，直接给结论。

            【参考资料】
            %s
            """;

    private static final String NO_CONTEXT = "（未检索到相关内容）";

    private final ChatClient chatClient;
    private final HybridRetriever retriever;
    private final UsageTracker usageTracker;

    public ChatService(ChatClient.Builder builder, HybridRetriever retriever, UsageTracker usageTracker) {
        this.chatClient = builder.build();
        this.retriever = retriever;
        this.usageTracker = usageTracker;
    }

    public Answer ask(String question) {
        long start = System.currentTimeMillis();
        List<ScoredChunk> chunks = retriever.retrieve(question);

        ChatResponse response = chatClient.prompt()
                .system(buildSystemPrompt(chunks))
                .user(question)
                .call()
                .chatResponse();

        long elapsed = System.currentTimeMillis() - start;
        String text = response == null || response.getResult() == null
                ? ""
                : response.getResult().getOutput().getText();
        Answer.Usage usage = extractUsage(response);
        usageTracker.record(usage.promptTokens(), usage.completionTokens());

        log.info("qa done costMs={} tokens={} sources={}", elapsed, usage.totalTokens(), chunks.size());
        return new Answer(text, toSources(chunks), elapsed, usage);
    }

    public Flux<String> stream(String question) {
        List<ScoredChunk> chunks = retriever.retrieve(question);
        return chatClient.prompt()
                .system(buildSystemPrompt(chunks))
                .user(question)
                .stream()
                .content();
    }

    public List<Answer.Source> retrieveOnly(String question) {
        return toSources(retriever.retrieve(question));
    }

    private String buildSystemPrompt(List<ScoredChunk> chunks) {
        if (chunks.isEmpty()) {
            return SYSTEM_PROMPT.formatted(NO_CONTEXT);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            ScoredChunk chunk = chunks.get(i);
            sb.append('[').append(i + 1).append("] ")
              .append(chunk.title()).append(" / ").append(chunk.text()).append("\n\n");
        }
        return SYSTEM_PROMPT.formatted(sb);
    }

    private Answer.Usage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return Answer.Usage.EMPTY;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return Answer.Usage.EMPTY;
        }
        Integer prompt = usage.getPromptTokens();
        Integer completion = usage.getCompletionTokens();
        int p = prompt == null ? 0 : prompt;
        int c = completion == null ? 0 : completion;
        return new Answer.Usage(p, c, p + c);
    }

    private List<Answer.Source> toSources(List<ScoredChunk> chunks) {
        return chunks.stream()
                .map(c -> new Answer.Source(c.chunkId(), c.title(),
                        String.valueOf(c.metadata().getOrDefault("section", "")),
                        c.text(),
                        Math.round(c.score() * 10000) / 10000.0, c.source()))
                .toList();
    }
}
