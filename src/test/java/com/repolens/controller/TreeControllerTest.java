package com.repolens.controller;

import com.repolens.domain.vo.FileTreeNodeVO;
import com.repolens.service.RepoFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.repolens.controller.TestAuthUtils;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc slice test for TreeController.
 *
 * @WebMvcTest was attempted but the project's @MapperScan on @SpringBootApplication causes
 * MyBatis MapperFactoryBean beans to be registered in the web-layer context without a
 * SqlSessionFactory, crashing context load. Switching to standaloneSetup avoids Spring
 * context loading entirely and is functionally equivalent: all @RequestHeader,
 * @PathVariable, and JSON serialisation are handled by the same Spring MVC infrastructure.
 * No security filters are needed because the SecurityConfig permits all requests anyway.
 */
@ExtendWith(MockitoExtension.class)
class TreeControllerTest {

    @Mock
    private RepoFileService repoFileService;

    @InjectMocks
    private TreeController treeController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(treeController)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void returnsTreeJson() throws Exception {
        FileTreeNodeVO child = FileTreeNodeVO.builder()
                .name("A.java").path("src/A.java").directory(false).build();
        FileTreeNodeVO src = FileTreeNodeVO.builder()
                .name("src").path("src").directory(true).children(List.of(child)).build();
        FileTreeNodeVO root = FileTreeNodeVO.builder()
                .name("").path("").directory(true).children(List.of(src)).build();
        when(repoFileService.listTree(eq(1L), eq(7L))).thenReturn(root);

        mockMvc.perform(get("/api/repos/7/tree")
                        .header("X-User-Id", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.directory").value(true))
                .andExpect(jsonPath("$.data.children[0].name").value("src"))
                .andExpect(jsonPath("$.data.children[0].children[0].path").value("src/A.java"));
    }
}
