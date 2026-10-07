package com.paper.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.ProductionAttempt;

public interface ProductionAttemptRepository extends JpaRepository<ProductionAttempt, Long> {

    List<ProductionAttempt> findByMachineIdOrderByAttemptedAtDescIdDesc(Long machineId);

    List<ProductionAttempt> findByMachineIdAndActionTypeOrderByAttemptedAtDescIdDesc(
            Long machineId, String actionType);
}
