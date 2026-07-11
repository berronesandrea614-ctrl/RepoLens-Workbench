package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JsDependencyExtractor 单测：
 * 1. package.json 新增依赖 diff（MANIFEST 来源）。
 * 2. JS/TS 源码 bare-specifier 提取 + 内置模块过滤。
 * 3. extractBareSpecifiers 过滤相对路径、node: 前缀、内置模块。
 * 4. normalizePkgName scoped 包处理。
 */
class JsDependencyExtractorTest {

    private final JsDependencyExtractor extractor = new JsDependencyExtractor();

    // ─────────────────────── supports() ─────────────────────────────────────

    @Test
    void supports_packageJson() {
        assertThat(extractor.supports("package.json")).isTrue();
        assertThat(extractor.supports("/app/package.json")).isTrue();
    }

    @Test
    void supports_jsAndTsFiles() {
        assertThat(extractor.supports("src/index.js")).isTrue();
        assertThat(extractor.supports("src/App.tsx")).isTrue();
        assertThat(extractor.supports("lib/util.mjs")).isTrue();
        assertThat(extractor.supports("src/App.cjs")).isTrue();
    }

    @Test
    void supports_pythonFilesReturnsFalse() {
        assertThat(extractor.supports("main.py")).isFalse();
        assertThat(extractor.supports("requirements.txt")).isFalse();
    }

    // ─────────────────── package.json diff extraction ────────────────────────

    @Test
    void extractAdded_packageJson_newDependency() {
        String oldPkg = """
                {"dependencies": {"react": "^18.0.0"}}
                """;
        String newPkg = """
                {"dependencies": {"react": "^18.0.0", "axios": "^1.4.0"}}
                """;

        List<ExtractedDep> added = extractor.extractAdded("package.json", oldPkg, newPkg);
        assertThat(added).hasSize(1);
        ExtractedDep dep = added.get(0);
        assertThat(dep.name()).isEqualTo("axios");
        assertThat(dep.ecosystem()).isEqualTo(ExtractedDep.ECOSYSTEM_NPM);
        assertThat(dep.source()).isEqualTo(ExtractedDep.SOURCE_MANIFEST);
        assertThat(dep.version()).isEqualTo("^1.4.0");
    }

    @Test
    void extractAdded_packageJson_removedDependencyNotReported() {
        String oldPkg = """
                {"dependencies": {"react": "^18.0.0", "axios": "^1.4.0"}}
                """;
        String newPkg = """
                {"dependencies": {"react": "^18.0.0"}}
                """;

        List<ExtractedDep> added = extractor.extractAdded("package.json", oldPkg, newPkg);
        assertThat(added).isEmpty();
    }

    @Test
    void extractAdded_packageJson_devDependencyNewEntry() {
        String oldPkg = "{}";
        String newPkg = """
                {"devDependencies": {"jest": "^29.0.0"}}
                """;

        List<ExtractedDep> added = extractor.extractAdded("package.json", oldPkg, newPkg);
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("jest");
        assertThat(added.get(0).source()).isEqualTo(ExtractedDep.SOURCE_MANIFEST);
    }

    @Test
    void extractAdded_packageJson_emptyOldContent() {
        String newPkg = """
                {"dependencies": {"lodash": "^4.17.21"}}
                """;

        List<ExtractedDep> added = extractor.extractAdded("package.json", null, newPkg);
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("lodash");
    }

    // ────────────────────── source file extraction ───────────────────────────

    @Test
    void extractAdded_sourceFile_newImport() {
        String oldCode = "import React from 'react';";
        String newCode = "import React from 'react';\nimport axios from 'axios';";

        List<ExtractedDep> added = extractor.extractAdded("src/app.ts", oldCode, newCode);
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("axios");
        assertThat(added.get(0).source()).isEqualTo(ExtractedDep.SOURCE_IMPORT);
    }

    @Test
    void extractAdded_sourceFile_existingImportNotReported() {
        String oldCode = "import axios from 'axios';";
        String newCode = "import axios from 'axios';\nimport React from 'react';";

        List<ExtractedDep> added = extractor.extractAdded("src/app.ts", oldCode, newCode);
        // 'axios' existed, 'react' is new
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("react");
    }

    // ──────────────────── extractBareSpecifiers filters ──────────────────────

    @Test
    void extractBareSpecifiers_filtersRelativePaths() {
        String code = """
                import A from './local';
                import B from '../parent';
                import C from 'axios';
                """;
        Set<String> specs = extractor.extractBareSpecifiers(code);
        assertThat(specs).containsExactly("axios");
    }

    @Test
    void extractBareSpecifiers_filtersNodeBuiltins() {
        String code = """
                import fs from 'fs';
                import path from 'path';
                import axios from 'axios';
                const http = require('http');
                """;
        Set<String> specs = extractor.extractBareSpecifiers(code);
        assertThat(specs).containsExactly("axios");
    }

    @Test
    void extractBareSpecifiers_filtersNodeProtocol() {
        String code = "import { readFile } from 'node:fs/promises';";
        Set<String> specs = extractor.extractBareSpecifiers(code);
        assertThat(specs).isEmpty();
    }

    @Test
    void extractBareSpecifiers_scopedPackage() {
        String code = "import { useSelector } from '@reduxjs/toolkit/dist/index';";
        Set<String> specs = extractor.extractBareSpecifiers(code);
        assertThat(specs).containsExactly("@reduxjs/toolkit");
    }

    @Test
    void extractBareSpecifiers_requireSyntax() {
        String code = "const express = require('express');";
        Set<String> specs = extractor.extractBareSpecifiers(code);
        assertThat(specs).containsExactly("express");
    }

    @Test
    void extractBareSpecifiers_subpathNormalized() {
        // 'lodash/isEmpty' → 'lodash'
        String code = "import isEmpty from 'lodash/isEmpty';";
        Set<String> specs = extractor.extractBareSpecifiers(code);
        assertThat(specs).containsExactly("lodash");
    }

    // ──────────────────────── normalizePkgName ───────────────────────────────

    @Test
    void normalizePkgName_simplePackage() {
        assertThat(extractor.normalizePkgName("axios")).isEqualTo("axios");
    }

    @Test
    void normalizePkgName_subpathStripped() {
        assertThat(extractor.normalizePkgName("lodash/isEmpty")).isEqualTo("lodash");
    }

    @Test
    void normalizePkgName_scopedPackage() {
        assertThat(extractor.normalizePkgName("@types/react")).isEqualTo("@types/react");
    }

    @Test
    void normalizePkgName_scopedPackageWithSubpath() {
        assertThat(extractor.normalizePkgName("@reduxjs/toolkit/dist/index"))
                .isEqualTo("@reduxjs/toolkit");
    }

    @Test
    void normalizePkgName_malformedScopedPackage_returnsNull() {
        // "@scope" without slash is malformed
        assertThat(extractor.normalizePkgName("@scope")).isNull();
    }

    // ──────────────────── NODE_BUILTINS completeness ─────────────────────────

    @Test
    void nodeBuiltins_containsExpectedModules() {
        assertThat(JsDependencyExtractor.NODE_BUILTINS)
                .contains("fs", "path", "http", "https", "stream", "crypto",
                          "os", "events", "child_process", "url", "util", "zlib");
    }
}
