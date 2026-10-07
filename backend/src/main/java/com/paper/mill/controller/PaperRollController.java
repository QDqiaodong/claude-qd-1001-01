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
import com.paper.mill.dto.PaperRollProduceRequest;
import com.paper.mill.dto.RollInspectionRequest;
import com.paper.mill.entity.CuttingRequisition;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.entity.RequisitionAttempt;
import com.paper.mill.service.PaperRollService;
import com.paper.mill.service.ProductionAttemptService;
import com.paper.mill.service.RequisitionService;

import jakarta.validation.Valid;

/** 抄纸工段 · 纸卷接口 */
@RestController
@RequestMapping("/api/paper-rolls")
public class PaperRollController {

    private final PaperRollService rollService;
    private final RequisitionService requisitionService;
    private final ProductionAttemptService productionAttemptService;

    public PaperRollController(PaperRollService rollService,
                               RequisitionService requisitionService,
                               ProductionAttemptService productionAttemptService) {
        this.rollService = rollService;
        this.requisitionService = requisitionService;
        this.productionAttemptService = productionAttemptService;
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

    @PutMapping("/{id}")
    public ApiResponse<PaperRoll> update(@PathVariable Long id, @RequestBody PaperRoll input) {
        return ApiResponse.success("已更新", rollService.update(id, input));
    }

    /** 检验：待检 → 可用 / 报废；检修中标记可用会被准入规则拒绝并留痕。 */
    @PostMapping("/{id}/inspect")
    public ApiResponse<PaperRoll> inspect(@PathVariable Long id,
                                          @Valid @RequestBody RollInspectionRequest req) {
        return ApiResponse.success("检验完成", rollService.inspect(id, req));
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

    // ---------------- 母卷产出准入（纸机导轨侧） ----------------

    /** 纸机上被检修准入阻止的新建母卷/标记可用留痕。 */
    @GetMapping("/by-machine/{machineId}/production-attempts")
    public ApiResponse<List<ProductionAttempt>> productionAttempts(@PathVariable Long machineId) {
        return ApiResponse.success(productionAttemptService.listByMachine(machineId));
    }
}
