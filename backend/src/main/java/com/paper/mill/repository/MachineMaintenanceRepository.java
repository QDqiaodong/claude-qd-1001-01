package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.MachineMaintenance;

public interface MachineMaintenanceRepository extends JpaRepository<MachineMaintenance, Long> {

    Optional<MachineMaintenance> findFirstByMachineIdAndRecordStatusOrderByIdDesc(
            Long machineId, String recordStatus);

    List<MachineMaintenance> findByMachineIdOrderByStartedAtDescIdDesc(Long machineId);

    List<MachineMaintenance> findByMachineIdAndRecordStatusOrderByIdDesc(Long machineId, String recordStatus);

    List<MachineMaintenance> findByRecordStatusOrderByIdDesc(String recordStatus);

    long countByMachineIdAndRecordStatus(Long machineId, String recordStatus);
}
