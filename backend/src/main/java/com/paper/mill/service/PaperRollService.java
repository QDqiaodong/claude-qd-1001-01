package com.paper.mill.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;

/**
 * 抄纸工段 · 纸卷业务。
 *
 * <p>核心规则：① 纸卷幅宽不得超过所属纸机的设计幅宽；② 等级按「实测定量 vs 目标定量」
 * 的偏差自动判定（偏差 ≤ 2 一等，≤ 5 二等，超过判等外）；③ 只有「可用」的纸卷能上切纸机。</p>
 */
@Service
public class PaperRollService {

    private final PaperRollRepository rollRepository;
    private final PaperMachineRepository machineRepository;

    public PaperRollService(PaperRollRepository rollRepository,
                            PaperMachineRepository machineRepository) {
        this.rollRepository = rollRepository;
        this.machineRepository = machineRepository;
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
     * 抄造产出纸卷。
     *
     * @param input     纸卷信息
     * @param targetGsm 本批目标定量，用于自动判等；为空则以实测定量为准
     */
    @Transactional
    public PaperRoll create(PaperRoll input, Integer targetGsm) {
        if (input.getRollNo() == null || input.getRollNo().isBlank()) {
            throw new BizException("纸卷号不能为空");
        }
        rollRepository.findByRollNo(input.getRollNo().trim()).ifPresent(r -> {
            throw new BizException("纸卷号已存在：" + input.getRollNo());
        });
        if (input.getMachine() == null || input.getMachine().getId() == null) {
            throw new BizException("必须指定所属纸机");
        }
        PaperMachine machine = machineRepository.findById(input.getMachine().getId())
                .orElseThrow(() -> new BizException("纸机不存在：" + input.getMachine().getId()));

        if (input.getGrammageGsm() == null || input.getGrammageGsm() <= 0) {
            throw new BizException("定量必须大于 0");
        }
        if (input.getWidthMm() == null || input.getWidthMm() <= 0) {
            throw new BizException("幅宽必须大于 0");
        }
        // 规则①：幅宽不得超过纸机设计幅宽
        if (input.getWidthMm() > machine.getDesignWidthMm()) {
            throw new BizException("纸卷幅宽 " + input.getWidthMm()
                    + " 超过纸机 " + machine.getMachineCode() + " 的设计幅宽 "
                    + machine.getDesignWidthMm());
        }
        if (input.getLengthM() == null || input.getLengthM() <= 0) {
            throw new BizException("长度必须大于 0");
        }
        if (input.getWeightKg() == null || input.getWeightKg().signum() <= 0) {
            throw new BizException("重量必须大于 0");
        }

        PaperRoll roll = new PaperRoll();
        roll.setRollNo(input.getRollNo().trim());
        roll.setMachine(machine);
        roll.setGrammageGsm(input.getGrammageGsm());
        roll.setWidthMm(input.getWidthMm());
        roll.setLengthM(input.getLengthM());
        roll.setWeightKg(input.getWeightKg());
        // 规则②：按定量偏差自动判等
        int target = (targetGsm == null || targetGsm <= 0) ? input.getGrammageGsm() : targetGsm;
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

    /** 检验：待检 → 可用 / 报废 */
    @Transactional
    public PaperRoll inspect(Long id, String target, String grade) {
        PaperRoll roll = get(id);
        if (!Dict.canTransferRoll(roll.getStatus(), target)) {
            throw new BizException("不允许从「" + roll.getStatus() + "」转为「" + target + "」");
        }
        if (grade != null && !grade.isBlank() && !Dict.isValidRollGrade(grade)) {
            throw new BizException("等级不合法：" + grade);
        }
        roll.setStatus(target);
        if (grade != null && !grade.isBlank()) {
            roll.setGrade(grade);
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
}
