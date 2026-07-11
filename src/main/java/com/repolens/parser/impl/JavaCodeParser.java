package com.repolens.parser.impl;

import com.repolens.parser.CodeParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JavaCodeParser implements CodeParser {

    @Override
    public void parse(Long repoId, String localRepoPath) {
        // 第一阶段仅保留解析扩展点，后续接入 JavaParser AST 解析与降级逻辑。
        log.info("JavaCodeParser.parse invoked, repoId={}, localRepoPath={}", repoId, localRepoPath);
    }
}
