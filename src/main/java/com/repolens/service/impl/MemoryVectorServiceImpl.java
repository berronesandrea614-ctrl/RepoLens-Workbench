package com.repolens.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.repolens.service.MemoryVectorService;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆向量索引服务的 Milvus 实现。
 *
 * Collection 命名：agent_memory_vec_{embeddingDimension}，随维度变化自动区分，
 * 避免切换 embedding 模型后维度不匹配的隐患（与 MilvusServiceImpl 同理）。
 *
 * Schema：memory_id (Int64, PK) + user_id (Int64) + repo_id (Int64) + embedding (FloatVector)。
 * 索引：IVF_FLAT + COSINE。
 *
 * 全部 public 方法均为失败安全：捕获所有异常，log.warn，返回 false/empty。
 */
@Slf4j
@Service
public class MemoryVectorServiceImpl implements MemoryVectorService {

    private static final String VECTOR_FIELD = "embedding";
    private static final int DEFAULT_NLIST = 128;

    @Value("${repolens.milvus.host:localhost}")
    private String host;

    @Value("${repolens.milvus.port:19530}")
    private int port;

    @Value("${repolens.embedding.dimension:384}")
    private int embeddingDimension;

    /** 延迟初始化的 Milvus 客户端（与 MilvusServiceImpl 相同的双重检查锁模式）。 */
    private volatile MilvusClientV2 milvusClient;

    /** 是否已完成 collection 创建/加载的确认，避免每次操作都调 has/create。 */
    private volatile boolean collectionEnsured;

    /** Collection 名称按维度派生，避免维度不匹配的隐患。 */
    private String collectionName() {
        return "agent_memory_vec_" + embeddingDimension;
    }

    // ------------------------------------------------------------------
    // Public fail-safe API
    // ------------------------------------------------------------------

    @Override
    public boolean upsertMemoryVector(Long memoryId, Long userId, Long repoId, float[] embedding) {
        if (memoryId == null || userId == null || repoId == null
                || embedding == null || embedding.length == 0) {
            return false;
        }
        try {
            ensureCollection();
            JsonObject row = new JsonObject();
            row.addProperty("memory_id", memoryId);
            row.addProperty("user_id", userId);
            row.addProperty("repo_id", repoId);
            JsonArray vec = new JsonArray();
            for (float v : embedding) {
                vec.add(v);
            }
            row.add(VECTOR_FIELD, vec);

            client().upsert(UpsertReq.builder()
                    .collectionName(collectionName())
                    .data(List.of(row))
                    .build());
            return true;
        } catch (Exception ex) {
            log.warn("MemoryVectorService.upsertMemoryVector failed, memoryId={}, err={}",
                    memoryId, trimMsg(ex));
            return false;
        }
    }

    @Override
    public List<MemoryVectorService.MemoryHit> searchSimilar(float[] queryVector, Long userId, Long repoId, int topK) {
        if (queryVector == null || queryVector.length == 0 || userId == null || repoId == null || topK <= 0) {
            return List.of();
        }
        try {
            ensureCollection();
            List<Float> vec = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                vec.add(v);
            }
            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("nprobe", 10);

            String filter = "user_id == " + userId + " && repo_id == " + repoId;
            SearchResp resp = client().search(SearchReq.builder()
                    .collectionName(collectionName())
                    .annsField(VECTOR_FIELD)
                    .filter(filter)
                    .topK(topK)
                    .searchParams(searchParams)
                    .outputFields(List.of("memory_id"))
                    .data(List.of(new FloatVec(vec)))
                    .build());

            return extractMemoryHits(resp);
        } catch (Exception ex) {
            log.warn("MemoryVectorService.searchSimilar failed, userId={}, repoId={}, err={}",
                    userId, repoId, trimMsg(ex));
            return List.of();
        }
    }

    @Override
    public void deleteMemoryVector(Long memoryId) {
        if (memoryId == null) {
            return;
        }
        try {
            ensureCollection();
            client().delete(DeleteReq.builder()
                    .collectionName(collectionName())
                    .filter("memory_id == " + memoryId)
                    .build());
        } catch (Exception ex) {
            log.warn("MemoryVectorService.deleteMemoryVector failed, memoryId={}, err={}",
                    memoryId, trimMsg(ex));
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * 确保 collection 与索引已就绪。失败时抛出异常（由 public 方法的 try/catch 捕获）。
     */
    private synchronized void ensureCollection() {
        if (collectionEnsured) {
            return;
        }
        MilvusClientV2 c = client();
        String name = collectionName();
        boolean exists = Boolean.TRUE.equals(c.hasCollection(HasCollectionReq.builder()
                .collectionName(name)
                .build()));
        if (!exists) {
            createCollection(c, name);
        }
        c.loadCollection(LoadCollectionReq.builder().collectionName(name).build());
        collectionEnsured = true;
    }

    private void createCollection(MilvusClientV2 client, String name) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .build();
        schema.addField(AddFieldReq.builder()
                .fieldName("memory_id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(false)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("user_id")
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("repo_id")
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(VECTOR_FIELD)
                .dataType(DataType.FloatVector)
                .dimension(embeddingDimension)
                .build());

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(name)
                .description("RepoLens agent memory vectors")
                .collectionSchema(schema)
                .build());

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("nlist", DEFAULT_NLIST);
        IndexParam vectorIndex = IndexParam.builder()
                .fieldName(VECTOR_FIELD)
                .indexName("idx_mem_embedding")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(extraParams)
                .build();
        client.createIndex(io.milvus.v2.service.index.request.CreateIndexReq.builder()
                .collectionName(name)
                .indexParams(List.of(vectorIndex))
                .build());

        log.info("MemoryVectorService created collection={}, dimension={}", name, embeddingDimension);
    }

    /** 延迟初始化 Milvus 客户端（双重检查锁，与 MilvusServiceImpl 一致）。 */
    private MilvusClientV2 client() {
        MilvusClientV2 c = milvusClient;
        if (c != null) {
            return c;
        }
        synchronized (this) {
            if (milvusClient == null) {
                String uri = "http://" + host + ":" + port;
                milvusClient = new MilvusClientV2(ConnectConfig.builder().uri(uri).build());
            }
            return milvusClient;
        }
    }

    /**
     * 从 Milvus SearchResp 中提取命中列表，携带真实 COSINE 相似度分。
     * hit.getScore() 在 COSINE 度量下直接返回余弦相似度，范围 [−1, 1]。
     */
    private List<MemoryVectorService.MemoryHit> extractMemoryHits(SearchResp resp) {
        if (resp == null || resp.getSearchResults() == null || resp.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<SearchResp.SearchResult> hits = resp.getSearchResults().get(0);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        List<MemoryVectorService.MemoryHit> result = new ArrayList<>(hits.size());
        for (SearchResp.SearchResult hit : hits) {
            Object raw = hit.getEntity() != null ? hit.getEntity().get("memory_id") : null;
            if (raw == null && hit.getId() != null) {
                raw = hit.getId();
            }
            long id;
            if (raw instanceof Number n) {
                id = n.longValue();
            } else if (raw != null) {
                try {
                    id = Long.parseLong(String.valueOf(raw));
                } catch (NumberFormatException ignored) {
                    continue;   // skip malformed entry
                }
            } else {
                continue;
            }
            result.add(new MemoryVectorService.MemoryHit(id, hit.getScore()));
        }
        return result;
    }

    private String trimMsg(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
