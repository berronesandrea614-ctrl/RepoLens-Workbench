# RepoLens 评测(Eval)

把"代码理解 Agent"的效果量化,覆盖四个维度。可复现:

```bash
# 前置:MySQL 容器 + 后端 8080 在跑、demo 仓库(repoId=1)已索引
python3 eval/run_eval.py     # 生成 eval/report.md
```

## 四个维度(对应面试/技术评审常问)

### D1 调用关系解析准确率(最硬)
- **问题**:调用图准不准?凭什么说类型推断比文本匹配强?
- **真值**:`groundtruth_callgraph.json` — demo 仓库的真实调用关系(同仓 7 + JDK 3 应解析到全限定;SpringApplication.run 应退回文本)。
- **指标**:解析到真实声明类的 precision / recall;文本匹配 baseline 对比。
- **算法**:查 `code_dependency` 表,confidence≥0.9 且含 `#` = 解析到全限定类型。
- **结果**:文本匹配 召回 0% → 类型推断 **召回 90% / 精确率 100%**;漏的 1 个(User#setCreatedAt)因源码 Instant 未 import 解析失败,可解释。

### D2 意图 / 相关性识别
- **问题**:用户问与仓库无关的东西怎么识别?
- **真值**:`groundtruth_retrieval.json` 里 `relevant` 字段(6 相关 + 5 无关)。
- **指标**:无关问题拒答准确率、相关问题放行率。靠系统的"无证据拒答"机制。
- **结果**:无关拒答 **100%(5/5)**、相关放行 **100%(6/6)**。

### D3 检索准确率
- **问题**:检索召回的代码准不准?
- **指标**:Recall@K / Precision@K / MRR(对相关问题,看期望文件是否在 topK)。
- **结果**:Recall@5 **0.83**、Precision@5 **0.67**、MRR **0.71**。

### D4 回答引用正确性
- **问题**:回答好不好、合不合预期?
- **指标**:回答引用的文件是否落在真值文件内(客观)。
- **结果**:**83%**。

## 离线检索评测 harness(Java,无需 live 基础设施)

`src/test/java/com/repolens/eval/RetrievalEvalHarness.java` —— 纯离线的检索指标 harness,读同一份真值 `groundtruth_retrieval.json`,算 **precision@k / recall@k(正例)** 和 **拒答准确率(负例)**,把逐条 hit/miss + 汇总表打到 stdout。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
mvn -o test -Dtest=RetrievalEvalHarness      # 打印报告
```

- **为什么不进 `mvn test` 默认门禁**:类名 `RetrievalEvalHarness` 不匹配 Surefire 默认 `*Test`/`*Tests` 规则,普通 `mvn test` 跳过,只有 `-Dtest=RetrievalEvalHarness` 显式选中才跑。它是评测不是单测。
- **默认离线 stub**:harness 通过函数式接口 `RetrievalRunner { List<String> filesFor(String query); }` 抽象检索边界。默认实现 `KeywordStubRunner` 是一个 demo 仓库的极简内存关键词索引(确定性、无 DB/Milvus),用来跑绿并演示指标计算——它会自然产生不完美 precision(如 `getUserById` 命中 Controller+Service → 0.5),对全部 OOD 负例返回空 → 拒答 100%。**指标计算是真的、可复用;只有 runner 是 stub。**
- **接 live runner**:把默认那行换成委托 `RagRetrievalService` 的 lambda 即可(见类 javadoc):
  ```java
  RetrievalRunner live = q -> ragRetrievalService
          .retrieve(repoId, userId, q, TOP_K)
          .getResults().stream()
          .map(RagChunkVO::getFilePath)
          .collect(Collectors.toList());
  ```
  文件比对走 basename,所以 live 服务返回的绝对 chunk 路径能对上真值里的短文件名。
- **默认 stub 报告**:Precision@5 avg ≈ 0.79、Recall@5 avg = 1.00、Refusal accuracy = 1.00 (5/5)。这些是 stub 索引下的数字,换 live runner 会得到真实检索的数字。

## 诚实局限(面试主动说,别被追问穿)
1. **当前是 mock LLM**:agent 决策固定、回答文本是模板,所以 D4 只客观评"引用正确性",**不评回答文本质量**。接真 LLM(DeepSeek/Qwen)后可加 **LLM-as-judge** 评回答质量。
2. **Milvus 未起,走关键词降级检索**:D3 数字是降级检索的,起 Milvus 走向量语义检索会更高;中文问题需 Milvus 才能命中。
3. **标注集规模小**(demo 仓库 5 类 13 方法、11 条问答标注):是方法论与可复现性的验证,不是大规模 benchmark。扩仓库 + 扩标注集可提升说服力。
4. **D1 是最可信的**:有客观真值、不依赖 LLM、纯确定性、可复现;precision=100% 不是刷出来的(漏召回也如实报告)。
