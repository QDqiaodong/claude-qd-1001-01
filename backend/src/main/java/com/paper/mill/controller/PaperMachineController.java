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
import com.paper.mill.dto.MaintenanceOperatorRequest;
import com.paper.mill.dto.MaintenanceStartRequest;
import com.paper.mill.entity.MachineMaintenance;
import com.paper.mill.entity.PaperMachine;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.dto.PaperRollProduceRequest;
import com.paper.mill.service.MaintenanceService;
import com.paper.mill.service.PaperMachineService;
import com.paper.mill.service.PaperRollService;

import jakarta.validation.Valid;

/** 抄纸工段 · 纸机接口（检修状态与母卷产出准入共用同一条规则链） */
@RestController
@RequestMapping("/api/paper-machines")
public class PaperMachineController {

    private final PaperMachineService machineService;
    private final MaintenanceService maintenanceService;
    private final PaperRollService rollService;

    public PaperMachineController(PaperMachineService machineService,
                                  MaintenanceService maintenanceService,
                                  PaperRollService rollService) {
        this.machineService = machineService;
        this.maintenanceService = maintenanceService;
        this.rollService = rollService;
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
     * 纸机概览：纸卷总数走 count 查询（EXTRA），并带出当前检修单与待复机检修单。
     */
    @GetMapping("/{id}/summary")
    public ApiResponse<Map<String, Object>> summary(@PathVariable Long id) {
        return ApiResponse.success(machineService.summary(id));
    }

    /** 导轨联动数据：状态动作 + 当前检修单 + 产出准入 + 检修历史。 */
    @GetMapping("/{id}/rail")
    public ApiResponse<Map<String, Object>> rail(@PathVariable Long id) {
        return ApiResponse.success(machineService.rail(id));
    }

    /** 判断某卷是否属于这台纸机（EXTRA 下走 exists 查询） */
    @GetMapping("/{id}/owns-roll")
    public ApiResponse<Boolean> ownsRoll(@PathVariable Long id, @RequestParam String rollNo) {
        return ApiResponse.success(machineService.ownsRoll(id, rollNo));
    }

    @GetMapping("/{id}/maintenances")
    public ApiResponse<List<MachineMaintenance>> maintenances(@PathVariable Long id) {
        return ApiResponse.success(maintenanceService.history(id));
    }

    /** 在指定纸机上登记母卷产出；检修中拒绝且持久化阻止原因。 */
    @PostMapping("/{id}/rolls")
    public ApiResponse<PaperRoll> produceRoll(
            @PathVariable Long id, @Valid @RequestBody PaperRollProduceRequest req) {
        return ApiResponse.success("纸卷已产出，归属纸机 " + id, rollService.produce(id, req));
    }

    @PostMapping
    public ApiResponse<PaperMachine> create(@RequestBody PaperMachine input) {
        return ApiResponse.success("纸机已建档", machineService.create(input));
    }

    @PutMapping("/{id}")
    public ApiResponse<PaperMachine> update(@PathVariable Long id, @RequestBody PaperMachine input) {
        return ApiResponse.success("已更新", machineService.update(id, input));
    }

    /** 普通动作：只允许运行 ↔ 停机；检修进入/完成/复机必须走检修单动作。 */
    @PostMapping("/{id}/transfer")
    public ApiResponse<PaperMachine> transfer(@PathVariable Long id, @RequestParam String target) {
        return ApiResponse.success("状态已更新", machineService.transfer(id, target));
    }

    /** 运行/停机 → 检修：登记原因、开始时间、预计结束时间、经办人，并阻止未完成产出登记。 */
    @PostMapping("/{id}/maintenance/start")
    public ApiResponse<MachineMaintenance> startMaintenance(
            @PathVariable Long id, @Valid @RequestBody MaintenanceStartRequest req) {
        return ApiResponse.success("检修已发起，母卷产出准入已关闭",
                maintenanceService.start(id, req));
    }

    /** 检修 → 停机：检修完成后不能直接运行。 */
    @PostMapping("/{id}/maintenance/finish")
    public ApiResponse<MachineMaintenance> finishMaintenance(
            @PathVariable Long id, @Valid @RequestBody MaintenanceOperatorRequest req) {
        return ApiResponse.success("检修已完成，纸机已回到停机，等待经办人复机确认",
                maintenanceService.finish(id, req));
    }

    /** 停机 → 运行：只有检修单经办人确认后才能恢复。 */
    @PostMapping("/{id}/maintenance/resume")
    public ApiResponse<MachineMaintenance> resumeMaintenance(
            @PathVariable Long id, @Valid @RequestBody MaintenanceOperatorRequest req) {
        return ApiResponse.success("经办人已确认，纸机恢复运行，母卷产出准入已打开",
                maintenanceService.resume(id, req));
    }
}
