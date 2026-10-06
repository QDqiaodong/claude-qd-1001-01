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
 * 成品库 · 成品件与出入库。
 *
 * <p>切好的纸按「定量 + 幅宽」打成件入库，按库区堆放，发运时整件出库。
 * 状态：已入库 → 已出库（单向，已出库不可回退）。</p>
 */
@Entity
@Table(name = "finished_bundle")
@Getter
@Setter
public class FinishedBundle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 件号，唯一 */
    @Column(name = "bundle_no", nullable = false, unique = true, length = 32)
    private String bundleNo;

    // EAGER：列表接口要在没有 Session 的上下文里带出 orderNo / customerName
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private CuttingOrder order;

    /** 定量（g/m2） */
    @Column(name = "grammage_gsm", nullable = false)
    private Integer grammageGsm;

    /** 幅宽（毫米） */
    @Column(name = "width_mm", nullable = false)
    private Integer widthMm;

    /** 令数 / 张数 */
    @Column(name = "sheet_count", nullable = false)
    private Integer sheetCount;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal weightKg;

    /** 库区：A区 / B区 / C区 */
    @Column(name = "warehouse_zone", nullable = false, length = 32)
    private String warehouseZone;

    /** 已入库 / 已出库 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "stored_at")
    private LocalDateTime storedAt;

    @Column(name = "outbound_at")
    private LocalDateTime outboundAt;

    /** 所属订单号，派生字段 */
    @Transient
    public String getOrderNo() {
        return order == null ? null : order.getOrderNo();
    }

    /** 客户名，派生字段 */
    @Transient
    public String getCustomerName() {
        return order == null ? null : order.getCustomerName();
    }
}
