package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 依赖体检记录。每次 AI 新增依赖（JS / Python / Java）均留一条审计记录。
 * session_id 方便前端按会话查询；change_id 关联具体文件变更。
 */
@Data
@TableName("dependency_check")
public class DependencyCheckEntity {

    /** OK: 存在且不疑似抢注。 */
    public static final String VERDICT_OK = "OK";
    /** NOT_FOUND: 公共 registry 返回 404。 */
    public static final String VERDICT_NOT_FOUND = "NOT_FOUND";
    /** TYPOSQUAT: 本地编辑距离检测疑似热门包名抢注。 */
    public static final String VERDICT_TYPOSQUAT = "TYPOSQUAT";
    /** UNKNOWN: 网络失败/超时，无法判断。 */
    public static final String VERDICT_UNKNOWN = "UNKNOWN";
    /** MALICIOUS: 确认恶意包（OSV MAL-* id）。优先级最高（1）。 */
    public static final String VERDICT_MALICIOUS = "MALICIOUS";
    /** VULNERABLE: 有已知 CVE/GHSA 漏洞。优先级 4。 */
    public static final String VERDICT_VULNERABLE = "VULNERABLE";

    /** MANIFEST: 从 package.json / requirements.txt 等清单文件提取。 */
    public static final String SOURCE_MANIFEST = "MANIFEST";
    /** IMPORT: 从源码 import / require 语句提取。 */
    public static final String SOURCE_IMPORT = "IMPORT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    /** 关联 file_change_log.id。 */
    private Long changeId;

    private String filePath;

    /** 生态系统：npm / pypi。 */
    private String ecosystem;

    private String packageName;

    private String version;

    /** 提取来源：MANIFEST / IMPORT。 */
    private String source;

    /** 体检结论：OK / NOT_FOUND / TYPOSQUAT / UNKNOWN。 */
    private String verdict;

    /** JSON 详情：如疑似包名建议、registry 响应、编辑距离等。 */
    private String detailJson;

    private LocalDateTime checkedAt;

    /** True when this record was produced in OFFLINE mode (no network calls). */
    private Boolean checkedOffline;
}
