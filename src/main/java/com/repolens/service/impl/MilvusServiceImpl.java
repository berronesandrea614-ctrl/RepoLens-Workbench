package com.repolens.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.vo.VectorSearchHitVO;
import com.repolens.service.EmbeddingService;
import com.repolens.service.MilvusService;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.SearchResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RepoLens 对 Milvus 的统一访问入口。
 * 职责边界：
 * 1. 管理 collection 与向量索引；
 * 2. 执行向量 upsert / delete / search；
 * 3. 只处理检索索引层，不承载 RAG 组装和业务降级策略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusServiceImpl implements MilvusService {

    private static final String VECTOR_FIELD = "embedding";
    private static final int CHUNK_ID_MAX_LENGTH = 128;
    private static final int FILE_PATH_MAX_LENGTH = 512;
    private static final int CHUNK_TYPE_MAX_LENGTH = 32;
    private static final int LANGUAGE_MAX_LENGTH = 32;
    private static final int CONTENT_HASH_MAX_LENGTH = 128;
    private static final int DEFAULT_NLIST = 128;
    private static final int DEFAULT_TOP_K = 8;

    @Value("${repolens.milvus.host:localhost}")
    private String host;

    @Value("${repolens.milvus.port:19530}")
    private int port;

    @Value("${repolens.milvus.collection-name:code_chunk_collection}")
    private String collectionName;

    @Value("${repolens.embedding.dimension:384}")
    private int embeddingDimension;

    private final EmbeddingService embeddingService;

    private volatile MilvusClientV2 milvusClient;
    private volatile boolean collectionEnsured;

    /**
     * 确保检索所需 collection 与索引存在。
     * Milvus 不可用时抛业务异常，由上层决定是失败还是降级。
     */
    @Override
    public synchronized void ensureCollection() {
        if (collectionEnsured) {
            return;
        }
        try {
            MilvusClientV2 client = client();
            if (!hasCollection(client)) {
                createCollection(client);
            } else {
                validateExistingCollectionDimension(client);
            }
            client.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            collectionEnsured = true;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Milvus unavailable: " + buildErrorMessage(ex));
        }
    }

    /**
     * 以 repo_id 为过滤条件删除该仓库的全部旧向量。
     * MySQL 中的 code_chunk 才是事实数据源，Milvus 只是检索索引。
     * 只要 chunk 采用全量重建策略，就必须先清掉 repo 旧向量，避免旧 chunk 被脏召回。
     */
    @Override
    public void deleteByRepoId(Long repoId) {
        if (repoId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "repoId is required");
        }

        long startTime = System.currentTimeMillis();
        try {
            MilvusClientV2 client = client();
            if (!hasCollection(client)) {
                log.info("Milvus collection missing, skip deleteByRepoId, repoId={}, collectionName={}",
                        repoId, collectionName);
                return;
            }

            client.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());

            DeleteResp response = client.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter("repo_id == " + repoId)
                    .build());

            long deleteCount = response == null ? 0 : response.getDeleteCnt();
            long costMs = System.currentTimeMillis() - startTime;
            log.info("Milvus deleteByRepoId success, repoId={}, deleteCount={}, costMs={}",
                    repoId, deleteCount, costMs);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Milvus delete failed: " + buildErrorMessage(ex));
        }
    }

    @Override
    public void upsertCodeChunkVector(CodeChunkEntity chunk, float[] embedding) {
        upsertCodeChunkVectors(List.of(chunk), List.of(embedding));
    }

    @Override
    public void upsertCodeChunkVectors(List<CodeChunkEntity> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (embeddings == null || embeddings.size() != chunks.size()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Embedding batch size mismatch");
        }

        ensureCollection();
        long startTime = System.currentTimeMillis();
        try {
            List<JsonObject> rows = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                rows.add(toMilvusRow(chunks.get(i), embeddings.get(i)));
            }

            UpsertResp response = client().upsert(UpsertReq.builder()
                    .collectionName(collectionName)
                    .data(rows)
                    .build());

            long upsertCount = response == null ? 0 : response.getUpsertCnt();
            long costMs = System.currentTimeMillis() - startTime;
            Long repoId = chunks.get(0).getRepoId();
            log.info("Milvus upsert success, repoId={}, chunkCount={}, upsertCount={}, costMs={}",
                    repoId, chunks.size(), upsertCount, costMs);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Milvus upsert failed: " + buildErrorMessage(ex));
        }
    }

    /**
     * 向量检索。
     * 约束：必须带 repo_id filter，避免跨仓库命中脏数据。
     */
    @Override
    public List<VectorSearchHitVO> search(String query, Long repoId, Integer topK) {
        if (!StringUtils.hasText(query)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Query cannot be empty");
        }
        if (repoId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "repoId is required");
        }

        int limit = normalizeTopK(topK);
        ensureCollection();

        long startTime = System.currentTimeMillis();
        try {
            float[] queryVector = embeddingService.embed(query);
            List<Float> vector = new ArrayList<>(queryVector.length);
            for (float value : queryVector) {
                vector.add(value);
            }

            Map<String, Object> searchParams = new HashMap<>();
            searchParams.put("nprobe", 10);

            SearchReq searchReq = SearchReq.builder()
                    .collectionName(collectionName)
                    .annsField(VECTOR_FIELD)
                    .filter("repo_id == " + repoId)
                    .topK(limit)
                    .searchParams(searchParams)
                    .outputFields(List.of("chunk_id", "repo_id", "file_path", "chunk_type", "start_line", "end_line"))
                    .data(List.of(new FloatVec(vector)))
                    .build();

            SearchResp response = client().search(searchReq);
            List<VectorSearchHitVO> hits = extractHits(response);
            long costMs = System.currentTimeMillis() - startTime;
            log.info("Milvus search finished, repoId={}, topK={}, hitCount={}, costMs={}",
                    repoId, limit, hits.size(), costMs);
            return hits;
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Milvus vector search failed: " + buildErrorMessage(ex));
        }
    }

    private void createCollection(MilvusClientV2 client) {
        if (embeddingDimension <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid embedding dimension config");
        }

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .build();
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_id")
                .dataType(DataType.VarChar)
                .isPrimaryKey(true)
                .autoID(false)
                .maxLength(CHUNK_ID_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("repo_id")
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("file_id")
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("symbol_id")
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("file_path")
                .dataType(DataType.VarChar)
                .maxLength(FILE_PATH_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("chunk_type")
                .dataType(DataType.VarChar)
                .maxLength(CHUNK_TYPE_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("language")
                .dataType(DataType.VarChar)
                .maxLength(LANGUAGE_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("start_line")
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("end_line")
                .dataType(DataType.Int64)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName("content_hash")
                .dataType(DataType.VarChar)
                .maxLength(CONTENT_HASH_MAX_LENGTH)
                .build());
        schema.addField(AddFieldReq.builder()
                .fieldName(VECTOR_FIELD)
                .dataType(DataType.FloatVector)
                .dimension(embeddingDimension)
                .build());

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description("RepoLens code chunk vectors")
                .collectionSchema(schema)
                .build());

        Map<String, Object> extraParams = new HashMap<>();
        extraParams.put("nlist", DEFAULT_NLIST);

        IndexParam vectorIndex = IndexParam.builder()
                .fieldName(VECTOR_FIELD)
                .indexName("idx_embedding")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(extraParams)
                .build();
        client.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(vectorIndex))
                .build());

        log.info("Milvus collection created, collectionName={}, dimension={}", collectionName, embeddingDimension);
    }

    /**
     * 已存在 collection 时，必须确认向量维度与当前 embedding 配置一致。
     * 这里不自动删库重建，因为 Milvus 是共享检索索引，静默重建会误删已有数据。
     * 如果用户切换成了不同维度的真实 Embedding 模型，需要手工清理 collection 或更换 collection name。
     */
    private void validateExistingCollectionDimension(MilvusClientV2 client) {
        DescribeCollectionResp response = client.describeCollection(DescribeCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        if (response == null || response.getCollectionSchema() == null) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Milvus collection schema is unavailable for dimension validation");
        }

        CreateCollectionReq.FieldSchema vectorField = response.getCollectionSchema().getField(VECTOR_FIELD);
        if (vectorField == null || vectorField.getDimension() == null) {
            throw new BizException(ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Milvus collection vector field schema is unavailable for dimension validation");
        }

        int actualDimension = vectorField.getDimension();
        if (actualDimension != embeddingDimension) {
            throw new BizException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Milvus collection dimension mismatch, expected=" + embeddingDimension + ", actual=" + actualDimension
            );
        }
        log.info("Milvus collection dimension validated, collectionName={}, dimension={}",
                collectionName, actualDimension);
    }

    private JsonObject toMilvusRow(CodeChunkEntity chunk, float[] embedding) {
        if (chunk == null || !StringUtils.hasText(chunk.getChunkId())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid chunk data for Milvus upsert");
        }
        if (embedding == null || embedding.length != embeddingDimension) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Embedding dimension mismatch");
        }

        JsonObject row = new JsonObject();
        row.addProperty("chunk_id", chunk.getChunkId());
        row.addProperty("repo_id", requiredLong(chunk.getRepoId(), "repoId"));
        row.addProperty("file_id", requiredLong(chunk.getFileId(), "fileId"));
        row.addProperty("symbol_id", chunk.getSymbolId() == null ? 0L : chunk.getSymbolId());
        row.addProperty("file_path", safeText(chunk.getFilePath()));
        row.addProperty("chunk_type", chunk.getChunkType() == null ? "UNKNOWN" : chunk.getChunkType().name());
        row.addProperty("language", safeText(chunk.getLanguage()));
        row.addProperty("start_line", chunk.getStartLine() == null ? 0L : chunk.getStartLine().longValue());
        row.addProperty("end_line", chunk.getEndLine() == null ? 0L : chunk.getEndLine().longValue());
        row.addProperty("content_hash", safeText(chunk.getContentHash()));

        JsonArray vector = new JsonArray();
        for (float value : embedding) {
            vector.add(value);
        }
        row.add(VECTOR_FIELD, vector);
        return row;
    }

    private List<VectorSearchHitVO> extractHits(SearchResp response) {
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }
        List<SearchResp.SearchResult> firstQueryHits = response.getSearchResults().get(0);
        if (firstQueryHits == null || firstQueryHits.isEmpty()) {
            return List.of();
        }

        List<VectorSearchHitVO> hits = new ArrayList<>(firstQueryHits.size());
        for (SearchResp.SearchResult hit : firstQueryHits) {
            Map<String, Object> entity = hit.getEntity();
            String chunkId = asString(entity == null ? null : entity.get("chunk_id"));
            if (!StringUtils.hasText(chunkId) && hit.getId() != null) {
                chunkId = String.valueOf(hit.getId());
            }
            hits.add(VectorSearchHitVO.builder()
                    .chunkId(chunkId)
                    .score(hit.getScore())
                    .repoId(asLong(entity == null ? null : entity.get("repo_id")))
                    .filePath(asString(entity == null ? null : entity.get("file_path")))
                    .chunkType(asString(entity == null ? null : entity.get("chunk_type")))
                    .startLine(asInt(entity == null ? null : entity.get("start_line")))
                    .endLine(asInt(entity == null ? null : entity.get("end_line")))
                    .build());
        }
        return hits;
    }

    private boolean hasCollection(MilvusClientV2 client) {
        return Boolean.TRUE.equals(client.hasCollection(HasCollectionReq.builder()
                .collectionName(collectionName)
                .build()));
    }

    private MilvusClientV2 client() {
        MilvusClientV2 client = milvusClient;
        if (client != null) {
            return client;
        }
        synchronized (this) {
            if (milvusClient == null) {
                String uri = "http://" + host + ":" + port;
                milvusClient = new MilvusClientV2(ConnectConfig.builder()
                        .uri(uri)
                        .build());
            }
            return milvusClient;
        }
    }

    private Long requiredLong(Long value, String fieldName) {
        if (value == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Missing " + fieldName + " for Milvus upsert");
        }
        return value;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, 20);
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer asInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String buildErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        String trimmed = message.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}
