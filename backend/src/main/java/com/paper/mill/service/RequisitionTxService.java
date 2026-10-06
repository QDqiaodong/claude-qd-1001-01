package com.paper.mill.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.Dict;
import com.paper.mill.dto.RequisitionRequest;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.CuttingRequisition;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.RequisitionAttempt;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.CuttingRequisitionRepository;
import com.paper.mill.repository.PaperRollRepository;
import com.paper.mill.repository.RequisitionAttemptRepository;

/**
 * 切裁领料 · 单事务闭环执行器。
 *
 * <p>一次领料在<strong>同一个数据库事务</strong>内完成：行锁 → 全量校验 → 写领料记录 →
 * 扣减母卷余量 →（余长低于阈值）母卷转「已切完」→ 写成功/失败留痕。任何一步业务条件不满足，
 * 都不写领料记录、不改变订单与母卷任何数值；但失败原因随留痕行一起提交，刷新页面仍可追溯。</p>
 *
 * <p>并发：固定按「先订单行锁、后母卷行锁」的顺序加锁（{@code SELECT ... FOR UPDATE}），
 * 两名操作员同时领同一母卷时，后到事务读到的一定是前者扣减后的真实余量，
 * 只有余量实际可满足的一方成功。</p>
 *
 * <p>注意：本类的 {@link #execute} 要求调用方（{@link RequisitionService}）先确认
 * {@code requestToken} 从未出现过；同令牌重放走的是 {@link #replay}，不进本方法。</p>
 */
@Service
public class RequisitionTxService {

    private final CuttingOrderRepository orderRepository;
    private final PaperRollRepository rollRepository;
    private final CuttingRequisitionRepository requisitionRepository;
    private final RequisitionAttemptRepository attemptRepository;
    private final BizNoGenerator bizNoGenerator;

    public RequisitionTxService(CuttingOrderRepository orderRepository,
                                PaperRollRepository rollRepository,
                                CuttingRequisitionRepository requisitionRepository,
                                RequisitionAttemptRepository attemptRepository,
                                BizNoGenerator bizNoGenerator) {
        this.orderRepository = orderRepository;
        this.rollRepository = rollRepository;
        this.requisitionRepository = requisitionRepository;
        this.attemptRepository = attemptRepository;
        this.bizNoGenerator = bizNoGenerator;
    }

