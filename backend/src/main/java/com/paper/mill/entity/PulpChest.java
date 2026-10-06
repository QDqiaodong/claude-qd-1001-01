package com.paper.mill.entity;

import java.math.BigDecimal;
import java.math.RoundingMode;

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
 * 制浆工段 · 浆池台账。
 *
 * <p>浆池是制浆与抄纸之间的缓冲：浆料按浆种分池储存，抄纸时按配比抽浆。
 * 状态流转：在用 → 检修 → 在用；停用池不允许补浆。</p>
 */
@Entity
@Table(name = "pulp_chest")
@Getter
@Setter
public class PulpChest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 浆池编号，唯一 */
    @Column(name = "chest_code", nullable = false, unique = true, length = 32)
    private String chestCode;

    @Column(name = "chest_name", nullable = false, length = 64)
    private String chestName;

    /** 浆种：针叶浆 / 阔叶浆 / 废纸浆 / 竹浆 */
    @Column(name = "pulp_type", nullable = false, length = 32)
    private String pulpType;

    /** 容量（立方米） */
    @Column(name = "capacity_m3", nullable = false, precision = 10, scale = 2)
    private BigDecimal capacityM3;

    /** 当前存量（立方米） */
    @Column(name = "current_m3", nullable = false, precision = 10, scale = 2)
    private BigDecimal currentM3;

    /** 在用 / 检修 / 停用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "remark", length = 128)
    private String remark;

    /** 占用率（%），派生字段：当前存量 / 容量 */
    @Transient
    public Double getUsageRate() {
        if (capacityM3 == null || capacityM3.signum() <= 0 || currentM3 == null) {
            return 0.0;
        }
        return currentM3.multiply(BigDecimal.valueOf(100))
                .divide(capacityM3, 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /** 剩余可补浆量（立方米），派生字段 */
    @Transient
    public BigDecimal getFreeM3() {
        if (capacityM3 == null || currentM3 == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal free = capacityM3.subtract(currentM3);
        return free.signum() > 0 ? free : BigDecimal.ZERO;
    }
}
