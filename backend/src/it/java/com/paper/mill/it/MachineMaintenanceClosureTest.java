package com.paper.mill.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.paper.mill.common.BizException;
import com.paper.mill.dto.MachineActionRequest;
import com.paper.mill.dto.MarkUsableRequest;
import com.paper.mill.dto.ProductionRequest;
import com.paper.mill.entity.MachineMaintenance;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.repository.MachineMaintenanceRepository;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.service.PaperMachineService;
import com.paper.mill.service.ProductionAccessService;

/**
 * 纸机检修状态与母卷产出准入联动集成测试。
 */
@SpringBootTest
@ActiveProfiles("it")
class MachineMaintenanceClosureTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    @Autowired PaperMachineService machineService;
    @Autowired ProductionAccessService productionService;
    @Autowired PaperMachineRepository machineRepository;
    @Autowired PaperRollRepository rollRepository;
    @Autowired MachineMaintenanceRepository maintenanceRepository;

    @Test
    void maintenance_flow_blocks_until_pending_roll_finished_then_stop_and_confirm_run() {
        PaperMachine machine = machine("运行");
        PaperRoll pending = pendingRoll(machine);

        MachineActionRequest start = action("开始检修", "张三", "更换筛鼓");
        BizException blocked = assertThrows(BizException.class,
                () -> machineService.performAction(machine.getId(), start));
        assertThat(blocked.getMessage())
                .contains("未完成的产出登记")
                .contains(pending.getRollNo());
        assertThat(machineRepository.findById(machine.getId()).orElseThrow().getStatus()).isEqualTo("运行");

        machineService.performAction(machine.getId(), action("停机", "张三", null));
        BizException stillPending = assertThrows(BizException.class,
                () -> machineService.performAction(machine.getId(), action("开始检修", "张三", "更换筛鼓")));
        assertThat(stillPending.getMessage()).contains(pending.getRollNo());

        reject(pending);
        machineService.performAction(machine.getId(), action("开始检修", "张三", "更换筛鼓"));

        PaperMachine underRepair = machineRepository.findById(machine.getId()).orElseThrow();
        MachineMaintenance ticket = maintenanceRepository
                .findByMachineIdOrderByStartedAtDesc(machine.getId()).get(0);
        assertThat(underRepair.getStatus()).isEqualTo("检修");
        assertThat(ticket.getStatus()).isEqualTo(MachineMaintenance.ACTIVE);
        assertThat(ticket.getReason()).isEqualTo("更换筛鼓");
        assertThat(ticket.getStartOperator()).isEqualTo("张三");
        assertThat(ticket.getExpectedFinishAt()).isAfter(LocalDateTime.now());

        // 检修完成只能回到停机，不能直接运行
        machineService.performAction(machine.getId(), action("完成检修", "李四", null));
        assertThat(machineRepository.findById(machine.getId()).orElseThrow().getStatus()).isEqualTo("停机");
        ticket = maintenanceRepository.findById(ticket.getId()).orElseThrow();
        assertThat(ticket.getStatus()).isEqualTo(MachineMaintenance.AWAIT_RESUME);
        assertThat(ticket.getFinishOperator()).isEqualTo("李四");

        BizException directRun = assertThrows(BizException.class,
                () -> machineService.performAction(machine.getId(), action("运行", "李四", null)));
        assertThat(directRun.getMessage()).contains("请先在检修单上使用「恢复运行」");

        machineService.performAction(machine.getId(), action("恢复运行", "王五", null));
        assertThat(machineRepository.findById(machine.getId()).orElseThrow().getStatus()).isEqualTo("运行");
        assertThat(maintenanceRepository.findById(ticket.getId()).orElseThrow().getStatus())
                .isEqualTo(MachineMaintenance.RESUMED);
        assertThat(maintenanceRepository.findByMachineIdOrderByStartedAtDesc(machine.getId()))
                .extracting(MachineMaintenance::getId).containsExactly(ticket.getId());
    }

    @Test
    void during_maintenance_new_roll_and_mark_usable_are_blocked_but_existing_rolls_remain() {
        PaperMachine machine = machine("运行");
        PaperRoll existing = usableRoll(machine);
        machineService.performAction(machine.getId(), action("停机", "张三", null));
        machineService.performAction(machine.getId(), action("开始检修", "张三", "更换真空辊"));

        ProductionRequest req = productionRequest(machine, "block-create");
        BizException createBlocked = assertThrows(BizException.class,
                () -> productionService.createRoll(req));
        assertThat(createBlocked.getMessage()).contains("检修").contains("禁止新建母卷");
        assertThat(rollRepository.findByRollNo(req.getRollNo())).isEmpty();
        // 同令牌重放只返回同一持久化原因，不重复产生留痕
        assertThrows(BizException.class, () -> productionService.createRoll(req));

        PaperRoll pending = pendingRoll(machine);
        MarkUsableRequest mark = markRequest("block-mark");
        BizException markBlocked = assertThrows(BizException.class,
                () -> productionService.markUsable(pending.getId(), mark));
        assertThat(markBlocked.getMessage()).contains("检修").contains("禁止");
        assertThat(rollRepository.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo("待检");

        List<ProductionAttempt> logs = machineService.listProductionBlocks(machine.getId());
        assertThat(logs).hasSize(2);
        assertThat(logs).extracting(ProductionAttempt::getActionType)
                .containsExactlyInAnyOrder(ProductionAttempt.CREATE_ROLL, ProductionAttempt.MARK_USABLE);
        assertThat(logs).allSatisfy(log -> assertThat(log.getFailReason()).contains("检修单"));

        // 既有母卷不消失，仍归属原纸机；完成检修后刚才待检卷可判可用，之后可新建
        machineService.performAction(machine.getId(), action("完成检修", "张三", null));
        machineService.performAction(machine.getId(), action("恢复运行", "张三", null));
        productionService.markUsable(pending.getId(), markRequest("ok-mark"));
        PaperRoll created = productionService.createRoll(productionRequest(machine, "after-repair"));
        assertThat(created.getMachine().getId()).isEqualTo(machine.getId());
        assertThat(rollRepository.findById(existing.getId()).orElseThrow().getMachine().getId())
                .isEqualTo(machine.getId());
    }

    @Test
    void concurrent_maintenance_actions_have_one_winner_and_loser_sees_current_state() throws Exception {
        PaperMachine startMachine = machine("运行");
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService startPool = Executors.newFixedThreadPool(2);
        var startFailures = new java.util.concurrent.atomic.AtomicInteger();
        var startErrors = new java.util.concurrent.atomic.AtomicReference<String>();

        Future<?> f1 = startPool.submit(() ->
                runAction(startBarrier, startMachine.getId(), action("开始检修", "操作员A", "同时检修"), startFailures, startErrors));
        Future<?> f2 = startPool.submit(() ->
                runAction(startBarrier, startMachine.getId(), action("开始检修", "操作员B", "同时检修"), startFailures, startErrors));
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        startPool.shutdownNow();
        assertThat(startFailures.get()).isEqualTo(1);
        assertThat(startErrors.get()).contains("当前纸机状态为「检修」").contains("同时检修");
        assertThat(machineRepository.findById(startMachine.getId()).orElseThrow().getStatus()).isEqualTo("检修");

        PaperMachine machine = machine("运行");
        machineService.performAction(machine.getId(), action("开始检修", "发起人", "并发恢复测试"));
        machineService.performAction(machine.getId(), action("完成检修", "完成人", null));

        CyclicBarrier resumeBarrier = new CyclicBarrier(2);
        ExecutorService resumePool = Executors.newFixedThreadPool(2);
        var resumeFailures = new java.util.concurrent.atomic.AtomicInteger();
        var resumeErrors = new java.util.concurrent.atomic.AtomicReference<String>();
        Future<?> r1 = resumePool.submit(() -> runAction(resumeBarrier, machine.getId(),
                action("恢复运行", "操作员A", null), resumeFailures, resumeErrors));
        Future<?> r2 = resumePool.submit(() -> runAction(resumeBarrier, machine.getId(),
                action("恢复运行", "操作员B", null), resumeFailures, resumeErrors));
        r1.get(30, TimeUnit.SECONDS);
        r2.get(30, TimeUnit.SECONDS);
        resumePool.shutdownNow();

        assertThat(resumeFailures.get()).isEqualTo(1);
        assertThat(resumeErrors.get()).contains("当前纸机状态为「运行」").contains("并发恢复测试");
        assertThat(machineRepository.findById(machine.getId()).orElseThrow().getStatus()).isEqualTo("运行");
    }

    private void runAction(CyclicBarrier barrier, Long machineId, MachineActionRequest req,
                           java.util.concurrent.atomic.AtomicInteger failures,
                           java.util.concurrent.atomic.AtomicReference<String> error) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            machineService.performAction(machineId, req);
        } catch (BizException e) {
            failures.incrementAndGet();
            error.set(e.getMessage());
        } catch (Exception e) {
            failures.incrementAndGet();
            error.set("UNEXPECTED: " + e);
        }
    }

    private PaperMachine machine(String status) {
        long n = SEQ.incrementAndGet();
        PaperMachine m = new PaperMachine();
        m.setMachineCode("PM-MX-" + n);
        m.setMachineName("检修测试纸机" + n);
        m.setDesignWidthMm(3200);
        m.setDesignSpeedMpm(500);
        m.setStatus(status);
        return machineRepository.save(m);
    }

    private PaperRoll pendingRoll(PaperMachine machine) {
        return roll(machine, "待检");
    }

    private PaperRoll usableRoll(PaperMachine machine) {
        return roll(machine, "可用");
    }

    private PaperRoll roll(PaperMachine machine, String status) {
        long n = SEQ.incrementAndGet();
        PaperRoll roll = new PaperRoll();
        roll.setRollNo("R-MX-" + n);
        roll.setMachine(machine);
        roll.setGrammageGsm(70);
        roll.setWidthMm(3100);
        roll.setLengthM(5000);
        roll.setWeightKg(new BigDecimal("1000.00"));
        roll.setGrade("一等");
        roll.setStatus(status);
        return rollRepository.save(roll);
    }

    private void reject(PaperRoll roll) {
        roll.setStatus("报废");
        rollRepository.save(roll);
    }

    private MachineActionRequest action(String action, String operator, String reason) {
        MachineActionRequest req = new MachineActionRequest();
        req.setAction(action);
        req.setOperator(operator);
        req.setReason(reason);
        req.setExpectedFinishAt(LocalDateTime.now().plusHours(2));
        return req;
    }

    private ProductionRequest productionRequest(PaperMachine machine, String token) {
        long n = SEQ.incrementAndGet();
        ProductionRequest req = new ProductionRequest();
        req.setRequestToken(token + "-" + n);
        req.setRollNo("R-NEW-MX-" + n);
        req.setMachineId(machine.getId());
        req.setGrammageGsm(70);
        req.setTargetGsm(70);
        req.setWidthMm(3000);
        req.setLengthM(4000);
        req.setWeightKg(new BigDecimal("800.00"));
        req.setOperator("赵操作员");
        return req;
    }

    private MarkUsableRequest markRequest(String token) {
        MarkUsableRequest req = new MarkUsableRequest();
        req.setRequestToken(token + "-" + SEQ.incrementAndGet());
        req.setOperator("钱检验员");
        return req;
    }
}
