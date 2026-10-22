package com.kbrag.api;

import com.kbrag.agent.ChatService;
import com.kbrag.agent.UsageTracker;
import com.kbrag.agent.model.Answer;
import com.kbrag.common.Result;
import com.kbrag.rag.ingest.IngestService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api")
public class ChatController {

    public record ChatRequest(String question) {
    }

    public record IngestRequest(String docId, String title, String content) {
    }

    private final ChatService chatService;
    private final IngestService ingestService;
    private final UsageTracker usageTracker;

    public ChatController(ChatService chatService, IngestService ingestService, UsageTracker usageTracker) {
        this.chatService = chatService;
        this.ingestService = ingestService;
        this.usageTracker = usageTracker;
    }

    @PostMapping("/chat")
    public Result<Answer> chat(@RequestBody ChatRequest request) {
        return Result.ok(chatService.ask(request.question()));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam("q") String question) {
        SseEmitter emitter = new SseEmitter(120_000L);
        chatService.stream(question).subscribe(
                token -> {
                    try {
                        emitter.send(SseEmitter.event().data(token, MediaType.TEXT_PLAIN));
                    } catch (IOException e) {
                        emitter.completeWithError(e);
                    }
                },
                emitter::completeWithError,
                emitter::complete
        );
        return emitter;
    }

    @PostMapping("/retrieve")
    public Result<List<Answer.Source>> retrieve(@RequestBody ChatRequest request) {
        return Result.ok(chatService.retrieveOnly(request.question()));
    }

    @PostMapping("/ingest")
    public Result<IngestService.IngestResult> ingest(@RequestBody IngestRequest request) {
        return Result.ok(ingestService.ingest(request.docId(), request.title(), request.content()));
    }

    @GetMapping("/stats")
    public Result<UsageTracker.Snapshot> stats() {
        return Result.ok(usageTracker.snapshot());
    }
}
