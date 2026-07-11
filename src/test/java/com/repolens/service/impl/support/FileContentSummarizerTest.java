package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD tests for FileContentSummarizer.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Java entity/POJO class → field-based sig and note</li>
 *   <li>Java service interface → method-based sig and note</li>
 *   <li>Java controller class → HTTP layer note</li>
 *   <li>Java class with Javadoc → note from Javadoc</li>
 *   <li>Java class with Lombok (no explicit methods) → entity path</li>
 *   <li>Non-Java file (TypeScript) → fallback heuristic</li>
 *   <li>Content exceeding 200 KB → EMPTY</li>
 *   <li>Null/empty inputs → EMPTY</li>
 *   <li>sig and note length caps</li>
 * </ul>
 */
class FileContentSummarizerTest {

    // ─────────────────────────────────────────────────────────────────────────
    // Guard: null / empty / oversized
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summarize_nullFilePath_returnsEmpty() {
        var r = FileContentSummarizer.summarize(null, "class Foo {}");
        assertThat(r).isEqualTo(FileContentSummarizer.FileSummary.EMPTY);
    }

    @Test
    void summarize_nullContent_returnsEmpty() {
        var r = FileContentSummarizer.summarize("Foo.java", null);
        assertThat(r).isEqualTo(FileContentSummarizer.FileSummary.EMPTY);
    }

    @Test
    void summarize_emptyContent_returnsEmpty() {
        var r = FileContentSummarizer.summarize("Foo.java", "");
        assertThat(r).isEqualTo(FileContentSummarizer.FileSummary.EMPTY);
    }

