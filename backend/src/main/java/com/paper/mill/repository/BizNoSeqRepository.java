package com.paper.mill.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.paper.mill.entity.BizNoSeq;

public interface BizNoSeqRepository extends JpaRepository<BizNoSeq, String> {

    /** 发号：行锁内取当前号，调用方负责在同一事务内把 nextVal 加一后存回。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BizNoSeq s where s.seqName = :name")
    Optional<BizNoSeq> findByNameForUpdate(@Param("name") String name);
}
