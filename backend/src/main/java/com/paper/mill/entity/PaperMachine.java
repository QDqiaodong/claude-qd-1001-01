package com.paper.mill.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.LazyCollection;
import org.hibernate.annotations.LazyCollectionOption;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * 抄纸工段 · 纸机台账。
 *
 * <p>一台纸机常年累积上千个纸卷，纸卷集合用 Hibernate
 * {@code @LazyCollection(EXTRA)} 声明：只问「有多少卷 / 里面有没有某卷」时
 * 走 {@code select count(*)} 或 {@code exists}，不会把上千条记录整体装载进内存；
 * 只有真正遍历集合内容时才发出完整查询。</p>
 *
 * <p>状态流转：运行 → 停机 → 运行；检修只能从运行/停机进，检修完回停机。</p>
 */
@Entity
@Table(name = "paper_machine")
@Getter
@Setter
public class PaperMachine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 纸机编号，唯一 */
    @Column(name = "machine_code", nullable = false, unique = true, length = 32)
    private String machineCode;

    @Column(name = "machine_name", nullable = false, length = 64)
    private String machineName;

    /** 设计幅宽（毫米），纸卷幅宽不得超过此值 */
    @Column(name = "design_width_mm", nullable = false)
    private Integer designWidthMm;

    /** 设计车速（米/分钟） */
    @Column(name = "design_speed_mpm", nullable = false)
    private Integer designSpeedMpm;

    /** 运行 / 停机 / 检修 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_maintain_at")
    private LocalDateTime lastMaintainAt;

    // ------------------------------------------------------------------
    // EXTRA 懒加载集合：纸卷数量极大，问「有多少卷」不要装载全部纸卷
    // ------------------------------------------------------------------
    // 集合本身不做 JSON 序列化：列表接口在没有 Session 的上下文里没法装载它
    @JsonIgnore
    @OneToMany(mappedBy = "machine", fetch = FetchType.LAZY)
    @LazyCollection(LazyCollectionOption.EXTRA)
    private List<PaperRoll> rolls = new ArrayList<>();

    /**
     * 纸卷总数。EXTRA 集合上调用 size() 只发 count 查询，不装载集合。
     * 只在事务内使用（见 PaperMachineService.summary），序列化时跳过。
     */
    @JsonIgnore
    @Transient
    public int getRollCount() {
        return rolls == null ? 0 : rolls.size();
    }

    /** 可用纸卷总数（只问集合内容时才真正装载） */
    @JsonIgnore
    @Transient
    public int getUsableRollCount() {
        if (rolls == null) {
            return 0;
        }
        int n = 0;
        for (PaperRoll r : rolls) {
            if ("可用".equals(r.getStatus())) {
                n++;
            }
        }
        return n;
    }
}
