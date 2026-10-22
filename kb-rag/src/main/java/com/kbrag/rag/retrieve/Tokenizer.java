package com.kbrag.rag.retrieve;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量分词：中文按字 bigram 切分，英文数字按整词切分。
 * 选择 bigram 而不是引入分词器，是为了让 BM25 索引零外部依赖、启动即用；
 * 代价是长词匹配精度略低于 HanLP，但实测在知识库场景召回损失可接受。
 */
public final class Tokenizer {

    private static final Pattern PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+|[a-zA-Z0-9]+");

    private Tokenizer() {
    }

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        Matcher m = PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String g = m.group();
            if (isCjk(g.charAt(0))) {
                if (g.length() == 1) {
                    tokens.add(g);
                } else {
                    for (int i = 0; i + 2 <= g.length(); i++) {
                        tokens.add(g.substring(i, i + 2));
                    }
                }
            } else {
                tokens.add(g);
            }
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FA5;
    }
}
