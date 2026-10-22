package com.kbrag.rag.chunk;

import com.kbrag.rag.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 面向知识库文档的切块器。
 * 策略：先按 Markdown 标题切分语义段，超长段再滑窗切分并保留重叠，
 * 保证同一段落被截断时上下文不丢失。
 */
@Component
public class TextChunker {

    public record Segment(String chunkId, int seq, String section, String text) {
    }

    private final RagProperties props;

    public TextChunker(RagProperties props) {
        this.props = props;
    }

    public List<Segment> split(String docId, String content) {
        int maxChars = Math.max(props.getChunk().getMaxChars(), 80);
        int overlap = Math.min(props.getChunk().getOverlapChars(), maxChars / 2);

        List<Segment> segments = new ArrayList<>();
        int seq = 0;
        for (Section section : sections(content)) {
            String body = section.text();
            if (body.isBlank()) {
                continue;
            }
            if (body.length() <= maxChars) {
                segments.add(new Segment(docId + "#" + seq, seq, section.heading(), body));
                seq++;
                continue;
            }
            int start = 0;
            while (start < body.length()) {
                int end = Math.min(start + maxChars, body.length());
                String piece = body.substring(start, end);
                segments.add(new Segment(docId + "#" + seq, seq, section.heading(), piece));
                seq++;
                if (end >= body.length()) {
                    break;
                }
                // 至少前进 1 个字符，防止 overlap 配置过大导致死循环
                start = Math.max(end - overlap, start + 1);
            }
        }
        return segments;
    }

    private List<Section> sections(String content) {
        List<Section> result = new ArrayList<>();
        String heading = "";
        StringBuilder buf = new StringBuilder();
        for (String line : content.split("\\R")) {
            if (line.startsWith("#")) {
                if (!buf.isEmpty()) {
                    result.add(new Section(heading, buf.toString().trim()));
                    buf.setLength(0);
                }
                heading = line.replaceAll("^#+\\s*", "").trim();
            } else {
                buf.append(line).append('\n');
            }
        }
        if (!buf.isEmpty()) {
            result.add(new Section(heading, buf.toString().trim()));
        }
        return result;
    }

    private record Section(String heading, String text) {
    }
}
