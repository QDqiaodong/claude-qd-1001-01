package com.paper.mill.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.dto.RequisitionRequest;
import com.paper.mill.entity.CuttingRequisition;
import com.paper.mill.entity.RequisitionAttempt;
import com.paper.mill.repository.CuttingRequisitionRepository;
import com.paper.mill.repository.RequisitionAttemptRepository;
import com.paper.mill.service.RequisitionTxService.RequisitionFailedException;
import com.paper.mill.service.RequisitionTxService.RequisitionOutcome;

/**
 * 切纸工段 · 切裁领料闭环门面。
 *
 * <p>对外负责三件事：</p>
 * <ol>
 *   <li><b>幂等</b>：按 {@code requestToken} 判重。车间重复点击、网络重试让同一个请求重复到达时，
 *       不再执行扣减，直接返回该令牌上一次的原结果（成功回同一条领料记录、失败回同一个原因）；</li>
 *   <li><b>一致性闭环</b>：实际执行委托给 {@link RequisitionTxService}，记录写入与余量扣减
 *       在同一事务内，要么都成功，要么都不发生（失败原因除外，失败原因必须留痕）；</li>
 *   <li><b>双向追溯</b>：订单详情与母卷导轨都能查到领料账本和最近的失败留痕。</li>
 * </ol>
 */
@Service
public class RequisitionService {

    private final RequisitionTxService txService;
    private final CuttingRequisitionRepository requisitionRepository;
    private final RequisitionAttemptRepository attemptRepository;

    public RequisitionService(RequisitionTxService txService,
                              CuttingRequisitionRepository requisitionRepository,
                              RequisitionAttemptRepository attemptRepository) {
        this.txService = txService;
        this.requisitionRepository = requisitionRepository;
        this.attemptRepository = attemptRepository;
    }

    /**
     * 登记一次切裁领料。
     *
     * @return 成功结果（replayed=true 表示这是重复请求，返回的是原结果，没有再次扣减）
     */
    public SubmitResult submit(Long orderId, RequisitionRequest req) {
        String token = req.getRequestToken();

        // 幂等第一道：同令牌先找上一次结果
        RequisitionAttempt existing = txService.replay(token);
        if (existing != null) {
            ensureSameOrder(existing, orderId);
            // 同一个请求重复到达只能返回原结果：
            // 上次失败 → 回同样的失败原因（HTTP 400），不重试、不扣量、不补写记录
            if (RequisitionAttempt.FAILED.equals(existing.getResult())) {
                throw new BizException(existing.getFailReason());
            }
            return SubmitResult.replayed(existing);
        }

        try {
            RequisitionOutcome outcome = txService.execute(orderId, req);
            return SubmitResult.fresh(outcome.requisition(), outcome.remainLengthM(),
                    outcome.remainWeightKg(), outcome.rollStatus());
        } catch (RequisitionFailedException e) {
            // 失败原因已随留痕行提交；这里转为带明确原因的业务异常（HTTP 400）
            throw new BizException(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // 幂等第二道：两个相同令牌并发到达，唯一索引只放行一个，另一个重放原结果
            RequisitionAttempt raced = txService.replay(token);
            if (raced != null) {
                ensureSameOrder(raced, orderId);
                if (RequisitionAttempt.FAILED.equals(raced.getResult())) {
                    throw new BizException(raced.getFailReason());
                }
                return SubmitResult.replayed(raced);
            }
            throw e;
        }
    }

    /** 订单详情追溯：领料账本（只含成功记录） */
    @Transactional(readOnly = true)
    public List<CuttingRequisition> listByOrder(Long orderId) {
        return requisitionRepository.findByOrderIdOrderByOperatedAtDesc(orderId);
    }

    /** 母卷导轨追溯：本卷被领走过的料 */
    @Transactional(readOnly = true)
    public List<CuttingRequisition> listByRoll(Long rollId) {
        return requisitionRepository.findByRollIdOrderByOperatedAtDesc(rollId);
    }

    /** 订单详情里最近的领料留痕（成功与失败都在，用于重开页面仍看到失败原因） */
    @Transactional(readOnly = true)
    public List<RequisitionAttempt> attemptsByOrder(Long orderId) {
        return attemptRepository.findByOrderIdOrderByAttemptedAtDesc(orderId);
    }

    /** 母卷导轨上最近的领料留痕 */
    @Transactional(readOnly = true)
    public List<RequisitionAttempt> attemptsByRoll(Long rollId) {
        return attemptRepository.findByRollIdOrderByAttemptedAtDesc(rollId);
    }

    /**
     * 把一次成功结果（新执行或重放）组装成接口返回体：
     * 领料记录 + 扣减后的新余量/母卷状态 + 是否重放。
     */
    public static Map<String, Object> bodyOf(SubmitResult result) {
        CuttingRequisition ledger = result.requisition();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("replayed", result.replayed());
        data.put("requisition", ledger);
        data.put("remainLengthM", result.remainLengthM());
        data.put("remainWeightKg", result.remainWeightKg());
        data.put("rollStatus", result.rollStatus());
        return data;
    }

    private void ensureSameOrder(RequisitionAttempt existing, Long orderId) {
        if (existing.getOrder() != null && !existing.getOrder().getId().equals(orderId)) {
            throw new BizException("该请求令牌已用于其他订单（" + existing.getOrderNo()
                    + "），一次点击的令牌不能挪用到别的订单");
        }
    }

    /** 提交结果。fresh=本次真实执行，replayed=重复请求返回原结果。 */
    public static class SubmitResult {
        private final boolean replayed;
        private final CuttingRequisition requisition;
        private final int remainLengthM;
        private final BigDecimal remainWeightKg;
        private final String rollStatus;

        private SubmitResult(boolean replayed, CuttingRequisition requisition,
                             int remainLengthM, BigDecimal remainWeightKg, String rollStatus) {
            this.replayed = replayed;
            this.requisition = requisition;
            this.remainLengthM = remainLengthM;
            this.remainWeightKg = remainWeightKg;
            this.rollStatus = rollStatus;
        }

        static SubmitResult fresh(CuttingRequisition r, int remainLengthM,
                                  BigDecimal remainWeightKg, String rollStatus) {
            return new SubmitResult(false, r, remainLengthM, remainWeightKg, rollStatus);
        }

        static SubmitResult replayed(RequisitionAttempt attempt) {
            // 调用方已保证这里只处理成功留痕；失败重放抛原失败原因，走 400 路径
            CuttingRequisition r = attempt.getRequisition();
            // 重放返回定格在账本上的扣减后余量——即那次动作完成后的新余量
            return new SubmitResult(true, r, r.getRemainLengthM(), r.getRemainWeightKg(),
                    r.getRoll() == null ? null : r.getRoll().getStatus());
        }

        public boolean replayed() {
            return replayed;
        }

        public CuttingRequisition requisition() {
            return requisition;
        }

        public int remainLengthM() {
            return remainLengthM;
        }

        public BigDecimal remainWeightKg() {
            return remainWeightKg;
        }

        public String rollStatus() {
            return rollStatus;
        }
    }
}
