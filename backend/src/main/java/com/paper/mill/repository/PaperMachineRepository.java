package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.PaperMachine;

public interface PaperMachineRepository extends JpaRepository<PaperMachine, Long> {

    Optional<PaperMachine> findByMachineCode(String machineCode);

    List<PaperMachine> findByStatus(String status);
}
