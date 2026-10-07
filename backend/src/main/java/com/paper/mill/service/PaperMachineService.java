package com.paper.mill.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.dto.MachineActionRequest;
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
 * <p>纸机状态、检修单与母卷产出准入在同一条规则链上：状态动作先锁纸机行，
 * 发起检修时在同一事务内检查「待检」产出登记；检修中由产出服务读取同一状态阻止新建和判可用；
 * 检修完成只能回停机，经办人再次确认后才恢复运行。</p>
 */
@Service
public class PaperMachineService {

    private static final List<String> OPEN_MAINTENANCE_STATUSES =
            List.of(MachineMaintenance.ACTIVE, MachineMaintenance.AWAIT_RESUME);
    private static final int HISTORY_LIMIT = 8;
    private static final int BLOCK_LOG_LIMIT = 5;

    private final PaperMachineRepository machineRepository;
    private final PaperRollRepository rollRepository;
    private final MachineMaintenanceRepository maintenanceRepository;
    private final ProductionAttemptRepository productionAttemptRepository;
    private final BizNoGenerator bizNoGenerator;

    public PaperMachineService(PaperMachineRepository machineRepository,
                               PaperRollRepository rollRepository,
                               MachineMaintenanceRepository maintenanceRepository,
                               ProductionAttemptRepository productionAttemptRepository,
                               BizNoGenerator bizNoGenerator) {
        this.machineRepository = machineRepository;
        this.rollRepository = rollRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.productionAttemptRepository = productionAttemptRepository;
        this.bizNoGenerator = bizNoGenerator;
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

    /** 纸机导轨：可执行动作、当前检修单、历史检修与检修期禁止产出原因放在同一份视图中。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> guide(String status) {
        List<PaperMachine> machines = list(status);
        List<Map<String, Object>> guides = new ArrayList<>();
        for (PaperMachine machine : machines) {
            guides.add(guideFor(machine));
        }
        return guides;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> summary(Long id) {
        return guideFor(get(id));
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
        machine.setMachineName(input.getMachineName() == null || input.getMachineName().isBlank()
                ? input.getMachineCode() : input.getMachineName().trim());
        machine.setDesignWidthMm(input.getDesignWidthMm());
        machine.setDesignSpeedMpm(input.getDesignSpeedMpm());
        String requested = input.getStatus() == null || input.getStatus().isBlank()
                ? "停机" : input.getStatus().trim();
        if (!"运行".equals(requested) && !"停机".equals(requested)) {
            throw new BizException("新建纸机只能为「运行」或「停机」；检修必须通过检修单发起");
        }
        machine.setStatus(requested);
        machine.setLastMaintainAt(null);
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
     * 执行纸机状态动作。所有并发动作在纸机行锁上排队，后到者按提交后的真实状态得到明确拒绝原因。
     */
    @Transactional
    public PaperMachine performAction(Long id, MachineActionRequest req) {
        String action = req.getAction() == null ? "" : req.getAction().trim();
        String operator = requiredOperator(req.getOperator());
        PaperMachine machine = machineRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("纸机不存在：" + id));
        MachineMaintenance open = findOpenMaintenance(machine.getId());

        switch (action) {
            case "停机" -> stop(machine, open, operator);
            case "运行" -> startRunning(machine, open, operator);
            case "开始检修" -> startMaintenance(machine, open, req, operator);
            case "完成检修" -> finishMaintenance(machine, open, operator);
            case "恢复运行" -> resumeRunning(machine, open, operator);
            default -> throw new BizException("不支持的纸机动作：" + action);
        }
        return machineRepository.save(machine);
    }

    @Transactional(readOnly = true)
    public List<MachineMaintenance> listMaintenance(Long machineId) {
        get(machineId);
        return maintenanceRepository.findByMachineIdOrderByStartedAtDesc(machineId);
    }

    @Transactional(readOnly = true)
    public List<ProductionAttempt> listProductionBlocks(Long machineId) {
        get(machineId);
        return productionAttemptRepository.findByMachineIdOrderByAttemptedAtDesc(machineId);
    }

