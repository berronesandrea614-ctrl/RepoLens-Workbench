package com.repolens.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.FeishuBindingEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.FeishuBindingVO;
import com.repolens.mapper.FeishuBindingMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.mcp.McpUiActionBroker;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.support.ClaudeOutputParser;
import com.repolens.service.support.CryptoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link FeishuBridgeService}.
 * All external dependencies are mocked; no Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
class FeishuBridgeServiceTest {

    @Mock FeishuClient        feishuClient;
    @Mock FeishuBindingMapper feishuBindingMapper;
    @Mock CryptoService       cryptoService;
    @Mock McpUiActionBroker   mcpUiActionBroker;
    @Mock ToolCallLogMapper   toolCallLogMapper;
    @Mock PermissionService   permissionService;
    @Mock com.repolens.service.impl.support.CommandRunner commandRunner;
    @Mock com.repolens.service.support.RepoWorkspaceResolver workspaceResolver;
    @Mock com.repolens.mapper.RepoMapper repoMapper;
    @Mock com.repolens.common.util.RepoUrlValidator repoUrlValidator;

    private FeishuBridgeService service;

    @BeforeEach
    void setUp() {
        service = new FeishuBridgeService(
                feishuClient, feishuBindingMapper, cryptoService,
                mcpUiActionBroker, toolCallLogMapper, permissionService,
                commandRunner, workspaceResolver, repoMapper, repoUrlValidator);
    }

    // ── create: encrypted secret ──────────────────────────────────────────────

    @Test
    void create_storesEncryptedSecret_notPlaintext() throws Exception {
        // Arrange
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        when(feishuBindingMapper.selectCount(any())).thenReturn(0L);
        when(cryptoService.encrypt("plainSecret")).thenReturn("ENCRYPTED_VALUE");
        doNothing().when(feishuClient).connect(any(), any(), any());

        // Act
        service.create(1L, 10L, "MyBot", "app123", "plainSecret");

        // Assert: the entity persisted should have the encrypted value, NOT the plaintext
        ArgumentCaptor<FeishuBindingEntity> captor = ArgumentCaptor.forClass(FeishuBindingEntity.class);
        verify(feishuBindingMapper).insert(captor.capture());
        FeishuBindingEntity saved = captor.getValue();
        assertThat(saved.getAppSecretEnc()).isEqualTo("ENCRYPTED_VALUE");
        assertThat(saved.getAppSecretEnc()).isNotEqualTo("plainSecret");
        assertThat(saved.getAppId()).isEqualTo("app123");
        assertThat(saved.getRepoId()).isEqualTo(10L);
    }

    @Test
    void create_voDoesNotContainSecret() throws Exception {
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        when(feishuBindingMapper.selectCount(any())).thenReturn(0L);
        when(cryptoService.encrypt("plainSecret")).thenReturn("ENC");
        doNothing().when(feishuClient).connect(any(), any(), any());

        FeishuBindingVO vo = service.create(1L, 10L, "Bot", "app1", "plainSecret");

        assertThat(vo).isNotNull();
        assertThat(vo.getAppId()).isEqualTo("app1");
        // VO should not expose secret fields — check via reflection that there's no secret field
        // (FeishuBindingVO does not declare appSecretEnc)
        assertThatCode(() -> FeishuBindingVO.class.getDeclaredField("appSecretEnc"))
                .isInstanceOf(NoSuchFieldException.class);
    }

    // ── onFeishuMessage → broker.push ─────────────────────────────────────────

    @Test
    void onFeishuMessage_pushesViaBroker() throws Exception {
        // Arrange: set up an active binding
        FeishuBindingEntity binding = makeBinding(1L, 10L, "app1", "ENC");
        service.activeBindings.put(10L, binding);

        when(cryptoService.decrypt("ENC")).thenReturn("secret");
        when(mcpUiActionBroker.push(anyString(), anyMap())).thenReturn(true);

        // Act: simulate message received from Feishu
        // We call onPtyOutput and the message route via connectBinding in integration,
        // but we need to test onFeishuMessage indirectly.
        // Use connectBinding to wire up onFeishuMessage, but that requires feishuClient.connect()
        // to capture the callback. Let's do it via create() flow.

        // Reset and redo with callback capture
        ArgumentCaptor<java.util.function.Consumer<String>> callbackCaptor =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        when(feishuBindingMapper.selectCount(any())).thenReturn(0L);
        when(cryptoService.encrypt("secret")).thenReturn("ENC");
        doNothing().when(feishuClient).connect(eq("app1"), eq("secret"), callbackCaptor.capture());

        service.create(1L, 10L, "Bot", "app1", "secret");

        // Trigger the callback as if Feishu sent a message
        java.util.function.Consumer<String> callback = callbackCaptor.getValue();
        callback.accept("ls -la");

        // Assert broker was called with expected action and params
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mcpUiActionBroker).push(eq("feishu_input"), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertThat(params.get("repoId")).isEqualTo(10L);
        assertThat(params.get("text")).isEqualTo("ls -la");
        assertThat(params.get("ptyId")).isEqualTo(10010L);
    }

