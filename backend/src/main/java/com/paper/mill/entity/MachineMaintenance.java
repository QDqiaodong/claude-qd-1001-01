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
import lombok.Getter;
import lombok.Setter;

/**
 * 纸机检修单：从「运行/停机」进入检修时建档，完成后只能先回「停机」，
 * 再由经办人确认恢复运行。检修单只增不改，形成纸机导轨上的检修历史。
 */
@Entity
@Table(name = "machine_maintenance")
@Getter
@Setter
public class MachineMaintenance {

    /** 检修中，纸机仍处于「检修」状态 */
    public static final String IN_PROGRESS = "检修中";
    /** 检修完成，纸机已回到「停机」，等待经办人确认恢复运行 */
    public static final String COMPLETED = "待复机确认";
    /** 经办人确认后，纸机恢复「运行」 */
    public static final String RESUMED = "已复机";

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

    @Column(name = "expected_end_at", nullable = false)
    private LocalDateTime expectedEndAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "resumed_at")
    private LocalDateTime resumedAt;

    /** 发起人/检修经办人；复机确认也必须由经办人执行 */
    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "finish_operator", length = 32)
    private String finishOperator;

    @Column(name = "resume_operator", length = 32)
    private String resumeOperator;

    /** 检修中 / 待复机确认 / 已复机 */
    @Column(name = "record_status", nullable = false, length = 16)
    private String recordStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
