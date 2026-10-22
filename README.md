# kb-rag-platform

企业知识库 RAG 问答平台：Spring Boot 3.5 + Spring AI 1.0 + pgvector，检索链路采用
**向量召回 + BM25 召回 → RRF 融合 → 专用重排模型精排** 四段式设计。

## 为什么这么设计

大部分 RAG demo 只做"向量检索 + 拼接 prompt"，在真实知识库上会遇到两个问题：

1. **专有名词、编号、精确数字查不准**。向量模型对"P0 响应 15 分钟"这类表达不敏感，
   语义相近但答案不命中。BM25 靠词频匹配，恰好补上这块。
2. **两路结果分数不可比**。向量输出余弦相似度，BM25 输出无界分数，直接加权需要归一化，
   而归一化参数对语料敏感。所以用 RRF 只用排名融合，规避分数可比性问题。

精排环节接百炼专用重排模型（text-rerank），一次请求返回全部候选分数，
比用 chat 模型逐个打分便宜一个数量级。可通过 `kb.rag.rerank.provider`
切换为 `llm`（对照用）或 `enabled=false` 关闭。
重排超时或返回异常时自动降级回 RRF 顺序，保证链路不整体失败。

## 架构

```
                    ┌──────────────────────────────┐
   HTTP / SSE  ───▶ │  kb-bootstrap  ChatController │
                    └───────────────┬──────────────┘
                                    │
                    ┌───────────────▼──────────────┐
                    │  kb-agent    ChatService      │  组装 prompt / 计量
                    └───────────────┬──────────────┘
                                    │
                    ┌───────────────▼──────────────┐
                    │  kb-rag    HybridRetriever    │
                    │                               │
                    │  ┌─────────┐   ┌───────────┐  │
                    │  │ Vector  │   │  BM25     │  │
                    │  │ pgvector│   │ 内存倒排  │  │
                    │  └────┬────┘   └─────┬─────┘  │
                    │       └──── RRF ─────┘        │
                    │             │                 │
                    │       Rerank (text-rerank)     │
                    └─────────────┬─────────────────┘
                                  │
                    ┌─────────────▼─────────────────┐
                    │ PostgreSQL + pgvector          │
                    └───────────────────────────────┘
```

## 模块说明

| 模块 | 职责 |
|---|---|
| kb-common | 统一返回体、异常处理 |
| kb-rag | 切块、BM25 索引、向量检索、RRF 融合、LLM 精排、文档入库 |
| kb-agent | 对话服务、prompt 组装、token 计量 |
| kb-bootstrap | 启动类、HTTP/SSE 接口、配置、示例知识库 |

依赖方向单向：bootstrap → agent → rag → common。

## 快速开始

前置：JDK 17、Maven 3.9+、Docker。

```bash
export DASHSCOPE_API_KEY=sk-xxxx    # 也支持 AI_API_KEY
./scripts/start.sh --build          # 首次：编译 + 起库 + 启动
```

启动后会自动加载 `kb-bootstrap/src/main/resources/docs` 下的 3 份示例文档并建索引，
日志里能看到 `knowledge base loaded, total chunks=N`。

之后只改代码不改配置，直接 `./scripts/start.sh` 即可。
注意：`application.yml` 是打进 jar 的，改了配置必须加 `--build` 重新编译。

## 脚本

### scripts/start.sh

一键启动。依次完成四件事：定位可用 JDK → 校验 API Key → 检查或编译 jar → 启动数据库并等待 healthy。

```bash
./scripts/start.sh                                 # 直接启动
./scripts/start.sh --build                         # 改了代码后重新编译再启动
./scripts/start.sh --server.port=18080             # 端口冲突时换端口
./scripts/start.sh --kb.rag.rerank.enabled=false   # 参数透传给应用
```

几点说明：

- **只有数据库跑在容器里**，应用本身是本地 `java -jar`
- macOS 的 `/usr/bin/java` 是个空壳，脚本会实际执行一次 `java -version` 来识别，并自动导出 `JAVA_HOME` 供 Maven 使用
- 检测到源码比 jar 新时会提示，但不会自动编译，避免每次启动都等编译

