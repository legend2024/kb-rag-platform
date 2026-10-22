package com.kbrag.rag.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 启动时加载 classpath:docs 下的示例知识库文档。
 * 生产环境应改为从对象存储 / 文档管理平台同步。
 */
@Component
public class KnowledgeLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLoader.class);

    private final IngestService ingestService;

    public KnowledgeLoader(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:docs/*.md");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String docId = filename.replaceFirst("\\.md$", "");
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                ingestService.ingest(docId, docId, content);
            }
            log.info("knowledge base loaded, total chunks={}", ingestService.indexSize());
        } catch (Exception e) {
            log.error("load knowledge base failed", e);
        }
    }
}
