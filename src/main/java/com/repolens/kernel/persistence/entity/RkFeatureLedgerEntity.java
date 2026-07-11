package com.repolens.kernel.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * failing-until-tested 特性台账（表 {@code rk_feature_ledger}）。
 *
 * <p>DeepSeek 版最大的病：功能都"在"却是空壳（假流式、假语义召回、缺真 E2E）。
 * 本台账是防假实现的硬约束：每条特性默认 {@code FAILING}，只有挂上真实通过的
 * {@link RkVerificationRunEntity} 凭据（{@code verificationId} 指向 passed=1 的运行）
 * 才准转 {@code PASSING}。{@code tamperSeal} = sha256(featureKey+status+evidence)，
 * 防止模型偷改状态而不附证据。
 */
@Data
@TableName("rk_feature_ledger")
public class RkFeatureLedgerEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long repoId;

    private Long sessionId;

    private Long runId;

    /** 特性稳定标识（唯一键的一部分）。 */
    private String featureKey;

    private String description;

    /** FAILING / PASSING，默认 FAILING（铁律）。 */
    private String status;

    /** 关联的真实测试标识。 */
    private String testRef;

    /** 通过证据：真实测试输出 + exit code。 */
    private String evidence;

    /** 关联 {@link RkVerificationRunEntity}.id（绿灯挂真凭据）。 */
    private Long verificationId;

    /** sha256(featureKey + status + evidence)，防模型偷改状态。 */
    private String tamperSeal;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
