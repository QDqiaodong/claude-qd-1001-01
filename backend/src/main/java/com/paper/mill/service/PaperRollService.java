package com.paper.mill.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.dto.PaperRollProduceRequest;
import com.paper.mill.dto.RollInspectionRequest;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;

/**
 * 抄纸工段 · 纸卷业务。
 *
 * <p>核心规则：① 纸卷幅宽不得超过所属纸机的设计幅宽；② 等级按「实测定量 vs 目标定量」
 * 的偏差自动判定（偏差 ≤ 2 一等，≤ 5 二等，超过判等外）；③ 检修中的纸机不能新建母卷，
 * 也不能把母卷标成「可用」。准入拒绝会留痕，刷新后仍能在纸机导轨看到原因。</p>
 */
@Service
public class PaperRollService {

    private final PaperRollRepository rollRepository;
    private final PaperMachineRepository machineRepository;
    private final ProductionAttemptService productionAttemptService;

    public PaperRollService(PaperRollRepository rollRepository,
                            PaperMachineRepository machineRepository,
                            ProductionAttemptService productionAttemptService) {
        this.rollRepository = rollRepository;
        this.machineRepository = machineRepository;
        this.productionAttemptService = productionAttemptService;
    }

    public List<PaperRoll> list(Long machineId, String status, String grade) {
        if (machineId != null && status != null && !status.isBlank()) {
            return rollRepository.findByMachineIdAndStatus(machineId, status);
        }
        if (machineId != null) {
            return rollRepository.findByMachineId(machineId);
        }
        if (status != null && !status.isBlank()) {
            return rollRepository.findByStatus(status);
        }
        if (grade != null && !grade.isBlank()) {
            return rollRepository.findByGrade(grade);
        }
        return rollRepository.findAll();
    }

    public PaperRoll get(Long id) {
        return rollRepository.findById(id)
                .orElseThrow(() -> new BizException("纸卷不存在：" + id));
    }

    /**
     * 抄造产出纸卷：锁纸机行后校验检修准入和幅宽，通过才生成待检母卷。
     */
    @Transactional
    public PaperRoll produce(Long machineId, PaperRollProduceRequest input) {
        PaperMachine machine = machineRepository.findByIdForUpdate(machineId)
                .orElseThrow(() -> new BizException("纸机不存在：" + machineId));

        if ("检修".equals(machine.getStatus())) {
            String reason = maintenanceBlockReason(machine, ProductionAttempt.ACTION_PRODUCE,
                    "检修中不能新建纸卷");
            productionAttemptService.recordBlocked(machine.getId(), null,
                    ProductionAttempt.ACTION_PRODUCE, input.getRollNo(), input.getOperator(), reason);
            throw new BizException(reason);
        }

        rollRepository.findByRollNo(input.getRollNo().trim()).ifPresent(r -> {
            throw new BizException("纸卷号已存在：" + input.getRollNo());
        });
        if (input.getWidthMm() > machine.getDesignWidthMm()) {
            throw new BizException("纸卷幅宽 " + input.getWidthMm()
                    + " 超过纸机 " + machine.getMachineCode() + " 的设计幅宽 "
                    + machine.getDesignWidthMm());
        }

        PaperRoll roll = new PaperRoll();
        roll.setRollNo(input.getRollNo().trim());
        roll.setMachine(machine);
        roll.setGrammageGsm(input.getGrammageGsm());
        roll.setWidthMm(input.getWidthMm());
        roll.setLengthM(input.getLengthM());
        roll.setWeightKg(input.getWeightKg());
        int target = (input.getTargetGsm() == null || input.getTargetGsm() <= 0)
                ? input.getGrammageGsm() : input.getTargetGsm();
        roll.setGrade(Dict.decideGrade(input.getGrammageGsm(), target));
        roll.setStatus("待检");
        roll.setProducedAt(LocalDateTime.now());
        return rollRepository.save(roll);
    }

    @Transactional
    public PaperRoll update(Long id, PaperRoll input) {
        PaperRoll roll = get(id);
        if (input.getWidthMm() != null) {
            if (input.getWidthMm() <= 0) {
                throw new BizException("幅宽必须大于 0");
            }
            if (roll.getMachine() != null && roll.getMachine().getDesignWidthMm() != null
                    && input.getWidthMm() > roll.getMachine().getDesignWidthMm()) {
                throw new BizException("纸卷幅宽 " + input.getWidthMm() + " 超过纸机设计幅宽 "
                        + roll.getMachine().getDesignWidthMm());
            }
            roll.setWidthMm(input.getWidthMm());
        }
        if (input.getGrammageGsm() != null) {
            if (input.getGrammageGsm() <= 0) {
                throw new BizException("定量必须大于 0");
            }
            roll.setGrammageGsm(input.getGrammageGsm());
        }
        if (input.getWeightKg() != null) {
            if (input.getWeightKg().signum() < 0) {
                throw new BizException("重量不能为负数");
            }
            roll.setWeightKg(input.getWeightKg());
        }
        return rollRepository.save(roll);
    }

