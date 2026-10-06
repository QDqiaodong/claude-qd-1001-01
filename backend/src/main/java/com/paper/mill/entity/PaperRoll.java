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
 * 抄纸工段 · 纸卷（母卷）。
 *
 * <p>纸机抄造出来的大卷，再送切纸机裁成客户要的规格。
 * 状态流转：待检 → 可用 / 报废；可用 → 已切完 / 报废。只有「可用」能上切纸机。</p>
 */
@Entity
@Table(name = "paper_roll")
@Getter
@Setter
public class PaperRoll {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 纸卷号，唯一 */
    @Column(name = "roll_no", nullable = false, unique = true, length = 32)
    private String rollNo;

    // EAGER：列表接口要在没有 Session 的上下文里带出 machineCode / designWidthMm
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "machine_id", nullable = false)
    private PaperMachine machine;

    /** 定量（g/m2） */
    @Column(name = "grammage_gsm", nullable = false)
    private Integer grammageGsm;

    /** 幅宽（毫米），不得超过所属纸机的设计幅宽 */
    @Column(name = "width_mm", nullable = false)
    private Integer widthMm;

    /** 剩余长度（米），切裁会从这里扣 */
    @Column(name = "length_m", nullable = false)
    private Integer lengthM;

    @Column(name = "weight_kg", nullable = false, precision = 10, scale = 2)
    private BigDecimal weightKg;

    /** 等级：一等 / 二等 / 等外 */
    @Column(name = "grade", nullable = false, length = 16)
    private String grade;

    /** 待检 / 可用 / 已切完 / 报废 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "produced_at")
    private LocalDateTime producedAt;

    /** 所属纸机编号，派生字段，便于前端直接展示 */
    @Transient
    public String getMachineCode() {
        return machine == null ? null : machine.getMachineCode();
    }

    /** 所属纸机设计幅宽，派生字段，用于前端校验提示 */
    @Transient
    public Integer getDesignWidthMm() {
        return machine == null ? null : machine.getDesignWidthMm();
    }
}
