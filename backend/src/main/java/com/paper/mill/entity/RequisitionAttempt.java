package com.paper.mill.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 切纸工段 · 领料请求留痕。
 *
 * <p>每一次领料请求（无论成功失败）落一行，承担两件事：</p>
 * <ol>
 *   <li><b>幂等</b>：{@code requestToken} 唯一，车间重复点击、网络重试导致同一请求重复到达时，
 *       直接返回该令牌上一次的结果，不会重复写领料记录、不会把母卷扣两次；</li>
 *   <li><b>失败原因持久化</b>：校验不通过时不产生领料记录、不动余量，但明确原因写入
 *       {@code failReason}，操作员刷新或重新打开页面仍能看到真实余量与失败原因。</li>
 * </ol>
 *
 * <p>成功行通过 {@link #requisition} 回链领料账本；失败行该字段为空，订单/母卷列也允许为空
 * （失败本身可能就是因为订单或母卷不存在）。</p>
 */
@Entity
@Table(name = "requisition_attempt")
@Getter
@Setter
public class RequisitionAttempt {

    /** 成功 */
    public static final String SUCCESS = "SUCCESS";
    /** 失败（未写领料记录、未动余量） */
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 请求幂等令牌，一次点击一个，唯一 */
    @Column(name = "request_token", nullable = false, unique = true, length = 64)
    private String requestToken;

    // 失败时订单/母卷可能不存在，故可为空；EAGER 便于追溯接口直接展示编号
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    private CuttingOrder order;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "roll_id")
    private PaperRoll roll;

    /** 成功时回链领料账本记录 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "requisition_id")
    private CuttingRequisition requisition;

    @Column(name = "length_m")
    private Integer lengthM;

    @Column(name = "weight_kg", precision = 10, scale = 2)
    private BigDecimal weightKg;

    @Column(name = "operator", length = 32)
    private String operator;

    /** SUCCESS / FAILED */
    @Column(name = "result", nullable = false, length = 8)
    private String result;

    /** 失败明确原因，多条以「；」连接 */
    @Column(name = "fail_reason", length = 512)
    private String failReason;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @Transient
    public String getOrderNo() {
        return order == null ? null : order.getOrderNo();
    }

    @Transient
    public String getRollNo() {
        return roll == null ? null : roll.getRollNo();
    }

    @Transient
    public Long getRequisitionId() {
        return requisition == null ? null : requisition.getId();
    }
}