    /**
     * 检验：待检 → 可用 / 报废。
     *
     * <p>标记「可用」属于新母卷获得生产准入，因此纸机检修中必须拒绝并留痕；
     * 「报废」不产生可用母卷，允许在检修中处理未完成登记。</p>
     */
    @Transactional
    public PaperRoll inspect(Long id, RollInspectionRequest req) {
        PaperRoll existing = get(id);
        // 固定锁顺序：先纸机、后母卷，和检修/产出准入共用同一把纸机行锁
        PaperMachine machine = machineRepository.findByIdForUpdate(existing.getMachine().getId())
                .orElseThrow(() -> new BizException("纸机不存在：" + existing.getMachine().getId()));
        PaperRoll roll = rollRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("纸卷不存在：" + id));
        if (!Dict.canTransferRoll(roll.getStatus(), req.getTarget())) {
            throw new BizException("不允许从「" + roll.getStatus() + "」转为「" + req.getTarget() + "」");
        }
        if (req.getGrade() != null && !req.getGrade().isBlank() && !Dict.isValidRollGrade(req.getGrade())) {
            throw new BizException("等级不合法：" + req.getGrade());
        }

        if ("可用".equals(req.getTarget()) && "检修".equals(machine.getStatus())) {
            String reason = maintenanceBlockReason(machine, ProductionAttempt.ACTION_MARK_USABLE,
                    "检修中不能把新纸卷标记为「可用」");
            productionAttemptService.recordBlocked(machine.getId(), roll.getId(),
                    ProductionAttempt.ACTION_MARK_USABLE, roll.getRollNo(), req.getOperator(), reason);
            throw new BizException(reason);
        }

        roll.setStatus(req.getTarget());
        if (req.getGrade() != null && !req.getGrade().isBlank()) {
            roll.setGrade(req.getGrade());
        }
        return rollRepository.save(roll);
    }

    /** 状态流转：可用 → 已切完 / 报废 */
    @Transactional
    public PaperRoll transfer(Long id, String target) {
        PaperRoll roll = get(id);
        if (!Dict.canTransferRoll(roll.getStatus(), target)) {
            throw new BizException("不允许从「" + roll.getStatus() + "」转为「" + target + "」");
        }
        roll.setStatus(target);
        return rollRepository.save(roll);
    }

    /**
     * 切纸机从母卷上取用一段：扣减剩余长度。
     *
     * @param id        母卷
     * @param lengthM   本次取用长度（米）
     * @param weightKg  本次取用重量（千克）
     */
    @Transactional
    public PaperRoll consume(Long id, Integer lengthM, BigDecimal weightKg) {
        PaperRoll roll = get(id);
        if (!Dict.isRollUsable(roll.getStatus())) {
            throw new BizException("只有「可用」的纸卷能上切纸机，当前状态：" + roll.getStatus());
        }
        if (lengthM == null || lengthM <= 0) {
            throw new BizException("取用长度必须大于 0");
        }
        if (lengthM > roll.getLengthM()) {
            throw new BizException("母卷剩余长度仅 " + roll.getLengthM() + " 米，不足 " + lengthM + " 米");
        }
        if (weightKg != null && weightKg.compareTo(roll.getWeightKg()) > 0) {
            throw new BizException("取用重量超过母卷剩余重量");
        }
        int remain = roll.getLengthM() - lengthM;
        roll.setLengthM(remain);
        if (weightKg != null) {
            roll.setWeightKg(roll.getWeightKg().subtract(weightKg));
        }
        // 余长不足阈值则整卷判为已切完
        if (remain < Dict.MIN_REMAIN_LENGTH_M) {
            roll.setStatus("已切完");
        }
        return rollRepository.save(roll);
    }

    private String maintenanceBlockReason(PaperMachine machine, String action, String prefix) {
        return prefix + "；纸机 " + machine.getMachineCode() + " 当前为「检修」状态，"
                + "新母卷不会获得准入，已有母卷归属和台账保持不变（本次" + action + "已阻止并留痕）";
    }
}
