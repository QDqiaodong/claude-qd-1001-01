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
import com.paper.mill.entity.PulpChest;
import com.paper.mill.service.PulpChestService;

/** 制浆工段 · 浆池接口 */
@RestController
@RequestMapping("/api/pulp-chests")
public class PulpChestController {

    private final PulpChestService chestService;

    public PulpChestController(PulpChestService chestService) {
        this.chestService = chestService;
    }

    @GetMapping
    public ApiResponse<List<PulpChest>> list(@RequestParam(required = false) String pulpType,
                                             @RequestParam(required = false) String status) {
        return ApiResponse.success(chestService.list(pulpType, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<PulpChest> get(@PathVariable Long id) {
        return ApiResponse.success(chestService.get(id));
    }

    @PostMapping
    public ApiResponse<PulpChest> create(@RequestBody PulpChest input) {
        return ApiResponse.success("浆池已建档", chestService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<PulpChest> update(@PathVariable Long id, @RequestBody PulpChest input) {
        return ApiResponse.success("已更新", chestService.update(id, input));
    }

    /** 补浆 */
    @PostMapping("/{id}/fill")
    public ApiResponse<PulpChest> fill(@PathVariable Long id, @RequestParam BigDecimal amount) {
        return ApiResponse.success("补浆完成", chestService.fill(id, amount));
    }

    /** 抽浆 */
    @PostMapping("/{id}/draw")
    public ApiResponse<PulpChest> draw(@PathVariable Long id, @RequestParam BigDecimal amount) {
        return ApiResponse.success("抽浆完成", chestService.draw(id, amount));
    }

    /** 状态流转 */
    @PostMapping("/{id}/transfer")
    public ApiResponse<PulpChest> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", chestService.transfer(id, target));
    }
}
