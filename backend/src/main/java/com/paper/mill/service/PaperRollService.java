package com.paper.mill.service;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.PaperRollRepository;

/**
 * 抄纸工段 · 纸卷业务。
 *
 * <p>新建母卷与「待检 → 可用」必须走 {@link ProductionAccessService} 的检修准入链；
 * 本类只保留查询、普通资料更新、判废/切完以及切裁取用。</p>
 */
@Service
public class PaperRollService {

    private final PaperRollRepository rollRepository;

    public PaperRollService(PaperRollRepository rollRepository) {
        this.rollRepository = rollRepository;
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

    /** 检验判废：待检 → 报废；判可用由 ProductionAccessService 检查检修准入。 */
    @Transactional
    public PaperRoll reject(Long id, String grade) {
        PaperRoll roll = get(id);
        if (!Dict.canTransferRoll(roll.getStatus(), "报废")) {
            throw new BizException("不允许从「" + roll.getStatus() + "」转为「报废」");
        }
        if (grade != null && !grade.isBlank() && !Dict.isValidRollGrade(grade)) {
            throw new BizException("等级不合法：" + grade);
        }
        roll.setStatus("报废");
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