### scripts/check-rerank.sh

单独验证重排模型，**不需要启动应用、不访问数据库**，只发一次 HTTP 请求。

```bash
./scripts/check-rerank.sh                             # 内置用例
./scripts/check-rerank.sh "年假有几天"                   # 只给查询，用默认候选
./scripts/check-rerank.sh "一线城市出差住宿标准" \
  "一线城市住宿标准为每天600元" "员工年假满5年15天" "报销单须在30天内提交"

AI_RERANK_MODEL=gte-rerank ./scripts/check-rerank.sh   # 换模型做对比
./scripts/check-rerank.sh -h                           # 查看帮助
```

成功时输出：

```
重排结果（按分数从高到低）：
  score=0.9312   [0] 一线城市住宿标准为每天600元
  score=0.0087   [1] 员工年假满5年15天
  score=0.0031   [2] 报销单须在30天内提交
```

判断标准：能回答问题的片段分数应显著高于干扰项。把候选顺序打乱再跑一次，
若相关片段仍然排第一，才说明模型真的在判断内容，而不是按位置返回。

### eval/eval_recall.py

批量评测召回率，见下一节。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/chat | 同步问答，返回答案 + 引用片段 + token 消耗 |
| GET | /api/chat/stream?q= | SSE 流式问答 |
| POST | /api/retrieve | 只做检索，返回片段与来源（评测用） |
| POST | /api/ingest | 导入文档 |
| GET | /api/stats | 调用次数与 token 计量 |

```bash
curl -s -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"一线城市出差住宿标准是多少？"}'
```

## 召回率评测

```bash
python3 eval/eval_recall.py --base http://localhost:8080 --k 6
```

脚本对 `eval/golden_qa.jsonl` 中 12 条问题逐条检索，判断返回片段是否命中预设关键词组，
输出 Recall@K。**先跑出你自己的数字再写简历，不要抄别人的百分比。**

对比实验建议：把 `kb.rag.rerank.enabled` 设为 false，或在
`HybridRetriever` 里注释掉 BM25 那一路，各跑一次，就能得到三条曲线：
纯向量、向量+BM25、向量+BM25+精排。这组对比本身就是面试时的加分素材。

## 已知边界

- BM25 是内存索引，单机万级 chunk 可用；超过十万级应换 Elasticsearch，本类仅作降级。
- 中文用 bigram 切分，未接入分词器，长专有名词召回略低于 HanLP。
- 流式接口暂未统计 token 用量（OpenAI 兼容端点流式返回 usage 不稳定），
  计量只覆盖 `/api/chat`。
- 没有用户体系与多租户，所有文档共享一个知识库。

## 与开源项目的关系

模块划分（bootstrap / framework / rag / agent 分层）参考了
[nageoffer/ragent](https://github.com/nageoffer/ragent) 的工程组织方式，
仅作结构借鉴；本仓库代码为独立实现，检索链路（BM25 倒排索引、RRF 融合、LLM 精排）
未使用该项目代码。

若后续引入任何第三方源码，须在此处列明来源与许可证，并保留原始版权声明。

## 简历怎么写

不要写"基于 Spring AI 实现了 RAG 问答"，那是功能描述。写工程结论：

> 设计并实现企业知识库 RAG 检索链路：向量召回 + BM25 双路并行，经 RRF 排名融合后
> 接入 LLM 精排，构建 12 条带标注的评测集，Recall@6 由 __% 提升至 __%；
> 精排环节做降级兜底，模型超时时自动回落融合排序，保障可用性。

把 __ 换成 `eval/eval_recall.py` 跑出来的真实数字。

## Roadmap

- [ ] 引入 Redis 做多轮会话记忆
- [ ] 文档接入对象存储，支持 PDF / Word 解析
- [ ] 把 rag、agent 拆为独立 Spring Cloud 服务，接入 Nacos
- [ ] 检索链路加 Micrometer 埋点，输出 P99 延迟
