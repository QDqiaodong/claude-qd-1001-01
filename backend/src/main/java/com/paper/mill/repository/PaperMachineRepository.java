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

    /**
     * 按主键锁纸机行（SELECT ... FOR UPDATE）。
     * 检修准入、母卷产出和标记可用共用同一把行锁，两名操作员并发操作时
     * 后到事务必须读到先提交的真实纸机状态与检修单。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from PaperMachine m where m.id = :id")
    Optional<PaperMachine> findByIdForUpdate(@Param("id") Long id);
}
