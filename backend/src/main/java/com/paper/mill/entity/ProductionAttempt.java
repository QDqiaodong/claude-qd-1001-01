package com.paper.mill.entity;

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
 * 抄纸工段 · 检修期产出准入失败留痕。
 *
 * <p>新建母卷或把待检母卷判为「可用」被检修准入规则阻止时落一行。记录不改变任何母卷数据，
 * 但把纸机、操作人、动作与明确原因持久化，刷新页面后仍可在纸机导轨上追溯。</p>
 */
@Entity
@Table(name = "production_attempt")
@Getter
@Setter
public class ProductionAttempt {

    /** 新建母卷登记 */
    public static final String CREATE_ROLL = "CREATE_ROLL";
    /** 待检母卷检验为可用 */
    public static final String MARK_USABLE = "MARK_USABLE";

    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_token", nullable = false, unique = true, length = 64)
    private String requestToken;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "machine_id")
    private PaperMachine machine;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "roll_id")
    private PaperRoll roll;

    /** CREATE_ROLL / MARK_USABLE */
    @Column(name = "action_type", nullable = false, length = 16)
    private String actionType;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "result", nullable = false, length = 8)
    private String result;

    @Column(name = "fail_reason", nullable = false, length = 512)
    private String failReason;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @Transient
    public String getMachineCode() {
        return machine == null ? null : machine.getMachineCode();
    }

    @Transient
    public String getRollNo() {
        return roll == null ? null : roll.getRollNo();
    }
}
