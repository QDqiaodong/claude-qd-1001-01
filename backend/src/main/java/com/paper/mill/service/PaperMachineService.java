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
import com.paper.mill.entity.MachineMaintenance;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.repository.MachineMaintenanceRepository;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.repository.ProductionAttemptRepository;

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
    private final MachineMaintenanceRepository maintenanceRepository;
    private final ProductionAttemptRepository productionAttemptRepository;

    public PaperMachineService(PaperMachineRepository machineRepository,
                               PaperRollRepository rollRepository,
                               MachineMaintenanceRepository maintenanceRepository,
                               ProductionAttemptRepository productionAttemptRepository) {
        this.machineRepository = machineRepository;
        this.rollRepository = rollRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.productionAttemptRepository = productionAttemptRepository;
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
        out.put("designSpeedMpm", machine.getDesignSpeedMpm());
        out.put("status", machine.getStatus());
        out.put("lastMaintainAt", machine.getLastMaintainAt());
        // EXTRA：size() 只发 count(*)，不装载集合
        out.put("rollCount", machine.getRollCount());
        out.put("pendingRollCount", rollRepository.countByMachineIdAndStatus(id, "待检"));
        out.put("currentMaintenance",
                maintenanceRepository.findFirstByMachineIdAndRecordStatusOrderByIdDesc(
                        id, MachineMaintenance.IN_PROGRESS).orElse(null));
        out.put("awaitingResumeMaintenance",
                maintenanceRepository.findFirstByMachineIdAndRecordStatusOrderByIdDesc(
                        id, MachineMaintenance.COMPLETED).orElse(null));

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
     * 纸机导轨联动数据：可执行动作、当前检修单、未完成产出登记、准入阻止原因和检修历史
     * 在同一条数据规则上一起返回，避免前端只看到状态按钮却看不到产出约束。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> rail(Long id) {
        PaperMachine machine = get(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("machine", machine);
        out.put("rollCount", machine.getRollCount());

        List<PaperRoll> pendingRolls = rollRepository.findByMachineIdAndStatus(id, "待检");
        out.put("pendingRollCount", pendingRolls.size());
        out.put("pendingRollNos", pendingRolls.stream().map(PaperRoll::getRollNo).limit(10).toList());

        MachineMaintenance active = maintenanceRepository
                .findFirstByMachineIdAndRecordStatusOrderByIdDesc(id, MachineMaintenance.IN_PROGRESS)
                .orElse(null);
        MachineMaintenance waiting = maintenanceRepository
                .findFirstByMachineIdAndRecordStatusOrderByIdDesc(id, MachineMaintenance.COMPLETED)
                .orElse(null);
        out.put("currentMaintenance", active != null ? active : waiting);
        out.put("activeMaintenance", active);
        out.put("awaitingResumeMaintenance", waiting);

        List<String> actions = new ArrayList<>();
        List<String> actionReasons = new ArrayList<>();
        switch (machine.getStatus()) {
            case "运行" -> {
                actions.add("停机");
                if (pendingRolls.isEmpty()) {
                    actions.add("发起检修");
                } else {
                    actionReasons.add("发起检修被禁止：仍有 " + pendingRolls.size() + " 条未完成产出登记（待检母卷）");
                }
            }
            case "停机" -> {
                if (waiting != null) {
                    actions.add("经办人确认复机");
                    actionReasons.add("普通「恢复运行」被禁止：检修完成后必须由经办人 "
                            + waiting.getOperator() + " 确认复机");
                } else {
                    actions.add("运行");
                }
                if (pendingRolls.isEmpty()) {
                    actions.add("发起检修");
                } else {
                    actionReasons.add("发起检修被禁止：仍有 " + pendingRolls.size() + " 条未完成产出登记（待检母卷）");
                }
            }
            case "检修" -> {
                actions.add("检修完成");
                actionReasons.add("母卷产出准入已关闭：不能新建母卷，也不能标记新母卷可用");
            }
            default -> actionReasons.add("未知纸机状态：" + machine.getStatus());
        }
        out.put("availableActions", actions);
        out.put("actionReasons", actionReasons);
        out.put("productionBlocked", "检修".equals(machine.getStatus()));

        List<ProductionAttempt> attempts = productionAttemptRepository
                .findByMachineIdOrderByAttemptedAtDescIdDesc(id);
        out.put("productionAttempts", attempts);
        out.put("latestProductionAttempt", attempts.stream().findFirst().orElse(null));
        out.put("maintenanceHistory",
                maintenanceRepository.findByMachineIdOrderByStartedAtDescIdDesc(id));
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
        if ("检修".equals(machine.getStatus())) {
            throw new BizException("新建设备不能直接置为检修，请建档后通过检修单发起检修");
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

    /**
     * 普通状态流转：运行 ↔ 停机。
     *
     * <p>进入「检修」必须带检修原因/时间/经办人，调用专门检修接口；检修完成回停机、再复机，
     * 也必须走检修单确认接口，不能用普通状态按钮绕过准入规则链。</p>
     */
    @Transactional
    public PaperMachine transfer(Long id, String target) {
        PaperMachine machine = machineRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("纸机不存在：" + id));
        if (!Dict.isValidMachineStatus(target)) {
            throw new BizException("纸机状态不合法：" + target);
        }
        if ("检修".equals(target)) {
            throw new BizException("进入检修必须提交检修原因、开始时间、预计结束时间和经办人，请使用「发起检修」");
        }
        if ("检修".equals(machine.getStatus())) {
            MachineMaintenance active = maintenanceRepository
                    .findFirstByMachineIdAndRecordStatusOrderByIdDesc(id, MachineMaintenance.IN_PROGRESS)
                    .orElse(null);
            throw new BizException(blockedTransferMessage(machine, active,
                    "检修中的纸机不能直接转为「停机」，必须先由经办人执行「检修完成」"));
        }
        if (!Dict.canTransferMachine(machine.getStatus(), target)) {
            throw new BizException("不允许从「" + machine.getStatus() + "」转为「" + target + "」");
        }
        if ("运行".equals(target)) {
            MachineMaintenance waiting = maintenanceRepository
                    .findFirstByMachineIdAndRecordStatusOrderByIdDesc(id, MachineMaintenance.COMPLETED)
                    .orElse(null);
            if (waiting != null) {
                throw new BizException(blockedTransferMessage(machine, waiting,
                        "检修已完成但尚未复机确认，不能直接恢复运行"));
            }
        }
        machine.setStatus(target);
        return machineRepository.save(machine);
    }

    private String blockedTransferMessage(PaperMachine machine, MachineMaintenance order, String prefix) {
        StringBuilder sb = new StringBuilder(prefix)
                .append("；当前状态为「").append(machine.getStatus()).append("」");
        if (order != null) {
            sb.append("，当前检修单 ").append(order.getMaintenanceNo())
                    .append("（").append(order.getRecordStatus())
                    .append("，原因：").append(order.getReason()).append("）");
        }
        return sb.toString();
    }
}
