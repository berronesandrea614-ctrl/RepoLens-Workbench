package com.repolens.service.impl.support;

import java.util.List;

/**
 * 依赖提取策略接口（Strategy Pattern）。
 * 按文件类型/名称分发到各实现（JS/Python），仅抽「AI 新增」依赖（newContent − oldContent）。
 */
public interface DependencyExtractor {

    /**
     * 是否支持处理该文件路径。
     *
     * @param filePath 相对于仓库根目录的文件路径
     * @return true 表示此提取器负责处理该文件
     */
    boolean supports(String filePath);

    /**
     * 抽取 AI 在本次改动中新增的依赖。
     * 仅返回「newContent 中存在、oldContent 中不存在」的依赖，剔除存量干扰。
     *
     * @param filePath   文件路径（用于决策文件类型）
     * @param oldContent 改动前内容（createFileContent 场景可为空字符串）
     * @param newContent 改动后内容
     * @return 新增依赖列表，不含重复项
     */
    List<ExtractedDep> extractAdded(String filePath, String oldContent, String newContent);
}
