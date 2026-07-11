# RepoLens 评测报告

> 可复现:`python3 eval/run_eval.py`(需后端 8080 + MySQL 容器)。标注集见 eval/groundtruth_*.json。

## D1 调用关系解析准确率(类型推断 vs 文本匹配)
```json
{
  "dimension": "D1 调用关系解析准确率(类型推断 vs 文本匹配)",
  "ground_truth_should_resolve": 10,
  "baseline_text_match": {
    "qualified_resolutions": 0,
    "recall": 0.0,
    "note": "纯文本匹配只输出变量名级目标(如 userService.getUserById),结构上无法解析到声明类,全限定解析数=0"
  },
  "upgraded_type_inference": {
    "qualified_resolutions": 9,
    "precision": 1.0,
    "recall": 0.9,
    "fallback_to_text_correct": "1/1"
  },
  "improvement": "调用'解析到真实声明类'的召回:0%% → 90%%;精确率 100%%",
  "explainable_miss": [
    "com.example.demo.entity.User#setCreatedAt"
  ],
  "miss_reason": "User#setCreatedAt 未解析:demo 源码该处实参 Instant.now() 的 Instant 未 import,该语句类型解析失败 → 可解释的真实局限",
  "raw_resolved": [
    "com.example.demo.entity.User#getId",
    "com.example.demo.entity.User#setId",
    "com.example.demo.repository.UserRepository#findById",
    "com.example.demo.repository.UserRepository#save",
    "com.example.demo.service.UserService#createUser",
    "com.example.demo.service.UserService#getUserById",
    "java.util.Map#get",
    "java.util.Map#put",
    "java.util.concurrent.atomic.AtomicLong#incrementAndGet"
  ],
  "raw_text_fallback": [
    "SpringApplication.run"
  ]
}
```

## D3 检索准确率
```json
{
  "recall@5": 0.833,
  "precision@5": 0.667,
  "MRR": 0.708,
  "n_relevant_queries": 6
}
```

## D2 意图/相关性识别(无关问题拒答)
```json
{
  "refusal_accuracy_on_irrelevant": 1.0,
  "answer_rate_on_relevant": 1.0,
  "n_irrelevant": 5,
  "n_relevant": 6
}
```

## D4 回答引用正确性(客观)
```json
{
  "citation_in_groundtruth_rate": 0.833,
  "n_evaluated": 6,
  "note": "当前 mock LLM 回答文本为模板,故只客观评测'引用是否落在真值文件内',不评文本质量;接真 LLM 后可加 LLM-as-judge 评回答质量"
}
```
