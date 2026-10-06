package com.paper.mill.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;

/**
 * 抄纸工段 · 纸机业务。
 *
 * <p>本仓的后端路线是 Hibernate {@code @LazyCollection(EXTRA)}：
 * 纸机的纸卷集合常年上千条，问「总共有多少卷」走 {@code select count(*)}，
 * 问「某卷是不是这台机的」走 {@code exists}，都不会把集合整体装载；
 * 只有真正要遍历（如统计可用卷数）时才发出完整查询。</p>
 */
@Service
public class PaperMachineService {

    private final PaperMachineRepository machineRepository;
    private final PaperRollRepository rollRepository;

    public PaperMachineService(PaperMachineRepository machineRepository,
                               PaperRollRepository rollRepository) {
        this.machineRepository = machineRepository;
        this.rollRepository = rollRepository;
    }

    public List<PaperMachine> list(String status) {
        if (status != null && !status.isBlank()) {
            return machineRepository.findByStatus(status);
        }
        return machineRepository.findAll();
    }

    public PaperMachine get(Long id) {
        return machineRepository.findById(id)
                .orElseThrow(() -> new BizException("纸机不存在：" + id));
    }

    /**
     * 纸机概览：只问集合大小（count 查询），不装载纸卷。
     *
     * @return 纸机信息 + 纸卷总数 + 可用卷数 + 可用卷号（前若干）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(Long id) {
        PaperMachine machine = get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", machine.getId());
        out.put("machineCode", machine.getMachineCode());
        out.put("machineName", machine.getMachineName());
        out.put("designWidthMm", machine.getDesignWidthMm());
        out.put("status", machine.getStatus());
        // EXTRA：size() 只发 count(*)，不装载集合
        out.put("rollCount", machine.getRollCount());

        // 真正要遍历内容时才装载（这一句才会发出完整查询）
        List<String> usable = new ArrayList<>();
        for (PaperRoll r : machine.getRolls()) {
            if ("可用".equals(r.getStatus())) {
                usable.add(r.getRollNo());
            }
        }
        out.put("usableRollCount", usable.size());
        out.put("usableRollNos", usable);
        return out;
    }

    /**
     * 判断某卷是否属于这台纸机：EXTRA 下 contains() 走 exists 查询，不装载集合。
     */
    @Transactional(readOnly = true)
    public boolean ownsRoll(Long machineId, String rollNo) {
        PaperMachine machine = get(machineId);
        PaperRoll roll = rollRepository.findByRollNo(rollNo)
                .orElseThrow(() -> new BizException("纸卷不存在：" + rollNo));
        return machine.getRolls().contains(roll);
    }

    @Transactional
    public PaperMachine create(PaperMachine input) {
        if (input.getMachineCode() == null || input.getMachineCode().isBlank()) {
            throw new BizException("纸机编号不能为空");
        }
        machineRepository.findByMachineCode(input.getMachineCode().trim()).ifPresent(m -> {
            throw new BizException("纸机编号已存在：" + input.getMachineCode());
        });
        if (input.getDesignWidthMm() == null || input.getDesignWidthMm() <= 0) {
            throw new BizException("设计幅宽必须大于 0");
        }
        if (input.getDesignSpeedMpm() == null || input.getDesignSpeedMpm() <= 0) {
            throw new BizException("设计车速必须大于 0");
        }
        PaperMachine machine = new PaperMachine();
        machine.setMachineCode(input.getMachineCode().trim());
        machine.setMachineName(input.getMachineName() == null ? input.getMachineCode() : input.getMachineName());
        machine.setDesignWidthMm(input.getDesignWidthMm());
        machine.setDesignSpeedMpm(input.getDesignSpeedMpm());
        machine.setStatus(input.getStatus() == null || input.getStatus().isBlank()
                ? "停机" : input.getStatus());
        if (!Dict.isValidMachineStatus(machine.getStatus())) {
            throw new BizException("纸机状态不合法：" + machine.getStatus());
        }
        machine.setLastMaintainAt(LocalDateTime.now());
        return machineRepository.save(machine);
    }

    @Transactional
    public PaperMachine update(Long id, PaperMachine input) {
        PaperMachine machine = get(id);
        if (input.getMachineName() != null && !input.getMachineName().isBlank()) {
            machine.setMachineName(input.getMachineName());
        }
        if (input.getDesignWidthMm() != null) {
            if (input.getDesignWidthMm() <= 0) {
                throw new BizException("设计幅宽必须大于 0");
            }
            // 缩小设计幅宽时，已有机身纸卷的幅宽不能超限
            List<PaperRoll> rolls = rollRepository.findByMachineId(machine.getId());
            for (PaperRoll r : rolls) {
                if (r.getWidthMm() != null && r.getWidthMm() > input.getDesignWidthMm()) {
                    throw new BizException("纸卷 " + r.getRollNo() + " 幅宽 " + r.getWidthMm()
                            + " 已超过新设计幅宽 " + input.getDesignWidthMm());
                }
            }
            machine.setDesignWidthMm(input.getDesignWidthMm());
        }
        if (input.getDesignSpeedMpm() != null) {
            if (input.getDesignSpeedMpm() <= 0) {
                throw new BizException("设计车速必须大于 0");
            }
            machine.setDesignSpeedMpm(input.getDesignSpeedMpm());
        }
        return machineRepository.save(machine);
    }

    /** 状态流转：运行 ↔ 停机；检修只能从运行/停机进，检修完回停机 */
    @Transactional
    public PaperMachine transfer(Long id, String target) {
        PaperMachine machine = get(id);
        if (!Dict.isValidMachineStatus(target)) {
            throw new BizException("纸机状态不合法：" + target);
        }
        if (!Dict.canTransferMachine(machine.getStatus(), target)) {
            throw new BizException("不允许从「" + machine.getStatus() + "」转为「" + target + "」");
        }
        if ("检修".equals(target)) {
            machine.setLastMaintainAt(LocalDateTime.now());
        }
        machine.setStatus(target);
        return machineRepository.save(machine);
    }
}