    @Test
    void summarize_contentExceeds200KB_returnsEmpty() {
        // Build a string slightly over 200 KB
        String big = "x".repeat(FileContentSummarizer.MAX_CONTENT_BYTES + 1);
        var r = FileContentSummarizer.summarize("Foo.java", big);
        assertThat(r).isEqualTo(FileContentSummarizer.FileSummary.EMPTY);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java entity class (field-based sig)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summarizeJava_entityClass_returnsClassNameAndFieldSig() {
        String src = """
                package com.example;

                public class BlogEntity {
                    private Long id;
                    private String title;
                    private String content;
                    private String author;
                    private String createTime;
                    private String tags;
                }
                """;
        var r = FileContentSummarizer.summarizeJava(src);

        assertThat(r.className()).isEqualTo("BlogEntity");
        assertThat(r.sig()).contains("BlogEntity");
        assertThat(r.sig()).contains("id");
        assertThat(r.sig()).contains("title");
        assertThat(r.note()).contains("BlogEntity");
        assertThat(r.note()).contains("实体类");
    }

    @Test
    void summarizeJava_entityWith7Fields_sigHasEllipsis() {
        String src = """
                package com.example;

                public class PersonalBlogEntity {
                    private Long id;
                    private String title;
                    private String content;
                    private String author;
                    private String createTime;
                    private String tags;
                    private Integer viewCount;
                }
                """;
        var r = FileContentSummarizer.summarizeJava(src);

        assertThat(r.className()).isEqualTo("PersonalBlogEntity");
        assertThat(r.sig()).contains("...");  // truncated after MAX_ENTITY_FIELDS (6)
    }

    @Test
    void summarizeJava_lombokEntityWithAnnotations_entityPath() {
        // Lombok @Data class — no declared getters/setters in source
        String src = """
                package com.example;

                import lombok.Data;
                import com.baomidou.mybatisplus.annotation.TableName;

                @Data
                @TableName("blog")
                public class Blog {
                    private Long id;
                    private String title;
                    private String content;
                    private String author;
                }
                """;
        var r = FileContentSummarizer.summarizeJava(src);

        assertThat(r.className()).isEqualTo("Blog");
        assertThat(r.sig()).contains("Blog");
        assertThat(r.sig()).contains("id");
        assertThat(r.sig()).contains("title");
        assertThat(r.note()).contains("Blog 实体类");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java service interface (method-based sig)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summarizeJava_serviceInterface_returnsMethodSig() {
        String src = """
                package com.example;

                import java.util.List;

                public interface BlogService {
                    Blog getById(Long id);
                    List<Blog> listAll();
                    void createBlog(Blog blog);
                }
                """;
        var r = FileContentSummarizer.summarizeJava(src);

        assertThat(r.className()).isEqualTo("BlogService");
        assertThat(r.sig()).contains("BlogService");
        assertThat(r.sig()).contains("getById");
        // note should mention business layer
        assertThat(r.note()).contains("业务逻辑层");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java controller class
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summarizeJava_controllerClass_returnsHttpLayerNote() {
        String src = """
                package com.example;

                import org.springframework.web.bind.annotation.RestController;
                import org.springframework.web.bind.annotation.GetMapping;

                @RestController
                public class BlogController {
                    public Blog getById(Long id) { return null; }
                    public void createBlog(Blog blog) {}
                }
                """;
        var r = FileContentSummarizer.summarizeJava(src);

        assertThat(r.className()).isEqualTo("BlogController");
        assertThat(r.note()).contains("HTTP 接口层");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java class with Javadoc
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summarizeJava_classWithJavadoc_usesJavadocAsNote() {
        String src = """
                package com.example;

                /**
                 * 博客评论实体，关联博客 ID 与评论内容。
                 * @author test
                 */
                public class CommentEntity {
                    private Long id;
                    private Long blogId;
                    private String content;
                }
                """;
        var r = FileContentSummarizer.summarizeJava(src);

        assertThat(r.className()).isEqualTo("CommentEntity");
        assertThat(r.note()).contains("博客评论实体");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java mapper interface
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summarizeJava_mapperInterface_returnsDataAccessNote() {
        String src = """
                package com.example;

                public interface BlogMapper {
                    Blog selectById(Long id);
                    int insert(Blog blog);
                }
                """;
        var r = FileContentSummarizer.summarizeJava(src);

        assertThat(r.className()).isEqualTo("BlogMapper");
        assertThat(r.note()).contains("数据访问层");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Non-Java files (fallback heuristic)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void summarize_typescriptFile_fallbackHeuristic() {
        String src = """
                // BlogService: 博客相关接口
                export interface BlogVO {
                    id: number;
                    title: string;
                }
                """;
        var r = FileContentSummarizer.summarize("src/api/blog.ts", src);

        assertThat(r.className()).isNull();
        assertThat(r.sig()).isNotNull();
        assertThat(r.note()).isNotNull();
        // Should pick up the first comment
        assertThat(r.sig()).contains("BlogService");
    }

    @Test
    void summarize_pythonFile_fallbackCountsDefLines() {
        String src = """
                # Blog utilities
                def get_blog(id):
                    pass

                def create_blog(data):
                    pass
                """;
        var r = FileContentSummarizer.summarize("blog.py", src);

        assertThat(r.className()).isNull();
        // First comment is "Blog utilities"
        assertThat(r.sig()).contains("Blog utilities");
    }

    @Test
    void summarize_fileWithNoComment_sigContainsLineCount() {
        String src = "x = 1\ny = 2\nz = x + y\n";
        var r = FileContentSummarizer.summarize("math.js", src);

        assertThat(r.sig()).isNotNull();
        // Fallback sig: "N 行，M 个方法/函数"
        assertThat(r.sig()).contains("行");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Length cap
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void truncate_longString_capped() {
        String s = "a".repeat(200);
        String truncated = FileContentSummarizer.truncate(s, FileContentSummarizer.MAX_SIG_LENGTH);
        assertThat(truncated).hasSizeLessThanOrEqualTo(FileContentSummarizer.MAX_SIG_LENGTH);
        assertThat(truncated).endsWith("…");
    }

    @Test
    void truncate_shortString_unchanged() {
        String s = "short";
        assertThat(FileContentSummarizer.truncate(s, FileContentSummarizer.MAX_SIG_LENGTH))
                .isEqualTo("short");
    }

    @Test
    void truncate_null_returnsNull() {
        assertThat(FileContentSummarizer.truncate(null, 50)).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Java field / method extraction
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void extractFieldNames_staticFieldsExcluded() {
        String src = """
                public class Foo {
                    public static final String CONST = "x";
                    private int value;
                    private String name;
                }
                """;
        var cu = new com.github.javaparser.JavaParser()
                .parse(src).getResult().orElseThrow();
        var type = FileContentSummarizer.findPrimaryType(cu);
        var fields = FileContentSummarizer.extractFieldNames(type);

        assertThat(fields).containsExactly("value", "name");
        assertThat(fields).doesNotContain("CONST");
    }

    @Test
    void extractNonTrivialMethodSigs_skipsAccessors() {
        String src = """
                public class BlogService {
                    public String getName() { return null; }
                    public void setName(String n) {}
                    public boolean isActive() { return true; }
                    public Blog findById(Long id) { return null; }
                    public void delete(Long id) {}
                }
                """;
        var cu = new com.github.javaparser.JavaParser()
                .parse(src).getResult().orElseThrow();
        var type = FileContentSummarizer.findPrimaryType(cu);
        var methods = FileContentSummarizer.extractNonTrivialMethodSigs(type);

        // Should only include findById and delete (not getName/setName/isActive)
        assertThat(methods).allMatch(s -> !s.contains("getName")
                && !s.contains("setName")
                && !s.contains("isActive"));
        assertThat(methods).anyMatch(s -> s.contains("findById"));
    }

    @Test
    void isEntityLike_entitySuffix_true() {
        String src = "public class UserEntity { private Long id; }";
        var cu = new com.github.javaparser.JavaParser()
                .parse(src).getResult().orElseThrow();
        var type = FileContentSummarizer.findPrimaryType(cu);
        assertThat(FileContentSummarizer.isEntityLike(type)).isTrue();
    }

    @Test
    void isEntityLike_serviceInterface_false() {
        String src = """
                public interface UserService {
                    User findById(Long id);
                    void create(User u);
                    void update(User u);
                    void delete(Long id);
                }
                """;
        var cu = new com.github.javaparser.JavaParser()
                .parse(src).getResult().orElseThrow();
        var type = FileContentSummarizer.findPrimaryType(cu);
        assertThat(FileContentSummarizer.isEntityLike(type)).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fallback helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void extractFirstComment_singleLine_found() {
        String c = FileContentSummarizer.extractFirstComment("// Hello world\n");
        assertThat(c).isEqualTo("Hello world");
    }

    @Test
    void extractFirstComment_hashComment_found() {
        String c = FileContentSummarizer.extractFirstComment("# Python comment\n");
        assertThat(c).isEqualTo("Python comment");
    }

    @Test
    void extractFirstComment_shebang_skipped() {
        // A shebang line (#!) should be skipped, but the next hash comment should be found
        String c = FileContentSummarizer.extractFirstComment("#!/usr/bin/env python\n# My script\n");
        // First hash line starts with !, so it's skipped; second line should be found
        assertThat(c).isEqualTo("My script");
    }

    @Test
    void extractFirstComment_noComment_returnsNull() {
        String c = FileContentSummarizer.extractFirstComment("x = 1\ny = 2\n");
        assertThat(c).isNull();
    }

    @Test
    void countFunctions_pythonDefs_counted() {
        String src = "def foo():\n  pass\ndef bar():\n  pass\n";
        int count = FileContentSummarizer.countFunctions(src, "py");
        assertThat(count).isEqualTo(2);
    }
}
