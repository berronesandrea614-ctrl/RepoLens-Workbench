package com.repolens.kernel.ledger;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repolens.kernel.persistence.entity.RkFeatureLedgerEntity;
import com.repolens.kernel.persistence.entity.RkVerificationRunEntity;
import com.repolens.kernel.persistence.mapper.RkFeatureLedgerMapper;
import com.repolens.kernel.persistence.mapper.RkVerificationRunMapper;
import com.repolens.kernel.verify.VerificationOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * failing-until-tested 特性台账：防"功能都在却是空壳"的 DeepSeek 式假实现。
 *
 * <p>铁律：
 * <ul>
 *   <li>每条特性 {@link #declare} 后默认 {@code FAILING}。</li>
 *   <li>{@link #markPassing} 只接受挂着<b>真实且已落库通过</b>的 {@link RkVerificationRunEntity} 凭据的转绿请求——
 *       服务端**重新按 verificationId 查库核对 passed=1**，不信调用方口头声称，杜绝"自报通过"。</li>
 *   <li>转绿时写 {@code tamperSeal = sha256(featureKey|status|evidence)}，任何事后偷改状态/证据都能被 {@link #isTampered} 抓出。</li>
 * </ul>
 */
@Slf4j
@Service("kernelFeatureLedgerService")
public class FeatureLedgerService {

    private final RkFeatureLedgerMapper ledgerMapper;
    private final RkVerificationRunMapper verificationRunMapper;

    public FeatureLedgerService(RkFeatureLedgerMapper ledgerMapper,
                                RkVerificationRunMapper verificationRunMapper) {
        this.ledgerMapper = ledgerMapper;
        this.verificationRunMapper = verificationRunMapper;
    }

    /** 登记一条特性（默认 FAILING）；同 (repoId,sessionId,featureKey) 幂等，已存在则返回既有。 */
    public RkFeatureLedgerEntity declare(Long repoId, Long sessionId, Long runId,
                                         String featureKey, String description) {
        RkFeatureLedgerEntity exist = findByKey(repoId, sessionId, featureKey);
        if (exist != null) {
            return exist;
        }
        RkFeatureLedgerEntity e = new RkFeatureLedgerEntity();
        e.setRepoId(repoId);
        e.setSessionId(sessionId);
        e.setRunId(runId);
        e.setFeatureKey(featureKey);
        e.setDescription(description);
        e.setStatus("FAILING");
        e.setTamperSeal(seal(featureKey, "FAILING", ""));
        ledgerMapper.insert(e);
        return e;
    }

    /**
     * 尝试把特性转绿。只有 outcome 已落库（verificationId 非空）且库里那条 passed=1 才准通过；
     * 否则抛异常拒绝——这正是"failing-until-tested / 绿灯挂真凭据"的强制点。
     */
    public RkFeatureLedgerEntity markPassing(Long repoId, Long sessionId, String featureKey,
                                             VerificationOutcome outcome) {
        if (outcome == null || outcome.verificationId() == null) {
            throw new IllegalStateException("拒绝转绿[" + featureKey + "]：无验证运行凭据(verificationId 为空)");
        }
        RkVerificationRunEntity vr = verificationRunMapper.selectById(outcome.verificationId());
        if (vr == null || !Boolean.TRUE.equals(vr.getPassed()) || vr.getExitCode() == null || vr.getExitCode() != 0) {
            throw new IllegalStateException("拒绝转绿[" + featureKey + "]：验证运行 #"
                    + outcome.verificationId() + " 未真实通过(failing-until-tested)");
        }
        RkFeatureLedgerEntity e = findByKey(repoId, sessionId, featureKey);
        if (e == null) {
            throw new IllegalStateException("特性未登记，不能转绿: " + featureKey);
        }
        String evidence = "exit=" + vr.getExitCode() + "; target=" + vr.getBuildTarget()
                + "; kind=" + vr.getKind() + "; networkIsolated=" + vr.getNetworkIsolated()
                + "; tail=" + safeTail(vr.getOutputTail());
        e.setStatus("PASSING");
        e.setVerificationId(vr.getId());
        e.setTestRef(vr.getBuildTarget() + ":" + vr.getKind() + "#" + vr.getId());
        e.setEvidence(evidence);
        e.setTamperSeal(seal(featureKey, "PASSING", evidence));
        ledgerMapper.updateById(e);
        log.info("[ledger] 特性[{}] 转绿，凭 verification #{}", featureKey, vr.getId());
        return e;
    }

    public RkFeatureLedgerEntity findByKey(Long repoId, Long sessionId, String featureKey) {
        return ledgerMapper.selectOne(new LambdaQueryWrapper<RkFeatureLedgerEntity>()
                .eq(RkFeatureLedgerEntity::getRepoId, repoId)
                .eq(RkFeatureLedgerEntity::getSessionId, sessionId)
                .eq(RkFeatureLedgerEntity::getFeatureKey, featureKey));
    }

    /** 校验封印：状态/证据被事后偷改则返回 true。 */
    public boolean isTampered(RkFeatureLedgerEntity e) {
        if (e == null || e.getTamperSeal() == null) {
            return false;
        }
        String expect = seal(e.getFeatureKey(), e.getStatus(), e.getEvidence() == null ? "" : e.getEvidence());
        return !expect.equals(e.getTamperSeal());
    }

    private String seal(String featureKey, String status, String evidence) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(
                    (featureKey + "|" + status + "|" + evidence).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String safeTail(String tail) {
        if (tail == null) {
            return "";
        }
        return tail.length() <= 500 ? tail : tail.substring(tail.length() - 500);
    }
}
