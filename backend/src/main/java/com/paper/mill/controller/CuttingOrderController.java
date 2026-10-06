package com.paper.mill.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paper.mill.common.ApiResponse;
import com.paper.mill.dto.RequisitionRequest;
import com.paper.mill.entity.CuttingOrder;
import com.paper.mill.entity.CuttingRequisition;
import com.paper.mill.entity.RequisitionAttempt;
import com.paper.mill.service.CuttingOrderService;
import com.paper.mill.service.RequisitionService;

/** 切纸工段 · 切纸订单接口 */
@RestController
@RequestMapping("/api/cutting-orders")
public class CuttingOrderController {

    private final CuttingOrderService orderService;
    private final RequisitionService requisitionService;

    public CuttingOrderController(CuttingOrderService orderService,
                                  RequisitionService requisitionService) {
        this.orderService = orderService;
        this.requisitionService = requisitionService;
    }

    @GetMapping
    public ApiResponse<List<CuttingOrder>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String customerName) {
        return ApiResponse.success(orderService.list(status, customerName));
    }

    @GetMapping("/{id}")
    public ApiResponse<CuttingOrder> get(@PathVariable Long id) {
        return ApiResponse.success(orderService.get(id));
    }

    @PostMapping
    public ApiResponse<CuttingOrder> create(@RequestBody CuttingOrder input) {
        return ApiResponse.success("订单已创建", orderService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<CuttingOrder> update(@PathVariable Long id, @RequestBody CuttingOrder input) {
        return ApiResponse.success("已更新", orderService.update(id, input));
    }

    /** 校验母卷能否用于本订单（含切裁领料可领上限） */
    @GetMapping("/{id}/check-roll")
    public ApiResponse<Map<String, Object>> checkRoll(@PathVariable Long id, @RequestParam String rollNo) {
        return ApiResponse.success(orderService.checkRoll(id, rollNo));
    }

    /** 排产：指定母卷上机，订单推进到切裁中 */
    @PostMapping("/{id}/assign-roll")
    public ApiResponse<CuttingOrder> assignRoll(@PathVariable Long id, @RequestParam String rollNo) {
        return ApiResponse.success("已排产", orderService.assignRoll(id, rollNo));
    }

    /** 状态推进 */
    @PostMapping("/{id}/transfer")
    public ApiResponse<CuttingOrder> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", orderService.transfer(id, target));
    }

    // ---------------- 切裁领料闭环 ----------------

    /**
     * 登记一次切裁领料：同一事务内写领料记录并扣减母卷余量。
     *
     * <p>同 requestToken 重复到达（重复点击/重试）只返回原结果：成功回同一条记录、
     * 失败回同一个原因。成功时 message 区分「领料已登记」与「重复请求，返回原结果」。</p>
     */
    @PostMapping("/{id}/requisitions")
    public ApiResponse<Map<String, Object>> submitRequisition(@PathVariable Long id,
                                                              @Valid @RequestBody RequisitionRequest req) {
        RequisitionService.SubmitResult result = requisitionService.submit(id, req);
        String message = result.replayed()
                ? "重复请求，已返回上次领料结果（未重复扣减）"
                : "领料已登记，母卷余量已同步扣减";
        return ApiResponse.success(message, RequisitionService.bodyOf(result));
    }

    /** 订单详情追溯：本订单的全部成功领料记录 */
    @GetMapping("/{id}/requisitions")
    public ApiResponse<List<CuttingRequisition>> listRequisitions(@PathVariable Long id) {
        return ApiResponse.success(requisitionService.listByOrder(id));
    }

    /** 订单详情追溯：最近的领料留痕（含失败原因，刷新/重开页面仍可见） */
    @GetMapping("/{id}/requisition-attempts")
    public ApiResponse<List<RequisitionAttempt>> listAttempts(@PathVariable Long id) {
        return ApiResponse.success(requisitionService.attemptsByOrder(id));
    }
}
