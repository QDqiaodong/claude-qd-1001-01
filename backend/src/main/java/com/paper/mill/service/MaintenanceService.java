package com.paper.mill.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.dto.MaintenanceOperatorRequest;
import com.paper.mill.dto.MaintenanceStartRequest;
import com.paper.mill.entity.MachineMaintenance;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.MachineMaintenanceRepository;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;

/**
 * 纸机检修状态机与检修单台账。
 *
 * <p>规则链：运行/停机 --(无未完成产出登记)--&gt; 检修 --完成确认--&gt; 停机 --经办人复机确认--&gt; 运行。
 * 所有状态动作先锁纸机行，并发发起时后到一方只能看到先提交一方留下的当前状态和检修单。</p>
 */
@Service
public class MaintenanceService {

    private final PaperMachineRepository machineRepository;
    private final MachineMaintenanceRepository maintenanceRepository;
    private final PaperRollRepository rollRepository;
    private final BizNoGenerator bizNoGenerator;

    public MaintenanceService(PaperMachineRepository machineRepository,
                              MachineMaintenanceRepository maintenanceRepository,
                              PaperRollRepository rollRepository,
                              BizNoGenerator bizNoGenerator) {
        this.machineRepository = machineRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.rollRepository = rollRepository;
        this.bizNoGenerator = bizNoGenerator;
    }

    /** 运行/停机进入检修：先查未完成产出登记，再在同一把纸机行锁内建检修单。 */
    @Transactional
    public MachineMaintenance start(Long machineId, MaintenanceStartRequest req) {
        PaperMachine machine = machineRepository.findByIdForUpdate(machineId)
                .orElseThrow(() -> new BizException("纸机不存在：" + machineId));

        if ("检修".equals(machine.getStatus())) {
            MachineMaintenance active = activeMaintenance(machineId);
            throw new BizException(blockedByCurrentState(machine, active,
                    "该纸机已在检修中，不能重复发起检修"));
        }
        if (!"运行".equals(machine.getStatus()) && !"停机".equals(machine.getStatus())) {
            throw new BizException("只有「运行」或「停机」纸机可以进入检修，当前状态：" + machine.getStatus());
        }
        if (!req.getExpectedEndAt().isAfter(req.getStartedAt())) {
            throw new BizException("预计结束时间必须晚于检修开始时间");
        }

        List<PaperRoll> pendingRolls = rollRepository.findByMachineIdAndStatus(machineId, "待检");
        if (!pendingRolls.isEmpty()) {
            String nos = pendingRolls.stream().limit(5).map(PaperRoll::getRollNo)
                    .reduce((a, b) -> a + "、" + b).orElse("");
            String suffix = pendingRolls.size() > 5 ? " 等" : "";
            throw new BizException("存在 " + pendingRolls.size()
                    + " 条未完成的产出登记（待检母卷：" + nos + suffix
                    + "）；请先检验为「可用」或判废，再提交检修，纸机未进入检修");
        }

        LocalDateTime now = LocalDateTime.now();
        MachineMaintenance order = new MachineMaintenance();
        order.setMaintenanceNo(bizNoGenerator.nextMaintenanceNo());
        order.setMachine(machine);
        order.setReason(req.getReason().trim());
        order.setStartedAt(req.getStartedAt());
        order.setExpectedEndAt(req.getExpectedEndAt());
        order.setOperator(req.getOperator().trim());
        order.setRecordStatus(MachineMaintenance.IN_PROGRESS);
        order.setCreatedAt(now);
        MachineMaintenance saved = maintenanceRepository.save(order);

        machine.setStatus("检修");
        machine.setLastMaintainAt(req.getStartedAt());
        machineRepository.save(machine);
        return saved;
    }

