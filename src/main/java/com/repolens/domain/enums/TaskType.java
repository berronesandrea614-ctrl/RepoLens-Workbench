package com.repolens.domain.enums;

public enum TaskType {
    CLONE_REPO,
    PARSE_CODE,
    BUILD_CHUNK,
    VECTORIZE_CHUNK,
    EMBED_CHUNK,
    UPSERT_VECTOR,
    REINDEX_REPO
}
