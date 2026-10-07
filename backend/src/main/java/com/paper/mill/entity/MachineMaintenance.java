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
 * 抄纸工段 · 纸机检修单。
 *
 * <p>检修单只增不改：发起时记录原因、开始/预计结束时间、经办人；检修完成只能让纸机回「停机」；
 * 再由经办人确认恢复「运行」。恢复后的检修单保留在历史中，不影响既有母卷归属。</p>
 */
@Entity
@Table(name = "machine_maintenance")
@Getter
@Setter
public class MachineMaintenance {

    /** 纸机正在检修，禁止产出与判可用 */
    public static final String ACTIVE = "检修中";
    /** 检修已完成，纸机停机，等待经办人确认恢复运行 */
    public static final String AWAIT_RESUME = "待恢复";
    /** 已确认恢复运行，作为历史留档 */
    public static final String RESUMED = "已恢复";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "maintenance_no", nullable = false, unique = true, length = 32)
    private String maintenanceNo;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "machine_id", nullable = false)
    private PaperMachine machine;

    @Column(name = "reason", nullable = false, length = 256)
    private String reason;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expected_finish_at", nullable = false)
    private LocalDateTime expectedFinishAt;

    @Column(name = "start_operator", nullable = false, length = 32)
    private String startOperator;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "finish_operator", length = 32)
    private String finishOperator;

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    @Column(name = "resume_operator", length = 32)
    private String resumeOperator;

    /** 检修中 / 待恢复 / 已恢复 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Transient
    public String getMachineCode() {
        return machine == null ? null : machine.getMachineCode();
    }

    @Transient
    public String getMachineName() {
        return machine == null ? null : machine.getMachineName();
    }
}
