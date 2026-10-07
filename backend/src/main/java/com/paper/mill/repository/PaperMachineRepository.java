package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.paper.mill.entity.PaperMachine;

public interface PaperMachineRepository extends JpaRepository<PaperMachine, Long> {

    Optional<PaperMachine> findByMachineCode(String machineCode);

    List<PaperMachine> findByStatus(String status);

    /** 纸机状态动作与产出准入共用这把行锁，保证并发请求看到同一状态裁决结果。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from PaperMachine m where m.id = :id")
    Optional<PaperMachine> findByIdForUpdate(@Param("id") Long id);
}