    private Map<String, Object> guideFor(PaperMachine machine) {
        MachineMaintenance current = findOpenMaintenance(machine.getId());
        List<MachineMaintenance> history = maintenanceRepository
                .findByMachineIdOrderByStartedAtDesc(machine.getId());
        List<ProductionAttempt> blocks = productionAttemptRepository
                .findByMachineIdOrderByAttemptedAtDesc(machine.getId());
        List<PaperRoll> pendingRolls = rollRepository
                .findByMachineIdAndStatusOrderByIdAsc(machine.getId(), "待检");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", machine.getId());
        out.put("machineCode", machine.getMachineCode());
        out.put("machineName", machine.getMachineName());
        out.put("designWidthMm", machine.getDesignWidthMm());
        out.put("designSpeedMpm", machine.getDesignSpeedMpm());
        out.put("status", machine.getStatus());
        out.put("lastMaintainAt", machine.getLastMaintainAt());
        out.put("rollCount", machine.getRollCount());
        out.put("pendingRollCount", pendingRolls.size());
        out.put("pendingRollNos", pendingRolls.stream().map(PaperRoll::getRollNo).limit(20).toList());
        out.put("currentMaintenance", current);
        out.put("maintenanceHistory", history.stream().limit(HISTORY_LIMIT).toList());
        out.put("productionBlocks", blocks.stream().limit(BLOCK_LOG_LIMIT).toList());
        out.put("productionAllowed", !"检修".equals(machine.getStatus()));
        out.put("productionBlockReason", productionBlockReason(machine, current));
        out.put("allowedActions", allowedActions(machine.getStatus(), current));
        return out;
    }

    private void stop(PaperMachine machine, MachineMaintenance open, String operator) {
        if (open != null) {
            throw new BizException(currentMachineReason(machine, open, "检修流程未闭环，不能直接停机"));
        }
        if ("停机".equals(machine.getStatus())) {
            throw new BizException("纸机已经是停机状态");
        }
        if (!"运行".equals(machine.getStatus())) {
            throw new BizException(currentMachineReason(machine, open, "只有运行中的纸机可以停机"));
        }
        machine.setStatus("停机");
    }

    private void startRunning(PaperMachine machine, MachineMaintenance open, String operator) {
        if (open != null) {
            throw new BizException(currentMachineReason(machine, open,
                    "存在未闭环检修单，请先在检修单上使用「恢复运行」由经办人确认"));
        }
        if ("运行".equals(machine.getStatus())) {
            throw new BizException("纸机已经是运行状态");
        }
        if (!"停机".equals(machine.getStatus())) {
            throw new BizException(currentMachineReason(machine, open, "只有停机纸机可以启动运行"));
        }
        machine.setStatus("运行");
    }

    private void startMaintenance(PaperMachine machine, MachineMaintenance open,
                                  MachineActionRequest req, String operator) {
        if (open != null) {
            throw new BizException(currentMachineReason(machine, open, "已有未闭环检修单，不能重复发起检修"));
        }
        if (!"运行".equals(machine.getStatus()) && !"停机".equals(machine.getStatus())) {
            throw new BizException(currentMachineReason(machine, open,
                    "只有「运行」或「停机」纸机可以进入检修"));
        }

        List<PaperRoll> pendingRolls = rollRepository
                .findByMachineIdAndStatusOrderByIdAsc(machine.getId(), "待检");
        if (!pendingRolls.isEmpty()) {
            String nos = pendingRolls.stream().map(PaperRoll::getRollNo)
                    .limit(20).reduce((a, b) -> a + "、" + b).orElse("");
            throw new BizException("该纸机存在 " + pendingRolls.size()
                    + " 条未完成的产出登记（待检母卷：" + nos + "），必须先检验为可用或报废后才能进入检修");
        }

        String reason = req.getReason() == null ? "" : req.getReason().trim();
        if (reason.isBlank()) {
            throw new BizException("检修原因不能为空");
        }
        if (req.getExpectedFinishAt() == null) {
            throw new BizException("预计结束时间不能为空");
        }
        if (!req.getExpectedFinishAt().isAfter(LocalDateTime.now())) {
            throw new BizException("预计结束时间必须晚于当前时间");
        }

        LocalDateTime now = LocalDateTime.now();
        MachineMaintenance maintenance = new MachineMaintenance();
        maintenance.setMaintenanceNo(bizNoGenerator.nextMaintenanceNo());
        maintenance.setMachine(machine);
        maintenance.setReason(reason);
        maintenance.setStartedAt(now);
        maintenance.setExpectedFinishAt(req.getExpectedFinishAt());
        maintenance.setStartOperator(operator);
        maintenance.setStatus(MachineMaintenance.ACTIVE);
        maintenance.setCreatedAt(now);
        maintenanceRepository.save(maintenance);

        machine.setStatus("检修");
        machine.setLastMaintainAt(now);
    }

