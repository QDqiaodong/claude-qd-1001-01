package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.MachineMaintenance;

public interface MachineMaintenanceRepository extends JpaRepository<MachineMaintenance, Long> {

    Optional<MachineMaintenance> findByMaintenanceNo(String maintenanceNo);

    List<MachineMaintenance> findByMachineIdOrderByStartedAtDesc(Long machineId);

    Optional<MachineMaintenance> findFirstByMachineIdAndStatusInOrderByStartedAtDesc(
            Long machineId, List<String> statuses);

    Optional<MachineMaintenance> findFirstByMachineIdOrderByStartedAtDesc(Long machineId);
}
