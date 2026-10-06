package com.paper.mill.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
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
import com.paper.mill.dto.RequisitionRequest;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.CuttingRequisition;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.RequisitionAttempt;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.CuttingRequisitionRepository;
import com.paper.mill.repository.PaperMachineRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.service.CuttingOrderService;
import com.paper.mill.service.RequisitionService;

/**
 * 切裁领料闭环集成测试（H2 / MySQL 兼容模式）。
 *
 * <p>覆盖：成功原子扣减、各种条件失效不落库不动量、requestToken 幂等、
 * 两名操作员并发领同一母卷的行锁裁决、余长阈值自动转「已切完」、
 * 成功记录在订单取消后仍可追溯、失败原因刷新后仍在。</p>
 */
@SpringBootTest
@ActiveProfiles("it")
class RequisitionClosureTest {

    @Autowired RequisitionService requisitionService;
    @Autowired CuttingOrderService orderService;
    @Autowired PaperRollRepository rollRepository;
    @Autowired PaperMachineRepository machineRepository;
    @Autowired CuttingOrderRepository orderRepository;
    @Autowired CuttingRequisitionRepository requisitionRepository;

    // ---------------- 成功路径：同事务写记录 + 扣余量 ----------------

    @Test
    void success_writes_ledger_and_deducts_remain_together() {
        Fixture f = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");

        RequisitionService.SubmitResult r = requisitionService.submit(
                f.orderId, f.req("tok-ok", 300, new BigDecimal("66.00"), "张三"));

        assertThat(r.replayed()).isFalse();
        PaperRoll roll = rollRepository.findById(f.rollId).orElseThrow();
        assertThat(roll.getLengthM()).isEqualTo(700);
        assertThat(roll.getWeightKg()).isEqualByComparingTo("154.00");
        assertThat(roll.getStatus()).isEqualTo("可用");

        List<CuttingRequisition> ledger = requisitionService.listByOrder(f.orderId);
        assertThat(ledger).hasSize(1);
        CuttingRequisition q = ledger.get(0);
        assertThat(q.getRequisitionNo()).startsWith("LL-");
        assertThat(q.getRollNo()).isEqualTo(f.rollNo);
        assertThat(q.getOrderNo()).isEqualTo(f.orderNo);
        assertThat(q.getLengthM()).isEqualTo(300);
        assertThat(q.getOperator()).isEqualTo("张三");
        assertThat(q.getOperatedAt()).isNotNull();
        assertThat(q.getRemainLengthM()).isEqualTo(700);
        assertThat(q.getRemainWeightKg()).isEqualByComparingTo("154.00");
    }

    // ---------------- 条件失效：不写记录、不动余量、原因明确 ----------------

    @Test
    void failures_never_write_ledger_nor_change_remain() {
        // 订单不是「切裁中」
        Fixture f1 = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        CuttingOrder notCutting = orderRepository.findById(f1.orderId).orElseThrow();
        notCutting.setStatus("待排产");
        orderRepository.save(notCutting);
        BizException e1 = assertThrows(BizException.class,
                () -> requisitionService.submit(f1.orderId, f1.req("t-order", 100, "22", "张三")));
        assertThat(e1.getMessage()).contains("只有「切裁中」");
        assertThat(rollRepository.findById(f1.rollId).orElseThrow().getLengthM()).isEqualTo(1000);

        // 母卷不是「可用」
        Fixture f2 = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "已切完");
        BizException e2 = assertThrows(BizException.class,
                () -> requisitionService.submit(f2.orderId, f2.req("t-roll", 100, "22", "张三")));
        assertThat(e2.getMessage()).contains("母卷状态为「已切完」");
        assertThat(rollRepository.findById(f2.rollId).orElseThrow().getLengthM()).isEqualTo(1000);

        // 规格不再匹配：幅宽不足
        Fixture f3 = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        PaperRoll narrow = rollRepository.findById(f3.rollId).orElseThrow();
        narrow.setWidthMm(500);
        rollRepository.save(narrow);
        BizException e3 = assertThrows(BizException.class,
                () -> requisitionService.submit(f3.orderId, f3.req("t-width", 100, "22", "张三")));
        assertThat(e3.getMessage()).contains("幅宽");
        assertThat(rollRepository.findById(f3.rollId).orElseThrow().getLengthM()).isEqualTo(1000);