    /**
     * 在一个事务内执行一次领料；成功返回含新领料记录的结果，失败抛
     * {@link RequisitionFailedException}，失败原因已随留痕行提交。
     *
     * <p>独立事务（REQUIRES_NEW）：失败留痕必须落库，不能被外层的异常回滚带走。
     * {@code noRollbackFor}：业务条件失效属于「预期内的失败」，要让失败留痕与原因
     * 正常提交；真正的系统异常仍照常回滚，绝不留下半成品数据。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = RequisitionFailedException.class)
    public RequisitionOutcome execute(Long orderId, RequisitionRequest req) {
        LocalDateTime now = LocalDateTime.now();
        List<String> reasons = new ArrayList<>();

        // ① 先锁订单行（固定锁顺序的第一把锁）
        CuttingOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null) {
            reasons.add("切纸订单不存在：" + orderId);
            persistFailure(orderId, null, req, reasons, now);
            throw new RequisitionFailedException(join(reasons));
        }
        if (!"切裁中".equals(order.getStatus())) {
            reasons.add("订单当前状态为「" + order.getStatus() + "」，只有「切裁中」的订单能登记领料");
        }

        // ② 再锁母卷行（固定锁顺序的第二把锁）
        PaperRoll roll = rollRepository.findByRollNoForUpdate(req.getRollNo().trim()).orElse(null);
        if (roll == null) {
            reasons.add("母卷不存在：" + req.getRollNo());
            persistFailure(orderId, null, req, reasons, now);
            throw new RequisitionFailedException(join(reasons));
        }

        // ③ 母卷状态
        if (!Dict.isRollUsable(roll.getStatus())) {
            reasons.add("母卷状态为「" + roll.getStatus() + "」，不可领料（只有「可用」母卷可领）");
        }

        // ④ 规格仍匹配：幅宽 >= 目标幅宽、定量偏差 <= GRAMMAGE_TOLERANCE
        if (roll.getWidthMm() < order.getTargetWidthMm()) {
            reasons.add("母卷幅宽 " + roll.getWidthMm() + "mm 小于订单目标幅宽 "
                    + order.getTargetWidthMm() + "mm，规格不再匹配");
        }
        if (Math.abs(roll.getGrammageGsm() - order.getTargetGrammage()) > Dict.GRAMMAGE_TOLERANCE) {
            reasons.add("母卷定量 " + roll.getGrammageGsm() + "g 与订单目标 "
                    + order.getTargetGrammage() + "g 偏差超过 " + Dict.GRAMMAGE_TOLERANCE
                    + "，规格不再匹配");
        }

        // ⑤ 长度/重量为正（注解已拦，这里在锁内再兜一层，防止绕过 Web 层的调用）
        if (req.getLengthM() == null || req.getLengthM() <= 0) {
            reasons.add("本次领料长度必须大于 0");
        }
        if (req.getWeightKg() == null || req.getWeightKg().signum() <= 0) {
            reasons.add("本次领料重量必须大于 0");
        } else if (req.getWeightKg().stripTrailingZeros().scale() > 2) {
            reasons.add("本次领料重量最多两位小数");
        }

        // ⑥ 不超过母卷余量（在锁内读到的就是当前真实余量）
        if (req.getLengthM() != null && req.getLengthM() > 0
                && req.getLengthM() > roll.getLengthM()) {
            reasons.add("本次领料长度 " + req.getLengthM() + " 米超过母卷剩余长度 "
                    + roll.getLengthM() + " 米");
        }
        if (req.getWeightKg() != null && req.getWeightKg().signum() > 0
                && req.getWeightKg().compareTo(roll.getWeightKg()) > 0) {
            reasons.add("本次领料重量 " + req.getWeightKg() + " 千克超过母卷剩余重量 "
                    + roll.getWeightKg() + " 千克");
        }

        // ---- 任何条件失效：只留痕，不写账本、不动余量 ----
        if (!reasons.isEmpty()) {
            persistFailure(orderId, roll.getId(), req, reasons, now);
            throw new RequisitionFailedException(join(reasons));
        }

        // ---- 全部通过：账本与余量在同一事务内一致落库 ----
        int remainLength = roll.getLengthM() - req.getLengthM();
        BigDecimal remainWeight = roll.getWeightKg().subtract(req.getWeightKg());

        roll.setLengthM(remainLength);
        roll.setWeightKg(remainWeight);
        // 余长低于现有阈值：整卷转为「已切完」（已成功的领料记录不因此消失）
        if (remainLength < Dict.MIN_REMAIN_LENGTH_M) {
            roll.setStatus("已切完");
        }
        rollRepository.save(roll);

        CuttingRequisition ledger = new CuttingRequisition();
        // 先在锁内发号，再一次 INSERT 写全（requisition_no 非空唯一，不能先插空号再回填）
        ledger.setRequisitionNo(bizNoGenerator.nextRequisitionNo());
        ledger.setOrder(order);
        ledger.setRoll(roll);
        ledger.setLengthM(req.getLengthM());
        ledger.setWeightKg(req.getWeightKg());
        ledger.setOperator(req.getOperator().trim());
        ledger.setOperatedAt(now);
        ledger.setRemainLengthM(remainLength);
        ledger.setRemainWeightKg(remainWeight);
        CuttingRequisition savedLedger = requisitionRepository.save(ledger);

        RequisitionAttempt attempt = baseAttempt(order, roll, req, now);
        attempt.setResult(RequisitionAttempt.SUCCESS);
        attempt.setRequisition(savedLedger);
        attemptRepository.save(attempt);

        return new RequisitionOutcome(true, join(reasons), savedLedger, null, remainLength,
                remainWeight, roll.getStatus());
    }

    /**
     * 同令牌重放：不重新执行、不扣余量，直接取上次结果。
     *
     * <p>独立只读事务，保证 EAGER 的订单/母卷/账本关联在会话内可解析。</p>
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RequisitionAttempt replay(String requestToken) {
        return attemptRepository.findByRequestToken(requestToken).orElse(null);
    }

    private void persistFailure(Long orderId, Long rollId, RequisitionRequest req,
                                List<String> reasons, LocalDateTime now) {
        RequisitionAttempt attempt = new RequisitionAttempt();
        attempt.setRequestToken(req.getRequestToken());
        attempt.setLengthM(req.getLengthM());
        attempt.setWeightKg(req.getWeightKg());
        attempt.setOperator(req.getOperator() == null ? null : req.getOperator().trim());
        attempt.setResult(RequisitionAttempt.FAILED);
        attempt.setFailReason(join(reasons));
        attempt.setAttemptedAt(now);
        if (orderId != null) {
            orderRepository.findById(orderId).ifPresent(attempt::setOrder);
        }
        if (rollId != null) {
            rollRepository.findById(rollId).ifPresent(attempt::setRoll);
        }
        attemptRepository.save(attempt);
    }

    private RequisitionAttempt baseAttempt(CuttingOrder order, PaperRoll roll,
                                           RequisitionRequest req, LocalDateTime now) {
        RequisitionAttempt attempt = new RequisitionAttempt();
        attempt.setRequestToken(req.getRequestToken());
        attempt.setOrder(order);
        attempt.setRoll(roll);
        attempt.setLengthM(req.getLengthM());
        attempt.setWeightKg(req.getWeightKg());
        attempt.setOperator(req.getOperator().trim());
        attempt.setAttemptedAt(now);
        return attempt;
    }

    private static String join(List<String> reasons) {
        return String.join("；", reasons);
    }

    /**
     * 事务内执行结果。失败时不返回本对象而是抛 {@link RequisitionFailedException}；
     * rollAfter 字段也用于把扣减后的新余量带回给发起方。
     */
    public record RequisitionOutcome(boolean success, String failReason,
                                     CuttingRequisition requisition,
                                     RequisitionAttempt attempt,
                                     int remainLengthM,
                                     BigDecimal remainWeightKg,
                                     String rollStatus) {
    }

    /** 业务条件失效：原因已随失败留痕提交，抛出仅为中断后续写入并把原因带回上层。 */
    public static class RequisitionFailedException extends RuntimeException {
        public RequisitionFailedException(String message) {
            super(message);
        }
    }
}
