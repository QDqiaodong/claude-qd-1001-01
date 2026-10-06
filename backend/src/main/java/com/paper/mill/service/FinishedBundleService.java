package com.paper.mill.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.common.BizException;
import com.paper.mill.common.Dict;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.FinishedBundle;
import com.paper.mill.repository.CuttingOrderRepository;
import com.paper.mill.repository.FinishedBundleRepository;

/**
 * 成品库 · 成品件与出入库业务。
 *
 * <p>核心规则：① 入库件的定量/幅宽必须与订单目标一致（定量允许
 * {@link Dict#GRAMMAGE_TOLERANCE} 偏差）；② 入库重量不得超过订单欠量；
 * ③ 只有「已入库」的件能出库，出库单向不可回退。</p>
 */
@Service
public class FinishedBundleService {

    private final FinishedBundleRepository bundleRepository;
    private final CuttingOrderRepository orderRepository;

    public FinishedBundleService(FinishedBundleRepository bundleRepository,
                                 CuttingOrderRepository orderRepository) {
        this.bundleRepository = bundleRepository;
        this.orderRepository = orderRepository;
    }

    public List<FinishedBundle> list(Long orderId, String zone, String status) {
        if (orderId != null) {
            return bundleRepository.findByOrderId(orderId);
        }
        if (zone != null && !zone.isBlank() && status != null && !status.isBlank()) {
            return bundleRepository.findByWarehouseZoneAndStatus(zone, status);
        }
        if (zone != null && !zone.isBlank()) {
            return bundleRepository.findByWarehouseZone(zone);
        }
        if (status != null && !status.isBlank()) {
            return bundleRepository.findByStatus(status);
        }
        return bundleRepository.findAll();
    }

    public FinishedBundle get(Long id) {
        return bundleRepository.findById(id)
                .orElseThrow(() -> new BizException("成品件不存在：" + id));
    }

    /**
     * 成品件入库。
     */
    @Transactional
    public FinishedBundle inbound(FinishedBundle input) {
        if (input.getBundleNo() == null || input.getBundleNo().isBlank()) {
            throw new BizException("件号不能为空");
        }
        bundleRepository.findByBundleNo(input.getBundleNo().trim()).ifPresent(b -> {
            throw new BizException("件号已存在：" + input.getBundleNo());
        });
        if (input.getOrder() == null || input.getOrder().getId() == null) {
            throw new BizException("必须指定所属订单");
        }
        CuttingOrder order = orderRepository.findById(input.getOrder().getId())
                .orElseThrow(() -> new BizException("切纸订单不存在：" + input.getOrder().getId()));
        if ("已取消".equals(order.getStatus())) {
            throw new BizException("订单已取消，不能入库");
        }
        if (input.getGrammageGsm() == null || input.getGrammageGsm() <= 0) {
            throw new BizException("定量必须大于 0");
        }
        // 规则①：规格必须与订单目标一致
        if (Math.abs(input.getGrammageGsm() - order.getTargetGrammage()) > Dict.GRAMMAGE_TOLERANCE) {
            throw new BizException("成品件定量 " + input.getGrammageGsm() + " 与订单目标 "
                    + order.getTargetGrammage() + " 偏差超过 " + Dict.GRAMMAGE_TOLERANCE);
        }
        if (input.getWidthMm() == null || !input.getWidthMm().equals(order.getTargetWidthMm())) {
            throw new BizException("成品件幅宽必须与订单目标幅宽 " + order.getTargetWidthMm() + " 一致");
        }
        if (input.getSheetCount() == null || input.getSheetCount() <= 0) {
            throw new BizException("张数必须大于 0");
        }
        if (input.getWeightKg() == null || input.getWeightKg().signum() <= 0) {
            throw new BizException("重量必须大于 0");
        }
        // 规则②：不得超过订单欠量
        if (input.getWeightKg().compareTo(order.getGapWeightKg()) > 0) {
            throw new BizException("入库重量 " + input.getWeightKg() + " 超过订单欠量 "
                    + order.getGapWeightKg() + " 千克");
        }
        if (!Dict.isValidWarehouseZone(input.getWarehouseZone())) {
            throw new BizException("库区不合法：" + input.getWarehouseZone());
        }

        FinishedBundle bundle = new FinishedBundle();
        bundle.setBundleNo(input.getBundleNo().trim());
        bundle.setOrder(order);
        bundle.setGrammageGsm(input.getGrammageGsm());
        bundle.setWidthMm(input.getWidthMm());
        bundle.setSheetCount(input.getSheetCount());
        bundle.setWeightKg(input.getWeightKg());
        bundle.setWarehouseZone(input.getWarehouseZone());
        bundle.setStatus("已入库");
        bundle.setStoredAt(LocalDateTime.now());
        FinishedBundle saved = bundleRepository.save(bundle);

        // 回写订单已入库重量
        order.setDoneWeightKg(order.getDoneWeightKg().add(input.getWeightKg()));
        orderRepository.save(order);
        return saved;
    }

    /** 出库：已入库 → 已出库，单向 */
    @Transactional
    public FinishedBundle outbound(Long id) {
        FinishedBundle bundle = get(id);
        if (!"已入库".equals(bundle.getStatus())) {
            throw new BizException("只有「已入库」的成品件能出库，当前状态：" + bundle.getStatus());
        }
        bundle.setStatus("已出库");
        bundle.setOutboundAt(LocalDateTime.now());
        return bundleRepository.save(bundle);
    }

    /** 库区库存汇总 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> stockByZone() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (String zone : Dict.WAREHOUSE_ZONES) {
            List<FinishedBundle> inStore = bundleRepository.findByWarehouseZoneAndStatus(zone, "已入库");
            BigDecimal weight = BigDecimal.ZERO;
            int sheets = 0;
            for (FinishedBundle b : inStore) {
                weight = weight.add(b.getWeightKg());
                sheets += b.getSheetCount() == null ? 0 : b.getSheetCount();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("zone", zone);
            row.put("bundleCount", inStore.size());
            row.put("weightKg", weight);
            row.put("sheetCount", sheets);
            out.add(row);
        }
        return out;
    }
}
