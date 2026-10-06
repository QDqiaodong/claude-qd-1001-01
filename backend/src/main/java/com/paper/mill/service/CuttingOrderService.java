package com.paper.mill.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.PaperRollRepository;

/**
 * 切纸工段 · 切纸订单业务。
 *
 * <p>核心规则：① 上机母卷幅宽必须 ≥ 订单目标幅宽；② 母卷定量与订单目标定量的偏差
 * 不得超过 {@link Dict#GRAMMAGE_TOLERANCE}；③ 母卷必须是「可用」状态；
 * ④ 状态只能逐级推进：待排产 → 切裁中 → 已完成。</p>
 */
@Service
public class CuttingOrderService {

    private final CuttingOrderRepository orderRepository;
    private final PaperRollRepository rollRepository;

    public CuttingOrderService(CuttingOrderRepository orderRepository,
                               PaperRollRepository rollRepository) {
        this.orderRepository = orderRepository;
        this.rollRepository = rollRepository;
    }

    public List<CuttingOrder> list(String status, String customerName) {
        if (status != null && !status.isBlank()) {
            return orderRepository.findByStatus(status);
        }
        if (customerName != null && !customerName.isBlank()) {
            return orderRepository.findByCustomerName(customerName);
        }
        return orderRepository.findAll();
    }

