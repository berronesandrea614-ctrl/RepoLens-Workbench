package com.repolens.service.impl;

import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.GraphExplainRequest;
import com.repolens.llm.LlmClient;
import com.repolens.llm.LlmClientException;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.llm.model.LlmResponse;
import com.repolens.security.PermissionService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GraphExplainServiceImpl 单测（mock LlmClient + PermissionService）。验证：
 * (a) 有权限且 LLM 正常 -> 返回 LLM 内容；
 * (b) LLM 抛异常 -> 返回降级文案（不冒泡）；
 * (c) 无权限 -> 抛 FORBIDDEN，且不触碰 LLM。
 */
@ExtendWith(MockitoExtension.class)
class GraphExplainServiceImplTest {

    private static final String FALLBACK = "（暂无法生成流程解说，请稍后重试或检查模型配置）";

    @Mock
    private PermissionService permissionService;

    @Mock
    private LlmClient llmClient;

    private GraphExplainServiceImpl newService() {
        LlmRuntimeConfig cfg = new LlmRuntimeConfig(null);
        ReflectionTestUtils.setField(cfg, "modelName", "mock-code-assistant");
        ReflectionTestUtils.setField(cfg, "timeoutMs", 15000);
        return new GraphExplainServiceImpl(permissionService, llmClient, cfg);
    }

    private GraphExplainRequest sampleReq() {
        GraphExplainRequest req = new GraphExplainRequest();
        req.setRootLabel("UserController.getUser [Controller]");
        req.setNodes(List.of("UserController.getUser [Controller]", "UserService.getUser [Service]"));
        req.setEdges(List.of("UserController.getUser -> UserService.getUser"));
        return req;
    }

    @Test
    void explain_returnsLlmContentOnSuccess() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);
        when(llmClient.generate(any())).thenReturn(LlmResponse.builder()
                .content("请求从 Controller 进入，经 Service 层取用户后返回。")
                .success(true)
                .build());

        String out = newService().explain(1L, 7L, sampleReq());

        Assertions.assertEquals("请求从 Controller 进入，经 Service 层取用户后返回。", out);
    }

    @Test
    void explain_returnsFallbackWhenLlmThrows() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);
        when(llmClient.generate(any()))
                .thenThrow(new LlmClientException("LLM_HTTP_ERROR", "boom"));

        String out = newService().explain(1L, 7L, sampleReq());

        Assertions.assertEquals(FALLBACK, out);
    }

    @Test
    void explain_throwsForbiddenWhenNoPermission() {
        when(permissionService.checkRepoPermission(2L, 7L)).thenReturn(false);

        GraphExplainServiceImpl service = newService();
        GraphExplainRequest req = sampleReq();

        Assertions.assertThrows(BizException.class, () -> service.explain(2L, 7L, req));
        verify(llmClient, never()).generate(any());
    }

    @Test
    void explain_withSubsetNodes_promptOnlyContainsProvidedNodes() {
        when(permissionService.checkRepoPermission(1L, 7L)).thenReturn(true);
        // Capture the LlmRequest sent to the client so we can inspect the prompt
        when(llmClient.generate(any())).thenAnswer(invocation -> {
            com.repolens.llm.model.LlmRequest req = invocation.getArgument(0);
            // Return the user-prompt as content so we can assert on it
            return LlmResponse.builder().content(req.getUserPrompt()).success(true).build();
        });

        GraphExplainRequest req = new GraphExplainRequest();
        req.setRootLabel("UserController.getUser [Controller]");
        req.setNodes(List.of("UserService.getUser [Service]")); // only 1 visible node
        req.setEdges(List.of("UserController.getUser -> UserService.getUser")); // 1 visible edge

        String out = newService().explain(1L, 7L, req);

        // The prompt must include the single node we passed (visible subgraph)
        org.junit.jupiter.api.Assertions.assertTrue(out.contains("UserService.getUser"),
                "prompt should contain the visible node");
        // And must NOT include nodes we did NOT pass
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("UserMapper.selectById"),
                "prompt must not contain nodes outside the visible subgraph");
    }
}
