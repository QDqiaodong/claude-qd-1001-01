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
import com.paper.mill.dto.MachineActionRequest;
import com.paper.mill.entity.MachineMaintenance;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.service.PaperMachineService;

/** 抄纸工段 · 纸机接口：状态动作、检修单与母卷产出准入共用同一导轨视图。 */
@RestController
@RequestMapping("/api/paper-machines")
public class PaperMachineController {

    private final PaperMachineService machineService;

    public PaperMachineController(PaperMachineService machineService) {
        this.machineService = machineService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(machineService.guide(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaperMachine> get(@PathVariable Long id) {
        return ApiResponse.success(machineService.get(id));
    }

    /** 纸机导轨：可执行动作 + 当前检修单 + 历史记录 + 禁止产出原因。 */
    @GetMapping("/{id}/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable Long id) {
        return ApiResponse.success(machineService.summary(id));
    }

    /** 判断某卷是否属于这台纸机（EXTRA 下走 exists 查询） */
    @GetMapping("/{id}/owns-roll")
    public ApiResponse<Boolean> ownsRoll(@PathVariable Long id, @RequestParam String rollNo) {
        return ApiResponse.success(machineService.ownsRoll(id, rollNo));
    }

    @GetMapping("/{id}/maintenances")
    public ApiResponse<List<MachineMaintenance>> listMaintenances(@PathVariable Long id) {
        return ApiResponse.success(machineService.listMaintenance(id));
    }

    @GetMapping("/{id}/production-attempts")
    public ApiResponse<List<ProductionAttempt>> listProductionBlocks(@PathVariable Long id) {
        return ApiResponse.success(machineService.listProductionBlocks(id));
    }

    @PostMapping
    public ApiResponse<PaperMachine> create(@RequestBody PaperMachine input) {
        return ApiResponse.success("纸机已建档", machineService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<PaperMachine> update(@PathVariable Long id, @RequestBody PaperMachine input) {
        return ApiResponse.success("已更新", machineService.update(id, input));
    }

    /** 状态动作：停机 / 运行 / 开始检修 / 完成检修 / 恢复运行。 */
    @PostMapping("/{id}/actions")
    public ApiResponse<PaperMachine> action(@PathVariable Long id,
                                            @Valid @RequestBody MachineActionRequest req) {
        PaperMachine machine = machineService.performAction(id, req);
        return ApiResponse.success("状态已更新", machine);
    }
}