    public CuttingOrder get(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException("切纸订单不存在：" + id));
    }

    @Transactional
    public CuttingOrder create(CuttingOrder input) {
        if (input.getOrderNo() == null || input.getOrderNo().isBlank()) {
            throw new BizException("订单号不能为空");
        }
        orderRepository.findByOrderNo(input.getOrderNo().trim()).ifPresent(o -> {
            throw new BizException("订单号已存在：" + input.getOrderNo());
        });
        if (input.getCustomerName() == null || input.getCustomerName().isBlank()) {
            throw new BizException("客户名称不能为空");
        }
        if (input.getTargetGrammage() == null || input.getTargetGrammage() <= 0) {
            throw new BizException("目标定量必须大于 0");
        }
        if (input.getTargetWidthMm() == null || input.getTargetWidthMm() <= 0) {
            throw new BizException("目标幅宽必须大于 0");
        }
        if (input.getTargetWeightKg() == null || input.getTargetWeightKg().signum() <= 0) {
            throw new BizException("目标重量必须大于 0");
        }
        if (input.getDeadline() != null && input.getDeadline().isBefore(LocalDate.now())) {
            throw new BizException("交期不能早于今天");
        }
        CuttingOrder order = new CuttingOrder();
        order.setOrderNo(input.getOrderNo().trim());
        order.setCustomerName(input.getCustomerName());
        order.setTargetGrammage(input.getTargetGrammage());
        order.setTargetWidthMm(input.getTargetWidthMm());
        order.setTargetWeightKg(input.getTargetWeightKg());
        order.setDoneWeightKg(BigDecimal.ZERO);
        order.setStatus("待排产");
        order.setDeadline(input.getDeadline());
        return orderRepository.save(order);
    }

    @Transactional
    public CuttingOrder update(Long id, CuttingOrder input) {
        CuttingOrder order = get(id);
        if ("已取消".equals(order.getStatus()) || "已完成".equals(order.getStatus())) {
            throw new BizException("订单已处于「" + order.getStatus() + "」，不能再修改");
        }
        if (input.getCustomerName() != null && !input.getCustomerName().isBlank()) {
            order.setCustomerName(input.getCustomerName());
        }
        if (input.getTargetWidthMm() != null) {
            if (input.getTargetWidthMm() <= 0) {
                throw new BizException("目标幅宽必须大于 0");
            }
            order.setTargetWidthMm(input.getTargetWidthMm());
        }
        if (input.getTargetWeightKg() != null) {
            if (input.getTargetWeightKg().signum() <= 0) {
                throw new BizException("目标重量必须大于 0");
            }
            if (order.getDoneWeightKg() != null
                    && order.getDoneWeightKg().compareTo(input.getTargetWeightKg()) > 0) {
                throw new BizException("目标重量不能小于已入库的 " + order.getDoneWeightKg() + " 千克");
            }
            order.setTargetWeightKg(input.getTargetWeightKg());
        }
        if (input.getDeadline() != null) {
            order.setDeadline(input.getDeadline());
        }
        return orderRepository.save(order);
    }

    /**
     * 校验某母卷能否用于本订单（不落库）。
     *
     * @return 校验结论：可用余长、幅宽余量、定量偏差，以及切裁领料的可领上限
     *         （{code maxTakeLengthM} / {code maxTakeWeightKg}，操作员填领料量前先看清）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> checkRoll(Long id, String rollNo) {
        CuttingOrder order = get(id);
        PaperRoll roll = rollRepository.findByRollNo(rollNo)
                .orElseThrow(() -> new BizException("纸卷不存在：" + rollNo));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rollNo", roll.getRollNo());
        out.put("orderNo", order.getOrderNo());
        out.put("widthGapMm", roll.getWidthMm() - order.getTargetWidthMm());
        out.put("grammageGap", Math.abs(roll.getGrammageGsm() - order.getTargetGrammage()));
        out.put("remainLengthM", roll.getLengthM());
        out.put("remainWeightKg", roll.getWeightKg());
        out.put("status", roll.getStatus());
        // 可领上限就是母卷当前真实余量；不可领（非「可用」/规格不符）时给 0，前端禁止提交
        boolean usable = Dict.isRollUsable(roll.getStatus())
                && roll.getWidthMm() >= order.getTargetWidthMm()
                && Math.abs(roll.getGrammageGsm() - order.getTargetGrammage()) <= Dict.GRAMMAGE_TOLERANCE;
        out.put("maxTakeLengthM", usable ? roll.getLengthM() : 0);
        out.put("maxTakeWeightKg", usable ? roll.getWeightKg() : BigDecimal.ZERO);

        List<String> problems = new java.util.ArrayList<>();
        // 注意：这里只判「母卷对该订单能不能用」（排产与领料共用），订单自身是否处于
        // 「切裁中」由领料接口在事务内强制校验、领料面板在前端门控，不能塞进 problems，
        // 否则待排产订单的排产候选清单会被全部打叉。
        if (!Dict.isRollUsable(roll.getStatus())) {
            problems.add("母卷状态为「" + roll.getStatus() + "」，不可上机");
        }
        if (roll.getWidthMm() < order.getTargetWidthMm()) {
            problems.add("母卷幅宽 " + roll.getWidthMm() + " 小于订单目标幅宽 " + order.getTargetWidthMm());
        }
        if (Math.abs(roll.getGrammageGsm() - order.getTargetGrammage()) > Dict.GRAMMAGE_TOLERANCE) {
            problems.add("母卷定量 " + roll.getGrammageGsm() + " 与订单目标 "
                    + order.getTargetGrammage() + " 偏差超过 " + Dict.GRAMMAGE_TOLERANCE);
        }
        out.put("problems", problems);
        out.put("ok", problems.isEmpty());
        return out;
    }

    /**
     * 排产：指定母卷上切纸机，并把订单推进到「切裁中」。
     */
    @Transactional
    public CuttingOrder assignRoll(Long id, String rollNo) {
        CuttingOrder order = get(id);
        if (!"待排产".equals(order.getStatus())) {
            throw new BizException("只有「待排产」的订单能排产，当前状态：" + order.getStatus());
        }
        PaperRoll roll = rollRepository.findByRollNo(rollNo)
                .orElseThrow(() -> new BizException("纸卷不存在：" + rollNo));
        if (!Dict.isRollUsable(roll.getStatus())) {
            throw new BizException("母卷状态为「" + roll.getStatus() + "」，不可上机");
        }
        if (roll.getWidthMm() < order.getTargetWidthMm()) {
            throw new BizException("母卷幅宽 " + roll.getWidthMm()
                    + " 小于订单目标幅宽 " + order.getTargetWidthMm());
        }
        if (Math.abs(roll.getGrammageGsm() - order.getTargetGrammage()) > Dict.GRAMMAGE_TOLERANCE) {
            throw new BizException("母卷定量 " + roll.getGrammageGsm() + " 与订单目标 "
                    + order.getTargetGrammage() + " 偏差超过 " + Dict.GRAMMAGE_TOLERANCE);
        }
        order.setStatus("切裁中");
        return orderRepository.save(order);
    }

    /** 状态推进：待排产 → 切裁中 → 已完成 */
    @Transactional
    public CuttingOrder transfer(Long id, String target) {
        CuttingOrder order = get(id);
        if (!Dict.isValidOrderStatus(target)) {
            throw new BizException("订单状态不合法：" + target);
        }
        if (!"已取消".equals(target) && !Dict.canTransferOrder(order.getStatus(), target)) {
            throw new BizException("订单只能逐级推进，不允许从「" + order.getStatus() + "」到「" + target + "」");
        }
        if ("已完成".equals(target) && order.getGapWeightKg().signum() > 0) {
            throw new BizException("还欠 " + order.getGapWeightKg() + " 千克未完成，不能置为已完成");
        }
        order.setStatus(target);
        return orderRepository.save(order);
    }
}
