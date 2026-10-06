package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.paper.mill.entity.PaperRoll;

public interface PaperRollRepository extends JpaRepository<PaperRoll, Long> {

    Optional<PaperRoll> findByRollNo(String rollNo);

    List<PaperRoll> findByMachineId(Long machineId);

    List<PaperRoll> findByStatus(String status);

    List<PaperRoll> findByMachineIdAndStatus(Long machineId, String status);

    List<PaperRoll> findByGrade(String grade);

    /**
     * 按卷号查母卷并加写锁（SELECT ... FOR UPDATE）。
     *
     * <p>切裁领料同一业务动作内要校验余量并扣减，两名操作员同时领同一母卷时，
     * 后到的事务在此阻塞、拿到前一个事务扣减后的真实余量再校验，保证不会扣出负值。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PaperRoll r where r.rollNo = :rollNo")
    Optional<PaperRoll> findByRollNoForUpdate(@Param("rollNo") String rollNo);

    /** 挑可切母卷：可用状态 + 定量匹配 + 幅宽足够，按剩余长度倒序 */
    List<PaperRoll> findByStatusAndGrammageGsmAndWidthMmGreaterThanEqualOrderByLengthMDesc(
            String status, Integer grammageGsm, Integer widthMm);
}
