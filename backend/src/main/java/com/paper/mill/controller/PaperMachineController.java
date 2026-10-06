package com.paper.mill.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.paper.mill.common.ApiResponse;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.service.PaperMachineService;

/** 抄纸工段 · 纸机接口（本仓后端路线：@LazyCollection(EXTRA) 超懒集合） */
@RestController
@RequestMapping("/api/paper-machines")
public class PaperMachineController {

    private final PaperMachineService machineService;

    public PaperMachineController(PaperMachineService machineService) {
        this.machineService = machineService;
    }

    @GetMapping
    public ApiResponse<List<PaperMachine>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(machineService.list(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaperMachine> get(@PathVariable Long id) {
        return ApiResponse.success(machineService.get(id));
    }

    /**
     * 纸机概览：纸卷总数走 count 查询（EXTRA），不装载上千条纸卷。
     */
    @GetMapping("/{id}/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable Long id) {
        return ApiResponse.success(machineService.summary(id));
    }

    /** 判断某卷是否属于这台纸机（EXTRA 下走 exists 查询） */
    @GetMapping("/{id}/owns-roll")
    public ApiResponse<Boolean> ownsRoll(@PathVariable Long id, @RequestParam String rollNo) {
        return ApiResponse.success(machineService.ownsRoll(id, rollNo));
    }

    @PostMapping
    public ApiResponse<PaperMachine> create(@RequestBody PaperMachine input) {
        return ApiResponse.success("纸机已建档", machineService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<PaperMachine> update(@PathVariable Long id, @RequestBody PaperMachine input) {
        return ApiResponse.success("已更新", machineService.update(id, input));
    }

    @PostMapping("/{id}/transfer")
    public ApiResponse<PaperMachine> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", machineService.transfer(id, target));
    }
}
