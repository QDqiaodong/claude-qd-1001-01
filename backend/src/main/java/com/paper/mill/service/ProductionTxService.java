package com.paper.mill.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.dto.MarkUsableRequest;
import com.paper.mill.dto.ProductionRequest;
import com.paper.mill.entity.MachineMaintenance;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.repository.MachineMaintenanceRepository;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.repository.ProductionAttemptRepository;

/**
 * 抄纸工段 · 母卷产出准入事务执行器。
 *
 * <p>新建母卷 / 判可用都先锁纸机（判可用还先锁母卷），在同一事务内读取检修状态并执行。
 * 检修准入失败时只写留痕，不创建母卷、不改变母卷状态；异常用于中断后续写入，
 * 但由 REQUIRES_NEW + noRollbackFor 保证失败原因可以提交。</p>
 */
@Service
public class ProductionTxService {

    private static final List<String> OPEN_STATUSES =
            List.of(MachineMaintenance.ACTIVE, MachineMaintenance.AWAIT_RESUME);

    private final PaperMachineRepository machineRepository;
    private final PaperRollRepository rollRepository;
    private final ProductionAttemptRepository attemptRepository;
    private final MachineMaintenanceRepository maintenanceRepository;

    public ProductionTxService(PaperMachineRepository machineRepository,
                               PaperRollRepository rollRepository,
                               ProductionAttemptRepository attemptRepository,
                               MachineMaintenanceRepository maintenanceRepository) {
        this.machineRepository = machineRepository;
        this.rollRepository = rollRepository;
        this.attemptRepository = attemptRepository;
        this.maintenanceRepository = maintenanceRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = ProductionBlockedException.class)
    public PaperRoll createRoll(ProductionRequest req) {
        LocalDateTime now = LocalDateTime.now();
        List<String> reasons = new ArrayList<>();

        PaperMachine machine = null;
        if (req.getMachineId() != null) {
            machine = machineRepository.findByIdForUpdate(req.getMachineId()).orElse(null);
            if (machine == null) {
                reasons.add("纸机不存在：" + req.getMachineId());
            }
        }

        if (rollRepository.findByRollNo(req.getRollNo().trim()).isPresent()) {
            reasons.add("纸卷号已存在：" + req.getRollNo());
        }

        MachineMaintenance open = machine == null ? null : findOpenMaintenance(machine.getId());
        if (machine != null && "检修".equals(machine.getStatus())) {
            reasons.add(maintenanceBlockReason(machine, open));
        }

        if (machine != null && req.getWidthMm() > machine.getDesignWidthMm()) {
            reasons.add("纸卷幅宽 " + req.getWidthMm()
                    + " 超过纸机 " + machine.getMachineCode() + " 的设计幅宽 "
                    + machine.getDesignWidthMm());
        }

        if (!reasons.isEmpty()) {
            persistFailure(req.getRequestToken(), machine, null,
                    ProductionAttempt.CREATE_ROLL, req.getOperator(), reasons, now);
            throw new ProductionBlockedException(join(reasons));
        }

        PaperRoll roll = new PaperRoll();
        roll.setRollNo(req.getRollNo().trim());
        roll.setMachine(machine);
        roll.setGrammageGsm(req.getGrammageGsm());
        roll.setWidthMm(req.getWidthMm());
        roll.setLengthM(req.getLengthM());
        roll.setWeightKg(req.getWeightKg());
        int target = (req.getTargetGsm() == null || req.getTargetGsm() <= 0)
                ? req.getGrammageGsm() : req.getTargetGsm();
        roll.setGrade(Dict.decideGrade(req.getGrammageGsm(), target));
        roll.setStatus("待检");
        roll.setProducedAt(now);
        return rollRepository.save(roll);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = ProductionBlockedException.class)
    public PaperRoll markUsable(Long rollId, MarkUsableRequest req) {
        LocalDateTime now = LocalDateTime.now();
        List<String> reasons = new ArrayList<>();

        // 固定加锁顺序：先纸机、后母卷，避免与纸机检修动作形成交叉等待。
        PaperRoll located = rollRepository.findById(rollId).orElse(null);
        if (located == null) {
            throw new BizException("纸卷不存在：" + rollId);
        }
        PaperMachine machine = machineRepository.findByIdForUpdate(located.getMachine().getId())
                .orElseThrow(() -> new IllegalStateException("母卷所属纸机不存在"));
        PaperRoll roll = rollRepository.findByIdForUpdate(rollId)
                .orElseThrow(() -> new BizException("纸卷不存在：" + rollId));
        MachineMaintenance open = findOpenMaintenance(machine.getId());

        if (!"待检".equals(roll.getStatus())) {
            reasons.add("不允许从「" + roll.getStatus() + "」转为「可用」");
        }
        if (req.getGrade() != null && !req.getGrade().isBlank()
                && !Dict.isValidRollGrade(req.getGrade().trim())) {
            reasons.add("等级不合法：" + req.getGrade());
        }

        boolean maintenanceBlocked = "检修".equals(machine.getStatus());
        if (maintenanceBlocked) {
            reasons.add(maintenanceBlockReason(machine, open));
        }

        if (!reasons.isEmpty()) {
            // 检修准入原因必须持久化；普通状态错误仍按业务异常阻止，但不污染检修准入台账。
            if (maintenanceBlocked) {
                persistFailure(req.getRequestToken(), machine, roll,
                        ProductionAttempt.MARK_USABLE, req.getOperator(), reasons, now);
            }
            throw new ProductionBlockedException(join(reasons), maintenanceBlocked);
        }

        roll.setStatus("可用");
        if (req.getGrade() != null && !req.getGrade().isBlank()) {
            roll.setGrade(req.getGrade().trim());
        }
        return rollRepository.save(roll);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public ProductionAttempt replay(String token) {
        return attemptRepository.findByRequestToken(token).orElse(null);
    }

    private MachineMaintenance findOpenMaintenance(Long machineId) {
        return maintenanceRepository
                .findFirstByMachineIdAndStatusInOrderByStartedAtDesc(machineId, OPEN_STATUSES)
                .orElse(null);
    }

    private void persistFailure(String token, PaperMachine machine, PaperRoll roll,
                                String actionType, String operator, List<String> reasons,
                                LocalDateTime now) {
        ProductionAttempt attempt = new ProductionAttempt();
        attempt.setRequestToken(token);
        attempt.setMachine(machine);
        attempt.setRoll(roll);
        attempt.setActionType(actionType);
        attempt.setOperator(operator == null ? null : operator.trim());
        attempt.setResult(ProductionAttempt.FAILED);
        attempt.setFailReason(join(reasons));
        attempt.setAttemptedAt(now);
        attemptRepository.save(attempt);
    }

    private String maintenanceBlockReason(PaperMachine machine, MachineMaintenance open) {
        String ticket = open == null ? "未关联检修单" : open.getMaintenanceNo();
        String operator = open == null ? "-" : open.getStartOperator();
        return "纸机 " + machine.getMachineCode() + " 当前为「检修」（检修单 " + ticket
                + "，经办人 " + operator + "），禁止新建母卷或把待检母卷判为可用；"
                + "需完成检修回到停机，并由经办人确认恢复运行后再操作";
    }

    private static String join(List<String> reasons) {
        return String.join("；", reasons);
    }

    /** 业务条件失效。persisted=true 表示失败原因已经写入准入留痕。 */
    public static class ProductionBlockedException extends RuntimeException {
        private final boolean persisted;

        ProductionBlockedException(String message) {
            this(message, true);
        }

        ProductionBlockedException(String message, boolean persisted) {
            super(message);
            this.persisted = persisted;
        }

        public boolean isPersisted() {
            return persisted;
        }
    }
}