    /** 检修完成：只能从检修回到停机，检修单进入「待复机确认」。 */
    @Transactional
    public MachineMaintenance finish(Long machineId, MaintenanceOperatorRequest req) {
        PaperMachine machine = machineRepository.findByIdForUpdate(machineId)
                .orElseThrow(() -> new BizException("纸机不存在：" + machineId));
        MachineMaintenance order = activeMaintenance(machineId);
        if (!"检修".equals(machine.getStatus()) || order == null) {
            throw new BizException(blockedByCurrentState(machine, latestOrder(machineId),
                    "当前没有进行中的检修单，不能执行检修完成"));
        }
        ensureOperator(order, req.getOperator(), "完成检修");

        LocalDateTime now = LocalDateTime.now();
        order.setRecordStatus(MachineMaintenance.COMPLETED);
        order.setFinishedAt(now);
        order.setFinishOperator(req.getOperator().trim());
        maintenanceRepository.save(order);

        machine.setStatus("停机");
        machineRepository.save(machine);
        return order;
    }

    /** 经办人确认后，才允许从停机恢复运行。 */
    @Transactional
    public MachineMaintenance resume(Long machineId, MaintenanceOperatorRequest req) {
        PaperMachine machine = machineRepository.findByIdForUpdate(machineId)
                .orElseThrow(() -> new BizException("纸机不存在：" + machineId));
        if (!"停机".equals(machine.getStatus())) {
            throw new BizException(blockedByCurrentState(machine, latestOrder(machineId),
                    "检修完成并经经办人确认后才能恢复运行，当前状态：" + machine.getStatus()));
        }
        MachineMaintenance order = maintenanceRepository
                .findFirstByMachineIdAndRecordStatusOrderByIdDesc(
                        machineId, MachineMaintenance.COMPLETED)
                .orElseThrow(() -> new BizException(blockedByCurrentState(machine, latestOrder(machineId),
                        "没有等待复机确认的检修单，不能执行经办人复机确认")));

        ensureOperator(order, req.getOperator(), "恢复运行");

        LocalDateTime now = LocalDateTime.now();
        order.setRecordStatus(MachineMaintenance.RESUMED);
        order.setResumedAt(now);
        order.setResumeOperator(req.getOperator().trim());
        maintenanceRepository.save(order);

        machine.setStatus("运行");
        machineRepository.save(machine);
        return order;
    }

    @Transactional(readOnly = true)
    public List<MachineMaintenance> history(Long machineId) {
        machineRepository.findById(machineId)
                .orElseThrow(() -> new BizException("纸机不存在：" + machineId));
        return maintenanceRepository.findByMachineIdOrderByStartedAtDescIdDesc(machineId);
    }

    @Transactional(readOnly = true)
    public MachineMaintenance activeMaintenance(Long machineId) {
        return maintenanceRepository.findFirstByMachineIdAndRecordStatusOrderByIdDesc(
                machineId, MachineMaintenance.IN_PROGRESS).orElse(null);
    }

    private MachineMaintenance latestOrder(Long machineId) {
        return maintenanceRepository.findByMachineIdOrderByStartedAtDescIdDesc(machineId)
                .stream().findFirst().orElse(null);
    }

    private void ensureOperator(MachineMaintenance order, String operator, String action) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("经办人不能为空");
        }
        if (!order.getOperator().trim().equals(operator.trim())) {
            throw new BizException("经办人不匹配：检修单 " + order.getMaintenanceNo()
                    + " 的经办人为「" + order.getOperator() + "」，不能由「"
                    + operator.trim() + "」" + action + "；当前仍为「"
                    + order.getRecordStatus() + "」");
        }
    }

    /** 并发失败时必须把先到一方造成的当前状态、检修单号和原因带给后到一方。 */
    private String blockedByCurrentState(PaperMachine machine, MachineMaintenance order, String prefix) {
        StringBuilder sb = new StringBuilder(prefix)
                .append("；当前纸机状态为「").append(machine.getStatus()).append("」");
        if (order != null) {
            sb.append("，当前检修单 ").append(order.getMaintenanceNo())
                    .append("（").append(order.getRecordStatus())
                    .append("，原因：").append(order.getReason()).append("）");
        }
        return sb.toString();
    }
}
