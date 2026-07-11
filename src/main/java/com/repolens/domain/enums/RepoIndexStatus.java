package com.repolens.domain.enums;

public enum RepoIndexStatus {
    PENDING,
    INDEXING,
    INDEXED,
    FAILED,
    /** 索引已过期：代码文件已被写盘修改但尚未重建索引，前端据此显示「索引已过期」徽标并引导重建。 */
    STALE
}
