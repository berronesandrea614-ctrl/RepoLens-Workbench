package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PythonDependencyExtractor 单测：
 * 1. requirements.txt 新增依赖 diff（MANIFEST 来源）。
 * 2. Python 源码 import 提取 + stdlib 过滤 + 别名映射。
 * 3. extractTopLevelModules 过滤 stdlib、相对导入。
 * 4. resolveModuleName 别名映射覆盖（yaml→PyYAML, cv2→opencv-python 等）。
 */
class PythonDependencyExtractorTest {

    private final PythonDependencyExtractor extractor = new PythonDependencyExtractor();

    // ────────────────────────── supports() ───────────────────────────────────

    @Test
    void supports_requirementsTxt() {
        assertThat(extractor.supports("requirements.txt")).isTrue();
        assertThat(extractor.supports("/app/requirements-dev.txt")).isTrue();
    }

    @Test
    void supports_pyprojectToml() {
        assertThat(extractor.supports("pyproject.toml")).isTrue();
    }

    @Test
    void supports_pyFiles() {
        assertThat(extractor.supports("main.py")).isTrue();
        assertThat(extractor.supports("src/util.py")).isTrue();
    }

    @Test
    void supports_nonPythonReturnsFalse() {
        assertThat(extractor.supports("package.json")).isFalse();
        assertThat(extractor.supports("src/index.ts")).isFalse();
    }

    // ─────────────────── requirements.txt diff ───────────────────────────────

    @Test
    void extractAdded_requirementsTxt_newPackage() {
        String oldReq = "requests==2.28.0\n";
        String newReq = "requests==2.28.0\nnumpy>=1.24.0\n";

        List<ExtractedDep> added = extractor.extractAdded("requirements.txt", oldReq, newReq);
        assertThat(added).hasSize(1);
        ExtractedDep dep = added.get(0);
        assertThat(dep.name()).isEqualTo("numpy");
        assertThat(dep.ecosystem()).isEqualTo(ExtractedDep.ECOSYSTEM_PYPI);
        assertThat(dep.source()).isEqualTo(ExtractedDep.SOURCE_MANIFEST);
    }

    @Test
    void extractAdded_requirementsTxt_removedPackageNotReported() {
        String oldReq = "requests==2.28.0\nnumpy>=1.24.0\n";
        String newReq = "requests==2.28.0\n";

        List<ExtractedDep> added = extractor.extractAdded("requirements.txt", oldReq, newReq);
        assertThat(added).isEmpty();
    }

    @Test
    void extractAdded_requirementsTxt_commentsAndEmptyLinesIgnored() {
        String oldReq = "";
        String newReq = "# This is a comment\n\nrequests==2.28.0\n";

        List<ExtractedDep> added = extractor.extractAdded("requirements.txt", oldReq, newReq);
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("requests");
    }

    @Test
    void extractAdded_requirementsTxt_packageWithExtrasStripped() {
        String oldReq = "";
        String newReq = "boto3[crt]>=1.26.0\n";

        List<ExtractedDep> added = extractor.extractAdded("requirements.txt", oldReq, newReq);
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("boto3");
    }

    @Test
    void extractAdded_requirementsTxt_nullOldContent() {
        String newReq = "flask>=2.0.0\n";
        List<ExtractedDep> added = extractor.extractAdded("requirements.txt", null, newReq);
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("flask");
    }

    // ──────────────────── Python source import extraction ────────────────────

    @Test
    void extractAdded_pySource_newImport() {
        String oldCode = "import os\nimport sys\n";
        String newCode = "import os\nimport sys\nimport requests\n";

        List<ExtractedDep> added = extractor.extractAdded("main.py", oldCode, newCode);
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("requests");
        assertThat(added.get(0).source()).isEqualTo(ExtractedDep.SOURCE_IMPORT);
    }

    @Test
    void extractAdded_pySource_fromImportStyle() {
        String oldCode = "";
        String newCode = "from flask import Flask\n";

        List<ExtractedDep> added = extractor.extractAdded("app.py", oldCode, newCode);
        assertThat(added).hasSize(1);
        assertThat(added.get(0).name()).isEqualTo("flask");
    }

    @Test
    void extractAdded_pySource_stdlibFiltered() {
        String oldCode = "";
        String newCode = "import os\nimport sys\nimport json\nimport pathlib\n";

        List<ExtractedDep> added = extractor.extractAdded("util.py", oldCode, newCode);
        assertThat(added).isEmpty();
    }

    // ─────────────────────── extractTopLevelModules ──────────────────────────

    @Test
    void extractTopLevelModules_stdlibFiltered() {
        String code = "import os\nimport json\nimport requests\n";
        Set<String> mods = extractor.extractTopLevelModules(code);
        assertThat(mods).containsExactly("requests");
    }

    @Test
    void extractTopLevelModules_fromImportStyle() {
        String code = "from flask import Flask\nfrom os import path\n";
        Set<String> mods = extractor.extractTopLevelModules(code);
        assertThat(mods).containsExactly("flask");
    }

    @Test
    void extractTopLevelModules_emptyContent() {
        assertThat(extractor.extractTopLevelModules("")).isEmpty();
        assertThat(extractor.extractTopLevelModules(null)).isEmpty();
    }

    @Test
    void extractTopLevelModules_stdlibModulesExcluded() {
        // verify common stdlib modules are excluded
        assertThat(PythonDependencyExtractor.STDLIB_MODULES).contains(
                "os", "sys", "json", "math", "re", "datetime", "collections",
                "io", "pathlib", "subprocess", "threading", "typing", "uuid"
        );
    }

    // ────────────────────── resolveModuleName alias map ──────────────────────

    @Test
    void resolveModuleName_yamlMapsToYAML() {
        assertThat(extractor.resolveModuleName("yaml")).isEqualTo("PyYAML");
    }

    @Test
    void resolveModuleName_cv2MapsToOpencvPython() {
        assertThat(extractor.resolveModuleName("cv2")).isEqualTo("opencv-python");
    }

    @Test
    void resolveModuleName_sklearnMapsToScikitLearn() {
        assertThat(extractor.resolveModuleName("sklearn")).isEqualTo("scikit-learn");
    }

    @Test
    void resolveModuleName_PILMapsToPillow() {
        assertThat(extractor.resolveModuleName("PIL")).isEqualTo("Pillow");
    }

    @Test
    void resolveModuleName_bs4MapsToBeautifulsoup4() {
        assertThat(extractor.resolveModuleName("bs4")).isEqualTo("beautifulsoup4");
    }

    @Test
    void resolveModuleName_unknownModuleReturnedAsIs() {
        assertThat(extractor.resolveModuleName("mymodule")).isEqualTo("mymodule");
    }

    @Test
    void resolveModuleName_nullReturnsNull() {
        assertThat(extractor.resolveModuleName(null)).isNull();
        assertThat(extractor.resolveModuleName("")).isNull();
    }

    @Test
    void resolveModuleName_aliasMapContainsExpectedEntries() {
        assertThat(PythonDependencyExtractor.MODULE_TO_PYPI).containsEntry("yaml", "PyYAML");
        assertThat(PythonDependencyExtractor.MODULE_TO_PYPI).containsEntry("cv2", "opencv-python");
        assertThat(PythonDependencyExtractor.MODULE_TO_PYPI).containsEntry("jinja2", "Jinja2");
        assertThat(PythonDependencyExtractor.MODULE_TO_PYPI).containsEntry("sqlalchemy", "SQLAlchemy");
        assertThat(PythonDependencyExtractor.MODULE_TO_PYPI).containsEntry("jwt", "PyJWT");
    }
}
