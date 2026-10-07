package com.paper.mill.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.paper.mill.common.BizException;
import com.paper.mill.dto.MaintenanceOperatorRequest;
import com.paper.mill.dto.MaintenanceStartRequest;
import com.paper.mill.dto.PaperRollProduceRequest;
import com.paper.mill.dto.RollInspectionRequest;
import com.paper.mill.entity.MachineMaintenance;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.repository.MachineMaintenanceRepository;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.repository.ProductionAttemptRepository;
import com.paper.mill.service.MaintenanceService;
import com.paper.mill.service.PaperRollService;

/**
 * 纸机检修状态与母卷产出准入联动集成测试。
 */
@SpringBootTest
@ActiveProfiles("it")
class MaintenanceAdmissionTest {

    @Autowired MaintenanceService maintenanceService;
    @Autowired PaperRollService rollService;
    @Autowired PaperMachineRepository machineRepository;
    @Autowired PaperRollRepository rollRepository;
    @Autowired MachineMaintenanceRepository maintenanceRepository;
    @Autowired ProductionAttemptRepository productionAttemptRepository;

    @Test
    void pending_production_blocks_maintenance_and_lists_explicit_reason() {
        Fixture f = new Fixture(machineRepository, rollRepository, "运行", "待检");

        MaintenanceStartRequest req = f.startRequest("更换网部", "王建国");
        BizException e = assertThrows(BizException.class, () -> maintenanceService.start(f.machineId, req));

        assertThat(e.getMessage()).contains("存在 1 条未完成的产出登记")
                .contains(f.rollNo)
                .contains("纸机未进入检修");
        assertThat(machineRepository.findById(f.machineId).orElseThrow().getStatus()).isEqualTo("运行");
        assertThat(maintenanceRepository.findByMachineIdOrderByStartedAtDescIdDesc(f.machineId)).isEmpty();
    }

    @Test
    void maintenance_start_finish_and_operator_resume_follow_required_chain() {
        Fixture f = new Fixture(machineRepository, rollRepository, "运行", "可用");

        MachineMaintenance order = maintenanceService.start(f.machineId,
                f.startRequest("烘缸检修", "王建国"));
        assertThat(order.getRecordStatus()).isEqualTo(MachineMaintenance.IN_PROGRESS);
        assertThat(machineRepository.findById(f.machineId).orElseThrow().getStatus()).isEqualTo("检修");

        MaintenanceOperatorRequest wrong = f.operatorRequest("李四");
        BizException wrongFinish = assertThrows(BizException.class,
                () -> maintenanceService.finish(f.machineId, wrong));
        assertThat(wrongFinish.getMessage()).contains("经办人不匹配").contains("王建国");
        assertThat(machineRepository.findById(f.machineId).orElseThrow().getStatus()).isEqualTo("检修");

        maintenanceService.finish(f.machineId, f.operatorRequest("王建国"));
        assertThat(machineRepository.findById(f.machineId).orElseThrow().getStatus()).isEqualTo("停机");
        assertThat(maintenanceRepository.findById(order.getId()).orElseThrow().getRecordStatus())
                .isEqualTo(MachineMaintenance.COMPLETED);

        BizException wrongResume = assertThrows(BizException.class,
                () -> maintenanceService.resume(f.machineId, wrong));
        assertThat(wrongResume.getMessage()).contains("经办人不匹配");
        assertThat(machineRepository.findById(f.machineId).orElseThrow().getStatus()).isEqualTo("停机");

        maintenanceService.resume(f.machineId, f.operatorRequest("王建国"));
        assertThat(machineRepository.findById(f.machineId).orElseThrow().getStatus()).isEqualTo("运行");
        assertThat(maintenanceRepository.findById(order.getId()).orElseThrow().getRecordStatus())
                .isEqualTo(MachineMaintenance.RESUMED);
    }