    @Test
    void onFeishuMessage_brokerReturnsFalse_sendsFallbackToFeishu() throws Exception {
        ArgumentCaptor<java.util.function.Consumer<String>> callbackCaptor =
                ArgumentCaptor.forClass(java.util.function.Consumer.class);
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        when(feishuBindingMapper.selectCount(any())).thenReturn(0L);
        when(cryptoService.encrypt("secret")).thenReturn("ENC");
        when(cryptoService.decrypt("ENC")).thenReturn("secret");
        doNothing().when(feishuClient).connect(eq("app1"), eq("secret"), callbackCaptor.capture());
        when(mcpUiActionBroker.push(anyString(), anyMap())).thenReturn(false);

        service.create(1L, 10L, "Bot", "app1", "secret");
        callbackCaptor.getValue().accept("any command");

        verify(feishuClient).sendMessage(eq("app1"), eq("secret"), contains("未在前台"));
    }

    // ── onPtyOutput → flush → sendMessage ────────────────────────────────────

    @Test
    void onPtyOutput_accumulatesAndFlush_sendsToFeishu() throws Exception {
        // Arrange
        FeishuBindingEntity binding = makeBinding(1L, 10L, "app1", "ENC");
        service.activeBindings.put(10L, binding);
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        when(cryptoService.decrypt("ENC")).thenReturn("decrypted");

        // Feed a chunk
        service.onPtyOutput(1L, 10L, "Hello from Claude output");

        // Wait > 300ms idle threshold so flushIfIdle returns a Turn
        Thread.sleep(400);
        service.flushAllParsers();

        // Verify sendMessage was called
        verify(feishuClient).sendMessage(eq("app1"), eq("decrypted"), any(String.class));
    }

    @Test
    void onPtyOutput_noActiveBinding_doesNothing() {
        // No binding in activeBindings map → should silently do nothing
        when(permissionService.checkRepoPermission(1L, 99L)).thenReturn(true);
        assertThatCode(() -> service.onPtyOutput(1L, 99L, "chunk"))
                .doesNotThrowAnyException();
        verifyNoInteractions(feishuClient);
    }

    @Test
    void onPtyOutput_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(2L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.onPtyOutput(2L, 10L, "chunk"))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(40300); // FORBIDDEN
    }

    // ── testConnection ────────────────────────────────────────────────────────

    @Test
    void testConnection_success_returnsTrue() throws Exception {
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        doNothing().when(feishuClient).connect(any(), any(), any());

        boolean result = service.testConnection(1L, 10L, "app1", "secret");

        assertThat(result).isTrue();
        verify(feishuClient).connect(eq("app1"), eq("secret"), any());
        verify(feishuClient).disconnect("app1");
    }

    @Test
    void testConnection_failure_returnsFalse() throws Exception {
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        doThrow(new RuntimeException("connection refused"))
                .when(feishuClient).connect(any(), any(), any());

        boolean result = service.testConnection(1L, 10L, "app1", "badSecret");

        assertThat(result).isFalse();
    }

    // ── Permission check ──────────────────────────────────────────────────────

    @Test
    void create_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(1L, 10L, "Bot", "app1", "secret"))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(40300); // FORBIDDEN code
    }

    @Test
    void list_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.list(1L, 10L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(40300);
    }

    @Test
    void testConnection_noPermission_throwsForbidden() {
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.testConnection(1L, 10L, "app1", "secret"))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(40300);
    }

    // ── delete: IDOR / ownership tests ───────────────────────────────────────

    @Test
    void delete_userIdMismatch_throwsForbidden() {
        when(permissionService.checkRepoPermission(2L, 10L)).thenReturn(true);
        // Binding belongs to userId=1, but caller is userId=2
        FeishuBindingEntity binding = makeBinding(1L, 10L, "app1", "ENC");
        when(feishuBindingMapper.selectById(42L)).thenReturn(binding);

        assertThatThrownBy(() -> service.delete(2L, 10L, 42L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(40300); // FORBIDDEN
    }

    @Test
    void delete_repoIdMismatch_throwsForbidden() {
        // Caller has permission on repoId=10, but binding belongs to repoId=99 (cross-repo)
        when(permissionService.checkRepoPermission(1L, 10L)).thenReturn(true);
        FeishuBindingEntity binding = makeBinding(1L, 99L, "app1", "ENC");
        when(feishuBindingMapper.selectById(42L)).thenReturn(binding);

        assertThatThrownBy(() -> service.delete(1L, 10L, 42L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(40300); // FORBIDDEN
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static FeishuBindingEntity makeBinding(Long userId, Long repoId, String appId, String enc) {
        FeishuBindingEntity e = new FeishuBindingEntity();
        e.setId(1L);
        e.setUserId(userId);
        e.setRepoId(repoId);
        e.setAppId(appId);
        e.setAppSecretEnc(enc);
        e.setStatus("CONNECTED");
        e.setBotName("TestBot");
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }
}