    private void finishMaintenance(PaperMachine machine, MachineMaintenance open, String operator) {
        if (open == null || !MachineMaintenance.ACTIVE.equals(open.getStatus())) {
            throw new BizException(currentMachineReason(machine, open, "当前没有检修中的检修单"));
        }
        if (!"检修".equals(machine.getStatus())) {
            throw new BizException(currentMachineReason(machine, open, "只有检修中的纸机可以完成检修"));
        }
        LocalDateTime now = LocalDateTime.now();
        open.setStatus(MachineMaintenance.AWAIT_RESUME);
        open.setFinishedAt(now);
        open.setFinishOperator(operator);
        maintenanceRepository.save(open);
        machine.setStatus("停机");
    }

    private void resumeRunning(PaperMachine machine, MachineMaintenance open, String operator) {
        if (open == null || !MachineMaintenance.AWAIT_RESUME.equals(open.getStatus())) {
            MachineMaintenance latest = open == null
                    ? maintenanceRepository.findFirstByMachineIdOrderByStartedAtDesc(machine.getId()).orElse(null)
                    : open;
            throw new BizException(currentMachineReason(machine, latest,
                    "检修尚未完成并回到停机，不能直接恢复运行"));
        }
        if (!"停机".equals(machine.getStatus())) {
            throw new BizException(currentMachineReason(machine, open,
                    "检修完成后纸机必须先处于停机，才能由经办人确认恢复运行"));
        }
        LocalDateTime now = LocalDateTime.now();
        open.setStatus(MachineMaintenance.RESUMED);
        open.setResumedAt(now);
        open.setResumeOperator(operator);
        maintenanceRepository.save(open);
        machine.setStatus("运行");
    }

    private MachineMaintenance findOpenMaintenance(Long machineId) {
        return maintenanceRepository
                .findFirstByMachineIdAndStatusInOrderByStartedAtDesc(machineId, OPEN_MAINTENANCE_STATUSES)
                .orElse(null);
    }

    private List<String> allowedActions(String status, MachineMaintenance current) {
        if (current != null && MachineMaintenance.AWAIT_RESUME.equals(current.getStatus())) {
            return List.of("恢复运行");
        }
        if ("检修".equals(status) || current != null
                && MachineMaintenance.ACTIVE.equals(current.getStatus())) {
            return List.of("完成检修");
        }
        if ("运行".equals(status)) {
            return List.of("停机", "开始检修");
        }
        if ("停机".equals(status)) {
            return List.of("运行", "开始检修");
        }
        return List.of();
    }

    private String productionBlockReason(PaperMachine machine, MachineMaintenance current) {
        if (!"检修".equals(machine.getStatus())) {
            return null;
        }
        String ticket = current == null ? "未关联检修单" : current.getMaintenanceNo();
        String operator = current == null ? "-" : current.getStartOperator();
        return "纸机 " + machine.getMachineCode() + " 当前为「检修」（检修单 " + ticket
                + "，经办人 " + operator + "），禁止新建母卷或把待检母卷判为可用；"
                + "需完成检修回到停机，并由经办人确认恢复运行后再产出";
    }

    private String currentMachineReason(PaperMachine machine, MachineMaintenance current, String actionReason) {
        StringBuilder sb = new StringBuilder(actionReason)
                .append("；当前纸机状态为「").append(machine.getStatus()).append("」");
        if (current != null) {
            sb.append("，检修单 ").append(current.getMaintenanceNo())
                    .append(" 状态「").append(current.getStatus()).append("」")
                    .append("，原因：").append(current.getReason());
        }
        return sb.toString();
    }

    private String requiredOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("经办人不能为空");
        }
        return operator.trim();
    }
}
