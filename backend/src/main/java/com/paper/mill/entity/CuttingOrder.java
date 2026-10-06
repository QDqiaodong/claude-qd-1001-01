package com.paper.mill.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 切纸工段 · 切纸订单。
 *
 * <p>客户按「定量 + 幅宽 + 重量」下切纸订单，车间挑母卷上切纸机裁切。
 * 状态流转：待排产 → 切裁中 → 已完成（不可跳级），可旁路置为已取消。</p>
 */
@Entity
@Table(name = "cutting_order")
@Getter
@Setter
public class CuttingOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单号，唯一 */
    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @Column(name = "customer_name", nullable = false, length = 64)
    private String customerName;

    /** 目标定量（g/m2） */
    @Column(name = "target_grammage", nullable = false)
    private Integer targetGrammage;

    /** 目标幅宽（毫米），必须 <= 母卷幅宽 */
    @Column(name = "target_width_mm", nullable = false)
    private Integer targetWidthMm;

    /** 目标重量（千克） */
    @Column(name = "target_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal targetWeightKg;

    /** 已入库重量（千克），入库时累加 */
    @Column(name = "done_weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal doneWeightKg;

    /** 待排产 / 切裁中 / 已完成 / 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "deadline")
    private LocalDate deadline;

    /** 欠量（千克），派生字段：目标 - 已入库 */
    @Transient
    public BigDecimal getGapWeightKg() {
        if (targetWeightKg == null || doneWeightKg == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal gap = targetWeightKg.subtract(doneWeightKg);
        return gap.signum() > 0 ? gap : BigDecimal.ZERO;
    }

    /** 完成率（%），派生字段 */
    @Transient
    public Double getProgressRate() {
        if (targetWeightKg == null || targetWeightKg.signum() <= 0 || doneWeightKg == null) {
            return 0.0;
        }
        return doneWeightKg.multiply(BigDecimal.valueOf(100))
                .divide(targetWeightKg, 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 距交期天数，派生字段；负数表示已逾期 */
    @Transient
    public Integer getDaysToDeadline() {
        if (deadline == null) {
            return null;
        }
        return (int) LocalDate.now().until(deadline).getDays();
    }
}
