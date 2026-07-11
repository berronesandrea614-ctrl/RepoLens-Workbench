package com.repolens.service;

import com.repolens.common.util.HashUtils;
import com.repolens.domain.entity.AiContributionRecordEntity;
import com.repolens.mapper.AiContributionRecordMapper;
import com.repolens.mapper.AppSettingMapper;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.service.impl.ProvenanceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * TDD 单测：Feature F 哈希链核心逻辑。
 *
 * 覆盖：
 * 1. sha256Chain 规范化（与 sha256(parts 拼接) 一致）
 * 2. appendRecordSafe 首条 genesis 哈希
 * 3. appendRecordSafe 第二条链接前一条
 * 4. appendRecordSafe 失败安全（不抛出）
 * 5. verifyChain 正常链验证
 * 6. verifyChain 检测到篡改
 * 7. verifyChain 空链返回 verified=true
 */
class ProvenanceServiceImplTest {

    private AiContributionRecordMapper recordMapper;
    private AppSettingMapper appSettingMapper;
    private LlmCallLogMapper llmCallLogMapper;
    private ProvenanceServiceImpl service;

    @BeforeEach
    void setup() {
        recordMapper = mock(AiContributionRecordMapper.class);
        appSettingMapper = mock(AppSettingMapper.class);
        llmCallLogMapper = mock(LlmCallLogMapper.class);
        service = new ProvenanceServiceImpl(recordMapper, appSettingMapper, llmCallLogMapper);
    }

    // ─── HashUtils.sha256Chain ────────────────────────────────────────────────

    @Test
    void sha256Chain_deterministicAndMatchesManualJoin() {
        String h = HashUtils.sha256Chain("abc", "def", "123");
        assertThat(h).hasSize(64);
        assertThat(h).isEqualTo(HashUtils.sha256("abc|def|123"));
    }

    @Test
    void sha256Chain_nullPartsReplacedWithEmpty() {
        String h = HashUtils.sha256Chain(null, "hello");
        assertThat(h).isEqualTo(HashUtils.sha256("|hello"));
    }

    @Test
    void sha256Chain_singlePart_noDelimiter() {
        String h = HashUtils.sha256Chain("onlyone");
        assertThat(h).isEqualTo(HashUtils.sha256("onlyone"));
    }

    // ─── appendRecordSafe: genesis record ────────────────────────────────────

    @Test
    void appendRecordSafe_firstRecord_usesGenesisHash() {
        // No existing records
        when(recordMapper.selectMaxSeqByRepoId(1L)).thenReturn(null);
        when(recordMapper.selectLastRecordHash(1L)).thenReturn(null);
        when(appSettingMapper.selectById(anyString())).thenReturn(null);

        service.appendRecordSafe(1L, 10L, "src/A.java", "old", "new", null, null, "APPROVED", 42L);

        ArgumentCaptor<AiContributionRecordEntity> captor = ArgumentCaptor.forClass(AiContributionRecordEntity.class);
        verify(recordMapper).insert(captor.capture());
        AiContributionRecordEntity saved = captor.getValue();

        assertThat(saved.getSeq()).isEqualTo(1L);
        assertThat(saved.getPrevHash()).isEqualTo(AiContributionRecordEntity.GENESIS_HASH);
        assertThat(saved.getRecordHash()).hasSize(64);
        assertThat(saved.getDecision()).isEqualTo("APPROVED");
        assertThat(saved.getApproverId()).isEqualTo(42L);
        assertThat(saved.getChangeId()).isEqualTo(10L);
        assertThat(saved.getFilePath()).isEqualTo("src/A.java");

        // Verify record_hash matches recomputed value
        String expectedHash = HashUtils.sha256Chain(
                AiContributionRecordEntity.GENESIS_HASH,
                "1",
                "1",
                "10",
                "APPROVED",
                "42",
                saved.getDecidedAt().toString()
        );
        assertThat(saved.getRecordHash()).isEqualTo(expectedHash);
    }

    @Test
    void appendRecordSafe_secondRecord_chainsFromPreviousHash() {
        String prevHash = HashUtils.sha256("some-existing-hash");
        when(recordMapper.selectMaxSeqByRepoId(1L)).thenReturn(1L);
        when(recordMapper.selectLastRecordHash(1L)).thenReturn(prevHash);
        when(appSettingMapper.selectById(anyString())).thenReturn(null);

        service.appendRecordSafe(1L, 11L, "src/B.java", "x", "y", null, null, "REJECTED", 99L);

        ArgumentCaptor<AiContributionRecordEntity> captor = ArgumentCaptor.forClass(AiContributionRecordEntity.class);
        verify(recordMapper).insert(captor.capture());
        AiContributionRecordEntity saved = captor.getValue();

        assertThat(saved.getSeq()).isEqualTo(2L);
        assertThat(saved.getPrevHash()).isEqualTo(prevHash);
        assertThat(saved.getDecision()).isEqualTo("REJECTED");
    }

