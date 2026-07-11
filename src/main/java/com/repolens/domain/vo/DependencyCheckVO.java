package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单个依赖体检结论的 VO，供接口返回及前端渲染。
 */
@Data
@Builder
public class DependencyCheckVO {

    private Long id;

    private Long repoId;

    private Long sessionId;

    /** 关联的 file_change_log.id。 */
    private Long changeId;

    /** 文件路径。 */
    private String filePath;

    /** 生态系统：npm / pypi。 */
    private String ecosystem;

    /** 包名。 */
    private String packageName;

    /** 版本（可能为 null）。 */
    private String version;

    /** 提取来源：MANIFEST / IMPORT。 */
    private String source;

    /**
     * 体检结论：
     * <ul>
     *   <li>OK — 存在且未检测到 typo-squat</li>
     *   <li>NOT_FOUND — 公共 registry 返回 404</li>
     *   <li>TYPOSQUAT — 疑似热门包抢注，detail 含建议</li>
     *   <li>UNKNOWN — 网络失败/超时，无法判断</li>
     * </ul>
     */
    private String verdict;

    /**
     * 详情 JSON（原始字符串）。典型内容：
     * <ul>
     *   <li>TYPOSQUAT：{@code {"suggestion":"requests","distance":1}}</li>
     *   <li>NOT_FOUND：{@code {"registryUrl":"https://..."}}</li>
     * </ul>
     */
    private String detailJson;

    private LocalDateTime checkedAt;

    /** True when checked in OFFLINE mode (no network calls made). */
    private boolean checkedOffline;
}
