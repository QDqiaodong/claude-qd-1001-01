package com.paper.mill.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 检修期间母卷产出准入的阻止留痕。
 *
 * <p>只有「准入规则拒绝」会落库：检修中新建母卷，或把母卷从「待检」标成「可用」。
 * 普通参数错误不写台账。记录持久保存，刷新页面后纸机导轨仍能看到被阻止的动作与原因。</p>
 *
 * <p>这里只保存纸机/母卷 ID，不设置外键：阻止动作要在业务事务回滚后独立落库，
 * 审计记录不能因父行短暂加锁或后续状态变化而丢失。</p>
 */
@Entity
@Table(name = "production_attempt")
@Getter
@Setter
public class ProductionAttempt {

    /** 新建母卷 */
    public static final String ACTION_PRODUCE = "新建母卷";
    /** 待检母卷检验合格、标记为可用 */
    public static final String ACTION_MARK_USABLE = "标记可用";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "machine_id", nullable = false)
    private Long machineId;

    /** 标记已有母卷时回链；新建被阻止时母卷尚未产生，允许为空 */
    @Column(name = "roll_id")
    private Long rollId;

    /** 新建母卷 / 标记可用 */
    @Column(name = "action_type", nullable = false, length = 16)
    private String actionType;

    /** 被尝试登记的母卷号；标记已有母卷时取现有编号 */
    @Column(name = "roll_no", length = 32)
    private String rollNo;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "block_reason", nullable = false, length = 512)
    private String blockReason;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;
}
