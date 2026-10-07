package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.ProductionAttempt;

public interface ProductionAttemptRepository extends JpaRepository<ProductionAttempt, Long> {

    Optional<ProductionAttempt> findByRequestToken(String requestToken);

    List<ProductionAttempt> findByMachineIdOrderByAttemptedAtDesc(Long machineId);
}