    @Test
    void maintenance_blocks_new_roll_and_marking_usable_but_keeps_existing_roll_ledger() {
        Fixture f = new Fixture(machineRepository, rollRepository, "运行", "待检");
        Long existingRollId = f.rollId;

        // 待检卷判废后没有未完成产出登记，才允许进入检修
        RollInspectionRequest scrap = new RollInspectionRequest();
        scrap.setTarget("报废");
        scrap.setOperator("王建国");
        rollService.inspect(existingRollId, scrap);
        maintenanceService.start(f.machineId, f.startRequest("刮刀更换", "王建国"));

        PaperRollProduceRequest produce = f.produceRequest("R-BLOCK-IT", "王建国");
        BizException produceError = assertThrows(BizException.class,
                () -> rollService.produce(f.machineId, produce));
        assertThat(produceError.getMessage()).contains("检修中不能新建纸卷");
        assertThat(rollRepository.findByRollNo("R-BLOCK-IT")).isEmpty();

        Fixture usableFixture = new Fixture(machineRepository, rollRepository, "运行", "可用");
        maintenanceService.start(usableFixture.machineId,
                usableFixture.startRequest("停机检查", "李秀兰"));
        // 正常规则链下检修开始时不会有待检卷；这里模拟绕过前端/并发残留，验证服务端准入仍拦截。
        PaperRoll bypassPending = rollRepository.findById(usableFixture.rollId).orElseThrow();
        bypassPending.setStatus("待检");
        rollRepository.save(bypassPending);
        RollInspectionRequest markUsable = new RollInspectionRequest();
        markUsable.setTarget("可用");
        markUsable.setOperator("李秀兰");
        BizException markError = assertThrows(BizException.class,
                () -> rollService.inspect(usableFixture.rollId, markUsable));
        assertThat(markError.getMessage()).contains("检修中不能把新纸卷标记为「可用」");
        assertThat(rollRepository.findById(usableFixture.rollId).orElseThrow().getStatus()).isEqualTo("待检");

        List<ProductionAttempt> attempts = productionAttemptRepository
                .findByMachineIdOrderByAttemptedAtDescIdDesc(f.machineId);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).getActionType()).isEqualTo(ProductionAttempt.ACTION_PRODUCE);
        assertThat(attempts.get(0).getBlockReason()).contains("检修中不能新建纸卷");

        // 检修状态变化不删除、不改写已产出母卷的纸机归属
        PaperRoll existing = rollRepository.findById(existingRollId).orElseThrow();
        assertThat(existing.getMachine().getId()).isEqualTo(f.machineId);
        assertThat(existing.getStatus()).isEqualTo("报废");
    }

    @Test
    void concurrent_maintenance_starts_only_one_succeeds_and_loser_sees_active_order() throws Exception {
        Fixture f = new Fixture(machineRepository, rollRepository, "运行", "可用");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Boolean> aOk = new AtomicReference<>();
        AtomicReference<String> aErr = new AtomicReference<>();
        AtomicReference<Boolean> bOk = new AtomicReference<>();
        AtomicReference<String> bErr = new AtomicReference<>();

        Future<?> fa = pool.submit(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                maintenanceService.start(f.machineId, f.startRequest("操作员A的检修", "操作员A"));
                aOk.set(true);
            } catch (BizException e) {
                aOk.set(false);
                aErr.set(e.getMessage());
            } catch (Exception e) {
                aOk.set(false);
                aErr.set("UNEXPECTED: " + e);
            }
        });
        Future<?> fb = pool.submit(() -> {
            try {
                barrier.await(10, TimeUnit.SECONDS);
                maintenanceService.start(f.machineId, f.startRequest("操作员B的检修", "操作员B"));
                bOk.set(true);
            } catch (BizException e) {
                bOk.set(false);
                bErr.set(e.getMessage());
            } catch (Exception e) {
                bOk.set(false);
                bErr.set("UNEXPECTED: " + e);
            }
        });
        fa.get(30, TimeUnit.SECONDS);
        fb.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(aOk.get() || bOk.get()).isTrue();
        assertThat(aOk.get() && bOk.get()).isFalse();
        String loserErr = Boolean.TRUE.equals(aOk.get()) ? bErr.get() : aErr.get();
        assertThat(loserErr).contains("已在检修中")
                .contains("当前纸机状态为「检修」")
                .contains("当前检修单");
        assertThat(maintenanceRepository
                .findByMachineIdAndRecordStatusOrderByIdDesc(f.machineId, MachineMaintenance.IN_PROGRESS))
                .hasSize(1);
    }

    @Test
    void concurrent_resumes_only_one_succeeds_and_loser_sees_running_state() throws Exception {
        Fixture f = new Fixture(machineRepository, rollRepository, "运行", "可用");
        maintenanceService.start(f.machineId, f.startRequest("例行检修", "王建国"));
        maintenanceService.finish(f.machineId, f.operatorRequest("王建国"));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Boolean> aOk = new AtomicReference<>();
        AtomicReference<String> aErr = new AtomicReference<>();
        AtomicReference<Boolean> bOk = new AtomicReference<>();
        AtomicReference<String> bErr = new AtomicReference<>();

        Future<?> fa = pool.submit(() -> runOnce(barrier, aOk, aErr,
                () -> maintenanceService.resume(f.machineId, f.operatorRequest("王建国"))));
        Future<?> fb = pool.submit(() -> runOnce(barrier, bOk, bErr,
                () -> maintenanceService.resume(f.machineId, f.operatorRequest("王建国"))));
        fa.get(30, TimeUnit.SECONDS);
        fb.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(aOk.get() || bOk.get()).isTrue();
        assertThat(aOk.get() && bOk.get()).isFalse();
        String loserErr = Boolean.TRUE.equals(aOk.get()) ? bErr.get() : aErr.get();
        assertThat(loserErr).contains("检修完成并经经办人确认后才能恢复运行")
                .contains("当前纸机状态为「运行」")
                .contains("当前检修单");
        assertThat(machineRepository.findById(f.machineId).orElseThrow().getStatus()).isEqualTo("运行");
    }

    private void runOnce(CyclicBarrier barrier,
                         AtomicReference<Boolean> ok, AtomicReference<String> err, Runnable action) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            action.run();
            ok.set(true);
        } catch (BizException e) {
            ok.set(false);
            err.set(e.getMessage());
        } catch (Exception e) {
            ok.set(false);
            err.set("UNEXPECTED: " + e);
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private static final class Fixture {
        final Long machineId;
        final Long rollId;
        final String rollNo;

        Fixture(PaperMachineRepository machineRepository, PaperRollRepository rollRepository,
                String machineStatus, String rollStatus) {
            long n = SEQ.incrementAndGet();
            PaperMachine machine = new PaperMachine();
            machine.setMachineCode("PM-MT-" + n);
            machine.setMachineName("检修测试纸机" + n);
            machine.setDesignWidthMm(3200);
            machine.setDesignSpeedMpm(500);
            machine.setStatus(machineStatus);
            machine.setLastMaintainAt(LocalDateTime.now().minusDays(1));
            machine = machineRepository.save(machine);
            machineId = machine.getId();

            rollNo = "R-MT-" + n;
            PaperRoll roll = new PaperRoll();
            roll.setRollNo(rollNo);
            roll.setMachine(machine);
            roll.setGrammageGsm(70);
            roll.setWidthMm(3000);
            roll.setLengthM(1000);
            roll.setWeightKg(new java.math.BigDecimal("220.00"));
            roll.setGrade("一等");
            roll.setStatus(rollStatus);
            roll.setProducedAt(LocalDateTime.now());
            roll = rollRepository.save(roll);
            rollId = roll.getId();
        }

        MaintenanceStartRequest startRequest(String reason, String operator) {
            MaintenanceStartRequest req = new MaintenanceStartRequest();
            req.setReason(reason);
            req.setStartedAt(LocalDateTime.now());
            req.setExpectedEndAt(LocalDateTime.now().plusHours(4));
            req.setOperator(operator);
            return req;
        }

        MaintenanceOperatorRequest operatorRequest(String operator) {
            MaintenanceOperatorRequest req = new MaintenanceOperatorRequest();
            req.setOperator(operator);
            return req;
        }

        PaperRollProduceRequest produceRequest(String no, String operator) {
            PaperRollProduceRequest req = new PaperRollProduceRequest();
            req.setRollNo(no);
            req.setGrammageGsm(70);
            req.setTargetGsm(70);
            req.setWidthMm(3000);
            req.setLengthM(1000);
            req.setWeightKg(new java.math.BigDecimal("220.00"));
            req.setOperator(operator);
            return req;
        }
    }
}