        // 定量偏差超限
        Fixture f4 = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        PaperRoll otherGsm = rollRepository.findById(f4.rollId).orElseThrow();
        otherGsm.setGrammageGsm(80);
        rollRepository.save(otherGsm);
        BizException e4 = assertThrows(BizException.class,
                () -> requisitionService.submit(f4.orderId, f4.req("t-gsm", 100, "22", "张三")));
        assertThat(e4.getMessage()).contains("定量");
        assertThat(rollRepository.findById(f4.rollId).orElseThrow().getLengthM()).isEqualTo(1000);

        // 长度非正、超过余长、重量超过余重
        Fixture f5 = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        BizException e5 = assertThrows(BizException.class,
                () -> requisitionService.submit(f5.orderId, f5.req("t-len1", 0, "22", "张三")));
        assertThat(e5.getMessage()).contains("长度必须大于 0");
        BizException e6 = assertThrows(BizException.class,
                () -> requisitionService.submit(f5.orderId, f5.req("t-len2", 1001, "22", "张三")));
        assertThat(e6.getMessage()).contains("超过母卷剩余长度 1000");
        BizException e7 = assertThrows(BizException.class,
                () -> requisitionService.submit(f5.orderId, f5.req("t-weight", 100, "220.01", "张三")));
        assertThat(e7.getMessage()).contains("超过母卷剩余重量");
        assertThat(rollRepository.findById(f5.rollId).orElseThrow().getLengthM()).isEqualTo(1000);
        assertThat(rollRepository.findById(f5.rollId).orElseThrow().getWeightKg())
                .isEqualByComparingTo("220.00");
        assertThat(requisitionService.listByOrder(f5.orderId)).isEmpty();

