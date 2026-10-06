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
 * 切纸工段 · 切裁领料记录（闭环账本）。
 *
 * <p>处于「切裁中」的订单从一卷「可用」且规格匹配的母卷上领料一次，就落一条本记录。
 * 记录保存订单、母卷、本次长度、本次重量、操作时间与操作人，并定格扣减后的母卷余量，
 * 是订单详情与母卷导轨双向追溯的唯一凭据。</p>
 *
 * <p>本账本只增不改：母卷随后转为「已切完」或订单完成/取消，已成功的记录都不会消失。
 * 记录的写入与母卷余量扣减在同一个事务内完成，不会出现有记录没扣量或扣两次。</p>
 */
@Entity
@Table(name = "cutting_requisition")
@Getter
@Setter
public class CuttingRequisition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 领料单号，唯一 */
    @Column(name = "requisition_no", nullable = false, unique = true, length = 32)
    private String requisitionNo;

    // EAGER：订单详情/母卷导轨追溯时直接带出订单号、纸卷号
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private CuttingOrder order;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "roll_id", nullable = false)
    private PaperRoll roll;

    /** 本次领料长度（米） */
    @Column(name = "length_m", nullable = false)
    private Integer lengthM;

    /** 本次领料重量（千克） */
    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal weightKg;

    /** 操作人 */
    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    /** 操作时间 */
    @Column(name = "operated_at", nullable = false)
    private LocalDateTime operatedAt;

    /** 本次扣减后母卷剩余长度（米），定格值，便于追溯当时余量 */
    @Column(name = "remain_length_m", nullable = false)
    private Integer remainLengthM;

    /** 本次扣减后母卷剩余重量（千克），定格值 */
    @Column(name = "remain_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal remainWeightKg;

    /** 所属订单号，派生字段 */
    @Transient
    public String getOrderNo() {
        return order == null ? null : order.getOrderNo();
    }

    /** 所属母卷号，派生字段 */
    @Transient
    public String getRollNo() {
        return roll == null ? null : roll.getRollNo();
    }
}