    @Test
    void appendRecordSafe_promptSnapshotNull_whenPlaintextDisabled() {
        when(recordMapper.selectMaxSeqByRepoId(1L)).thenReturn(null);
        // "audit.capture-plaintext" not set → defaults to false (privacy red line)
        when(appSettingMapper.selectById("audit.capture-plaintext")).thenReturn(null);

        service.appendRecordSafe(1L, 5L, "f.java", "a", "b", null, null, "APPROVED", 1L);

        ArgumentCaptor<AiContributionRecordEntity> captor = ArgumentCaptor.forClass(AiContributionRecordEntity.class);
        verify(recordMapper).insert(captor.capture());
        assertThat(captor.getValue().getPromptSnapshot()).isNull();
    }

    // ─── appendRecordSafe: failure-safe ──────────────────────────────────────

    @Test
    void appendRecordSafe_mapperThrows_doesNotPropagateException() {
        when(recordMapper.selectMaxSeqByRepoId(anyLong())).thenThrow(new RuntimeException("DB error"));

        assertThatCode(() ->
                service.appendRecordSafe(1L, 1L, "f.java", "a", "b", null, null, "APPROVED", 1L)
        ).doesNotThrowAnyException();
    }

    // ─── verifyChain ─────────────────────────────────────────────────────────

    @Test
    void verifyChain_emptyChain_returnsVerified() {
        when(recordMapper.selectAllByRepoIdOrderBySeq(1L)).thenReturn(List.of());

        ProvenanceService.VerifyResult result = service.verifyChain(1L);
        assertThat(result.verified()).isTrue();
        assertThat(result.brokenAtSeq()).isNull();
    }

    @Test
    void verifyChain_validChain_returnsVerified() {
        // Create a valid 2-record chain
        LocalDateTime t1 = LocalDateTime.of(2026, 7, 5, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 7, 5, 10, 1, 0);

        String prevHash1 = AiContributionRecordEntity.GENESIS_HASH;
        String recordHash1 = HashUtils.sha256Chain(prevHash1, "1", "1", "10", "APPROVED", "42", t1.toString());

        String recordHash2 = HashUtils.sha256Chain(recordHash1, "1", "2", "11", "REJECTED", "42", t2.toString());

        AiContributionRecordEntity r1 = new AiContributionRecordEntity();
        r1.setRepoId(1L); r1.setSeq(1L); r1.setChangeId(10L);
        r1.setDecision("APPROVED"); r1.setApproverId(42L); r1.setDecidedAt(t1);
        r1.setPrevHash(prevHash1); r1.setRecordHash(recordHash1);

        AiContributionRecordEntity r2 = new AiContributionRecordEntity();
        r2.setRepoId(1L); r2.setSeq(2L); r2.setChangeId(11L);
        r2.setDecision("REJECTED"); r2.setApproverId(42L); r2.setDecidedAt(t2);
        r2.setPrevHash(recordHash1); r2.setRecordHash(recordHash2);

        when(recordMapper.selectAllByRepoIdOrderBySeq(1L)).thenReturn(List.of(r1, r2));

        ProvenanceService.VerifyResult result = service.verifyChain(1L);
        assertThat(result.verified()).isTrue();
    }

    @Test
    void verifyChain_tamperedRecord_returnsBrokenAtSeq() {
        LocalDateTime t1 = LocalDateTime.of(2026, 7, 5, 10, 0, 0);
        String prevHash1 = AiContributionRecordEntity.GENESIS_HASH;
        String originalHash = HashUtils.sha256Chain(prevHash1, "1", "1", "10", "APPROVED", "42", t1.toString());

        AiContributionRecordEntity r1 = new AiContributionRecordEntity();
        r1.setRepoId(1L); r1.setSeq(1L); r1.setChangeId(10L);
        r1.setDecision("APPROVED"); r1.setApproverId(42L); r1.setDecidedAt(t1);
        r1.setPrevHash(prevHash1);
        r1.setRecordHash("tampered000000000000000000000000000000000000000000000000000000"); // tampered!

        when(recordMapper.selectAllByRepoIdOrderBySeq(1L)).thenReturn(List.of(r1));

        ProvenanceService.VerifyResult result = service.verifyChain(1L);
        assertThat(result.verified()).isFalse();
        assertThat(result.brokenAtSeq()).isEqualTo(1L);
    }
}