        // 母卷不存在
        Fixture f6 = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        RequisitionRequest missingRoll = f6.req("t-missing", 100, "22", "张三");
        missingRoll.setRollNo("R-NONE");
        BizException e8 = assertThrows(BizException.class,
                () -> requisitionService.submit(f6.orderId, missingRoll));
        assertThat(e8.getMessage()).contains("母卷不存在");
    }

    // ---------------- 失败原因持久化：重开页面仍看到真实余量与原因 ----------------

    @Test
    void failed_reason_persists_for_refresh_and_real_remain_visible() {
        Fixture f = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        assertThrows(BizException.class,
                () -> requisitionService.submit(f.orderId, f.req("t-persist", 1500, "330", "李四")));

        // 模拟刷新/重开页面：真实余量未变
        assertThat(rollRepository.findById(f.rollId).orElseThrow().getLengthM()).isEqualTo(1000);
        List<RequisitionAttempt> logs = requisitionService.attemptsByOrder(f.orderId);
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getResult()).isEqualTo("FAILED");
        assertThat(logs.get(0).getFailReason()).contains("超过母卷剩余长度");
        assertThat(logs.get(0).getOperator()).isEqualTo("李四");
        assertThat(requisitionService.attemptsByRoll(f.rollId)).hasSize(1);
        assertThat(requisitionService.listByOrder(f.orderId)).isEmpty();
    }

    // ---------------- 幂等：同一请求重复到达只返回原结果 ----------------

    @Test
    void duplicate_token_replays_original_success_without_double_deduction() {
        Fixture f = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        RequisitionRequest req = f.req("dup-ok", 300, "66", "张三");

        RequisitionService.SubmitResult first = requisitionService.submit(f.orderId, req);
        RequisitionService.SubmitResult second = requisitionService.submit(f.orderId, req);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.requisition().getId()).isEqualTo(first.requisition().getId());
        assertThat(rollRepository.findById(f.rollId).orElseThrow().getLengthM()).isEqualTo(700);
        assertThat(requisitionService.listByOrder(f.orderId)).hasSize(1);
    }

    @Test
    void duplicate_token_after_failure_replays_same_failure_reason() {
        Fixture f = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        RequisitionRequest req = f.req("dup-fail", 2000, "440", "张三");

        BizException e1 = assertThrows(BizException.class,
                () -> requisitionService.submit(f.orderId, req));
        BizException e2 = assertThrows(BizException.class,
                () -> requisitionService.submit(f.orderId, req));
        assertThat(e2.getMessage()).isEqualTo(e1.getMessage());
        assertThat(rollRepository.findById(f.rollId).orElseThrow().getLengthM()).isEqualTo(1000);
        assertThat(requisitionService.listByOrder(f.orderId)).isEmpty();
        // 同令牌只留一行痕
        assertThat(requisitionService.attemptsByOrder(f.orderId)).hasSize(1);
    }

    // ---------------- 并发：两人同时领同一母卷，只有实际余量可满足的一方成功 ----------------

    @Test
    void concurrent_requests_on_same_roll_only_one_succeeds_with_real_remain() throws Exception {
        // 两名操作员各持一张「切裁中」订单，同时从余量 1000 的同一母卷各领 600：
        // 600 单独都可满足，但 1200 > 1000，无论谁先拿到行锁都只有一方成功
        Fixture fA = new Fixture(rollRepository, machineRepository, orderRepository,
                1000, new BigDecimal("220.00"), "可用");
        Fixture fB = Fixture.orderOnRoll(rollRepository, orderRepository, fA.rollId, fA.rollNo);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Boolean> aOk = new AtomicReference<>();
        AtomicReference<String> aErr = new AtomicReference<>();
        AtomicReference<Boolean> bOk = new AtomicReference<>();
        AtomicReference<String> bErr = new AtomicReference<>();

        Future<?> fa = pool.submit(() -> runOnce(barrier, aOk, aErr,
                () -> requisitionService.submit(fA.orderId, fA.req("cc-A", 600, "132", "操作员A"))));
        Future<?> fb = pool.submit(() -> runOnce(barrier, bOk, bErr,
                () -> requisitionService.submit(fB.orderId, fB.req("cc-B", 600, "132", "操作员B"))));
        fa.get(30, TimeUnit.SECONDS);
        fb.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 恰一方成功、一方失败，且不依赖调度顺序
        assertThat(aOk.get() || bOk.get()).isTrue();
        assertThat(aOk.get() && bOk.get()).isFalse();
        String loserErr = Boolean.TRUE.equals(aOk.get()) ? bErr.get() : aErr.get();
        assertThat(loserErr).contains("超过母卷剩余长度");

        // 母卷只被扣一次：1000 - 600 = 400，绝无负数/扣两次
        PaperRoll roll = rollRepository.findById(fA.rollId).orElseThrow();
        assertThat(roll.getLengthM()).isEqualTo(400);
        assertThat(roll.getWeightKg()).isEqualByComparingTo("88.00");
        // 该卷只有一条成功账本、两条留痕（一成功一失败）
        assertThat(requisitionService.listByRoll(fA.rollId)).hasSize(1);
        List<RequisitionAttempt> logs = requisitionService.attemptsByRoll(fA.rollId);
        assertThat(logs).hasSize(2);
        assertThat(logs.stream().filter(l -> "SUCCESS".equals(l.getResult()))).hasSize(1);
        assertThat(logs.stream().filter(l -> "FAILED".equals(l.getResult()))).hasSize(1);
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

    // ---------------- 阈值：余长 < 200 母卷转「已切完」，成功记录不消失 ----------------

    @Test
    void low_remain_marks_roll_done_but_ledger_survives_order_cancel() {
        Fixture f = new Fixture(rollRepository, machineRepository, orderRepository, 1000, new BigDecimal("220.00"), "可用");
        RequisitionService.SubmitResult r = requisitionService.submit(
                f.orderId, f.req("t-thr", 900, new BigDecimal("198.00"), "王五"));
        assertThat(r.rollStatus()).isEqualTo("已切完");
        assertThat(r.remainLengthM()).isEqualTo(100);
        assertThat(rollRepository.findById(f.rollId).orElseThrow().getStatus()).isEqualTo("已切完");

        // 已切完的母卷不能再领
        BizException e = assertThrows(BizException.class,
                () -> requisitionService.submit(f.orderId, f.req("t-thr2", 10, "2.2", "王五")));
        assertThat(e.getMessage()).contains("已切完");

        // 订单取消后，成功的领料记录依然在（不能因状态变化而消失）
        orderService.transfer(f.orderId, "已取消");
        assertThat(requisitionService.listByOrder(f.orderId)).hasSize(1);
        assertThat(requisitionService.listByRoll(f.rollId)).hasSize(1);
    }

    // ---------------- 测试夹具：每个用例独立一套订单/母卷 ----------------

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private static final class Fixture {
        final Long orderId;
        final Long rollId;
        private final String rollNo;
        private final String orderNo;
        private final PaperRollRepository rollRepository;
        private final CuttingOrderRepository orderRepository;

        Fixture(PaperRollRepository rollRepository, PaperMachineRepository machineRepository,
                CuttingOrderRepository orderRepository,
                int lengthM, BigDecimal weightKg, String rollStatus) {
            this.rollRepository = rollRepository;
            this.orderRepository = orderRepository;
            long n = SEQ.incrementAndGet();
            PaperMachine m = new PaperMachine();
            m.setMachineCode("PM-IT-" + n);
            m.setMachineName("测试纸机" + n);
            m.setDesignWidthMm(3200);
            m.setDesignSpeedMpm(500);
            m.setStatus("运行");
            machineRepository.save(m);

            rollNo = "R-IT-" + n;
            PaperRoll roll = new PaperRoll();
            roll.setRollNo(rollNo);
            roll.setMachine(m);
            roll.setGrammageGsm(70);
            roll.setWidthMm(3150);
            roll.setLengthM(lengthM);
            roll.setWeightKg(weightKg);
            roll.setGrade("一等");
            roll.setStatus(rollStatus);
            rollRepository.save(roll);
            rollId = roll.getId();

            orderId = createOrder(orderRepository, n);
            orderNo = orderRepository.findById(orderId).orElseThrow().getOrderNo();
        }

        /** 只再建一张「切裁中」订单，绑定到已存在的母卷（两名操作员抢同一卷） */
        static Fixture orderOnRoll(PaperRollRepository rollRepository,
                                   CuttingOrderRepository orderRepository,
                                   Long existingRollId, String existingRollNo) {
            long n = SEQ.incrementAndGet();
            Long orderId = createOrder(orderRepository, n);
            return new Fixture(rollRepository, orderRepository, existingRollId, existingRollNo,
                    orderId, "CO-IT-" + n);
        }

        private Fixture(PaperRollRepository rollRepository, CuttingOrderRepository orderRepository,
                        Long existingRollId, String existingRollNo,
                        Long orderId, String orderNo) {
            this.rollRepository = rollRepository;
            this.orderRepository = orderRepository;
            this.rollId = existingRollId;
            this.rollNo = existingRollNo;
            this.orderId = orderId;
            this.orderNo = orderNo;
        }

        private static Long createOrder(CuttingOrderRepository orderRepository, long n) {
            CuttingOrder order = new CuttingOrder();
            order.setOrderNo("CO-IT-" + n);
            order.setCustomerName("测试客户" + n);
            order.setTargetGrammage(70);
            order.setTargetWidthMm(787);
            order.setTargetWeightKg(new BigDecimal("5000.00"));
            order.setDoneWeightKg(BigDecimal.ZERO);
            order.setStatus("切裁中");
            return orderRepository.save(order).getId();
        }

        RequisitionRequest req(String token, int lengthM, String weightKg, String operator) {
            return req(token, lengthM, new BigDecimal(weightKg), operator);
        }

        RequisitionRequest req(String token, int lengthM, BigDecimal weightKg, String operator) {
            RequisitionRequest r = new RequisitionRequest();
            r.setRequestToken(token + "-" + rollNo);
            r.setRollNo(rollNo);
            r.setLengthM(lengthM);
            r.setWeightKg(weightKg);
            r.setOperator(operator);
            return r;
        }
    }
}
