package com.repolens.service.support;

import com.repolens.domain.dto.chat.MentionDTO;
import com.repolens.domain.vo.FileContentVO;
import com.repolens.domain.vo.SymbolVO;
import com.repolens.service.SymbolQueryService;
import com.repolens.service.impl.support.MentionContextInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MentionContextInjectorTest {

    @Mock
    private SymbolQueryService symbolQueryService;

    private MentionContextInjector injector;

    @BeforeEach
    void setUp() {
        injector = new MentionContextInjector(symbolQueryService);
    }

    // -----------------------------------------------------------------------
    // null / empty guards
    // -----------------------------------------------------------------------

    @Test
    void buildMentionEvidence_nullMentions_returnsEmpty() {
        String result = injector.buildMentionEvidence(1L, 5L, null);
        assertThat(result).isEmpty();
        verify(symbolQueryService, never()).getFileContent(any(), any(), any(), any(), any());
    }

    @Test
    void buildMentionEvidence_emptyMentions_returnsEmpty() {
        String result = injector.buildMentionEvidence(1L, 5L, List.of());
        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // file mention
    // -----------------------------------------------------------------------

    @Test
    void buildMentionEvidence_fileMention_returnsFormattedBlock() {
        MentionDTO m = new MentionDTO();
        m.setType("file");
        m.setValue("src/main/java/com/example/Foo.java");

        when(symbolQueryService.getFileContent(eq(1L), eq(5L),
                eq("src/main/java/com/example/Foo.java"), isNull(), isNull()))
                .thenReturn(FileContentVO.builder()
                        .filePath("src/main/java/com/example/Foo.java")
                        .content("public class Foo { }")
                        .build());

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));

        assertThat(result).contains("[Mention-1]");
        assertThat(result).contains("source: @提及");
        assertThat(result).contains("type: file");
        assertThat(result).contains("value: src/main/java/com/example/Foo.java");
        assertThat(result).contains("public class Foo { }");
    }

    @Test
    void buildMentionEvidence_fileMention_nullContent_skips() {
        MentionDTO m = new MentionDTO();
        m.setType("file");
        m.setValue("missing.java");

        when(symbolQueryService.getFileContent(any(), any(), eq("missing.java"), any(), any()))
                .thenReturn(null);

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));
        assertThat(result).isEmpty();
    }

    @Test
    void buildMentionEvidence_fileMention_contentTruncated() {
        MentionDTO m = new MentionDTO();
        m.setType("file");
        m.setValue("big.java");

        String bigContent = "x".repeat(9000);
        when(symbolQueryService.getFileContent(any(), any(), eq("big.java"), any(), any()))
                .thenReturn(FileContentVO.builder().filePath("big.java").content(bigContent).build());

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));
        assertThat(result).contains("[...文件已截断");
        // Should contain exactly 8000 chars of 'x' + truncation message
        assertThat(result).contains("x".repeat(100)); // spot-check content is present
    }

    // -----------------------------------------------------------------------
    // symbol mention
    // -----------------------------------------------------------------------

    @Test
    void buildMentionEvidence_symbolMention_withSummary_returnsBlock() {
        MentionDTO m = new MentionDTO();
        m.setType("symbol");
        m.setValue("UserService");

        SymbolVO sym = SymbolVO.builder()
                .id(1L)
                .className("UserService")
                .methodName(null)
                .signature("class UserService")
                .summary("管理用户增删改查的核心服务类")
                .build();
        when(symbolQueryService.searchSymbols(eq(1L), eq(5L), eq("UserService")))
                .thenReturn(List.of(sym));

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));

        assertThat(result).contains("[Mention-1]");
        assertThat(result).contains("type: symbol");
        assertThat(result).contains("value: UserService");
        assertThat(result).contains("UserService");
        assertThat(result).contains("管理用户增删改查的核心服务类");
    }

    @Test
    void buildMentionEvidence_symbolMention_classHashMethod_picksMatchingMethod() {
        MentionDTO m = new MentionDTO();
        m.setType("symbol");
        m.setValue("UserService#createUser");

        SymbolVO wrongMethod = SymbolVO.builder()
                .id(1L).className("UserService").methodName("deleteUser")
                .signature("void deleteUser(Long)").summary("删除用户").build();
        SymbolVO rightMethod = SymbolVO.builder()
                .id(2L).className("UserService").methodName("createUser")
                .signature("User createUser(UserCreateRequest)").summary("创建用户方法").build();
        when(symbolQueryService.searchSymbols(eq(1L), eq(5L), eq("UserService")))
                .thenReturn(List.of(wrongMethod, rightMethod));

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));

        assertThat(result).contains("createUser");
        assertThat(result).contains("创建用户方法");
        assertThat(result).doesNotContain("deleteUser");
    }

    @Test
    void buildMentionEvidence_symbolMention_noSymbolsFound_skips() {
        MentionDTO m = new MentionDTO();
        m.setType("symbol");
        m.setValue("NonExistentClass");

        when(symbolQueryService.searchSymbols(any(), any(), any())).thenReturn(List.of());

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));
        assertThat(result).isEmpty();
    }

    @Test
    void buildMentionEvidence_symbolMention_dotFormat_fallback_picksMatchingMethod() {
        // Simulates the legacy dot-format emitted by older frontend versions.
        // Whole-string search for "UserService.createUser" returns nothing;
        // fallback splits on last dot → searches "UserService", filters by "createUser".
        MentionDTO m = new MentionDTO();
        m.setType("symbol");
        m.setValue("UserService.createUser");

        // First call with the full string returns empty (not found as a class name).
        when(symbolQueryService.searchSymbols(eq(1L), eq(5L), eq("UserService.createUser")))
                .thenReturn(List.of());
        // Fallback call with the class part resolves to symbols.
        SymbolVO createUserSym = SymbolVO.builder()
                .id(10L).className("UserService").methodName("createUser")
                .signature("User createUser(UserCreateRequest)").summary("创建用户").build();
        SymbolVO deleteUserSym = SymbolVO.builder()
                .id(11L).className("UserService").methodName("deleteUser")
                .signature("void deleteUser(Long)").summary("删除用户").build();
        when(symbolQueryService.searchSymbols(eq(1L), eq(5L), eq("UserService")))
                .thenReturn(List.of(deleteUserSym, createUserSym));

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));

        assertThat(result).contains("createUser");
        assertThat(result).contains("创建用户");
        assertThat(result).doesNotContain("deleteUser");
    }

    // -----------------------------------------------------------------------
    // selection mention
    // -----------------------------------------------------------------------

    @Test
    void buildMentionEvidence_selectionMention_returnsBlock() {
        MentionDTO m = new MentionDTO();
        m.setType("selection");
        m.setExtra("String name = user.getName();");

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));

        assertThat(result).contains("[Mention-1]");
        assertThat(result).contains("type: selection");
        assertThat(result).contains("String name = user.getName();");
    }

    @Test
    void buildMentionEvidence_selectionMention_truncatedAt4000() {
        MentionDTO m = new MentionDTO();
        m.setType("selection");
        m.setExtra("y".repeat(5000));

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));
        assertThat(result).contains("[...选中内容已截断]");
    }

    @Test
    void buildMentionEvidence_selectionMention_emptyExtra_skips() {
        MentionDTO m = new MentionDTO();
        m.setType("selection");
        m.setExtra("");

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));
        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // unknown type
    // -----------------------------------------------------------------------

    @Test
    void buildMentionEvidence_unknownType_skips() {
        MentionDTO m = new MentionDTO();
        m.setType("branch");
        m.setValue("main");

        String result = injector.buildMentionEvidence(1L, 5L, List.of(m));
        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // multiple mentions, MAX cap, failure isolation
    // -----------------------------------------------------------------------

    @Test
    void buildMentionEvidence_multipleMentions_allIncluded() {
        MentionDTO f1 = new MentionDTO();
        f1.setType("file");
        f1.setValue("A.java");
        MentionDTO f2 = new MentionDTO();
        f2.setType("file");
        f2.setValue("B.java");

        when(symbolQueryService.getFileContent(any(), any(), eq("A.java"), any(), any()))
                .thenReturn(FileContentVO.builder().filePath("A.java").content("class A {}").build());
        when(symbolQueryService.getFileContent(any(), any(), eq("B.java"), any(), any()))
                .thenReturn(FileContentVO.builder().filePath("B.java").content("class B {}").build());

        String result = injector.buildMentionEvidence(1L, 5L, List.of(f1, f2));

        assertThat(result).contains("[Mention-1]");
        assertThat(result).contains("[Mention-2]");
        assertThat(result).contains("A.java");
        assertThat(result).contains("B.java");
    }

    @Test
    void buildMentionEvidence_moreThan5_onlyFirst5Used() {
        // Build 7 mentions
        List<MentionDTO> mentions = new java.util.ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            MentionDTO m = new MentionDTO();
            m.setType("selection");
            m.setExtra("selected text " + i);
            mentions.add(m);
        }

        String result = injector.buildMentionEvidence(1L, 5L, mentions);

        assertThat(result).contains("[Mention-5]");
        assertThat(result).doesNotContain("[Mention-6]");
        assertThat(result).doesNotContain("[Mention-7]");
    }

    @Test
    void buildMentionEvidence_exceptionInOneMention_othersStillProcessed() {
        MentionDTO failing = new MentionDTO();
        failing.setType("file");
        failing.setValue("throws.java");
        MentionDTO good = new MentionDTO();
        good.setType("selection");
        good.setExtra("safe content");

        when(symbolQueryService.getFileContent(any(), any(), eq("throws.java"), any(), any()))
                .thenThrow(new RuntimeException("simulated IO error"));

        String result = injector.buildMentionEvidence(1L, 5L, List.of(failing, good));

        // failing mention is skipped; good mention still appears
        assertThat(result).doesNotContain("throws.java");
        assertThat(result).contains("safe content");
    }
}
