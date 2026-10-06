package com.paper.mill.controller;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paper.mill.common.ApiResponse;
import com.paper.mill.entity.CuttingRequisition;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.RequisitionAttempt;
import com.paper.mill.service.PaperRollService;
import com.paper.mill.service.RequisitionService;

/** 抄纸工段 · 纸卷接口 */
@RestController
@RequestMapping("/api/paper-rolls")
public class PaperRollController {

    private final PaperRollService rollService;
    private final RequisitionService requisitionService;

    public PaperRollController(PaperRollService rollService,
                               RequisitionService requisitionService) {
        this.rollService = rollService;
        this.requisitionService = requisitionService;
    }

    @GetMapping
    public ApiResponse<List<PaperRoll>> list(@RequestParam(required = false) Long machineId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String grade) {
        return ApiResponse.success(rollService.list(machineId, status, grade));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaperRoll> get(@PathVariable Long id) {
        return ApiResponse.success(rollService.get(id));
    }

    /** 抄造产出纸卷，targetGsm 为本批目标定量，用于自动判等 */
    @PostMapping
    public ApiResponse<PaperRoll> create(@RequestBody PaperRoll input,
                                         @RequestParam(required = false) Integer targetGsm) {
        return ApiResponse.success("纸卷已产出", rollService.create(input, targetGsm));
    }

    @PutMapping("/{id}")
    public ApiResponse<PaperRoll> update(@PathVariable Long id, @RequestBody PaperRoll input) {
        return ApiResponse.success("已更新", rollService.update(id, input));
    }

    /** 检验：待检 → 可用 / 报废 */
    @PostMapping("/{id}/inspect")
    public ApiResponse<PaperRoll> inspect(@PathVariable Long id,
                                          @RequestParam String target,
                                          @RequestParam(required = false) String grade) {
        return ApiResponse.success("检验完成", rollService.inspect(id, target, grade));
    }

    /** 状态流转：可用 → 已切完 / 报废 */
    @PostMapping("/{id}/transfer")
    public ApiResponse<PaperRoll> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", rollService.transfer(id, target));
    }

    /** 切纸取用：扣减母卷余长与余重 */
    @PostMapping("/{id}/consume")
    public ApiResponse<PaperRoll> consume(@PathVariable Long id,
                                          @RequestParam Integer lengthM,
                                          @RequestParam(required = false) BigDecimal weightKg) {
        return ApiResponse.success("取用已登记", rollService.consume(id, lengthM, weightKg));
    }

    // ---------------- 切裁领料追溯（母卷导轨侧） ----------------

    /** 本卷被领走过的料：每张切纸订单从本卷领走的长度/重量/操作人/时间 */
    @GetMapping("/{id}/requisitions")
    public ApiResponse<List<CuttingRequisition>> listRequisitions(@PathVariable Long id) {
        // 先确认母卷存在，不存在沿用 get() 的业务提示
        rollService.get(id);
        return ApiResponse.success(requisitionService.listByRoll(id));
    }

    /** 本卷最近的领料留痕（含失败原因，刷新/重开页面仍可见） */
    @GetMapping("/{id}/requisition-attempts")
    public ApiResponse<List<RequisitionAttempt>> listAttempts(@PathVariable Long id) {
        rollService.get(id);
        return ApiResponse.success(requisitionService.attemptsByRoll(id));
    }
}
