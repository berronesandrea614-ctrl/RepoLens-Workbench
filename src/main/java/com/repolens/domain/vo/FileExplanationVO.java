package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 文件讲解 VO（偿债路径的「帮助理解」形态，替代原考试式选择题）。
 *
 * <p>由 LLM 基于文件内容生成：
 * <ul>
 *   <li>{@link #explanation}：markdown 讲解——该文件在项目中的作用与职责、
 *       与之直接相关的业务/调用链前因后果。</li>
 *   <li>{@link #relatedFiles}：与该文件直接相关的其他文件（相对路径，供前端点击跳转）。</li>
 *   <li>{@link #relatedSymbols}：直接相关的类/方法/函数（含所在文件相对路径，供前端跳转）。</li>
 * </ul>
 *
 * <p>失败安全：LLM 不可用时返回降级讲解（degraded=true），不抛异常。
 */
@Data
@Builder
public class FileExplanationVO {

    /** 目标文件 id。 */
    private Long fileId;

    /** 目标文件相对路径。 */
    private String filePath;

    /** markdown 格式的文件讲解（作用/职责 + 业务前因后果）。 */
    private String explanation;

    /** 直接相关的其他文件。 */
    private List<RelatedFile> relatedFiles;

    /** 直接相关的类/方法/函数。 */
    private List<RelatedSymbol> relatedSymbols;

    /** true = LLM 不可用，返回的是降级讲解。 */
    private boolean degraded;

    /** 相关文件项。 */
    @Data
    @Builder
    public static class RelatedFile {
        /** 相对路径（前端据此调用 openFile 跳转）。 */
        private String path;
        /** 为什么相关（一句话）。 */
        private String reason;
    }

    /** 相关符号项（类/方法/函数）。 */
    @Data
    @Builder
    public static class RelatedSymbol {
        /** 符号名（类名/方法名/函数名）。 */
        private String name;
        /** 该符号所在文件的相对路径（前端据此跳转，可空）。 */
        private String path;
        /** 为什么相关（一句话）。 */
        private String reason;
    }
}
