package com.paper.mill.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paper.mill.common.ApiResponse;
import com.paper.mill.entity.FinishedBundle;
import com.paper.mill.service.FinishedBundleService;

/** 成品库 · 成品件与出入库接口 */
@RestController
@RequestMapping("/api/finished-bundles")
public class FinishedBundleController {

    private final FinishedBundleService bundleService;

    public FinishedBundleController(FinishedBundleService bundleService) {
        this.bundleService = bundleService;
    }

    @GetMapping
    public ApiResponse<List<FinishedBundle>> list(@RequestParam(required = false) Long orderId,
                                                  @RequestParam(required = false) String zone,
                                                  @RequestParam(required = false) String status) {
        return ApiResponse.success(bundleService.list(orderId, zone, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<FinishedBundle> get(@PathVariable Long id) {
        return ApiResponse.success(bundleService.get(id));
    }

    /** 成品件入库 */
    @PostMapping("/inbound")
    public ApiResponse<FinishedBundle> inbound(@RequestBody FinishedBundle input) {
        return ApiResponse.success("已入库", bundleService.inbound(input));
    }

    /** 成品件出库 */
    @PostMapping("/{id}/outbound")
    public ApiResponse<FinishedBundle> outbound(@PathVariable Long id) {
        return ApiResponse.success("已出库", bundleService.outbound(id));
    }

    /** 库区库存汇总 */
    @GetMapping("/stock-by-zone")
    public ApiResponse<List<Map<String, Object>>> stockByZone() {
        return ApiResponse.success(bundleService.stockByZone());
    }
}
