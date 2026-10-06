package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.paper.mill.entity.CuttingOrder;

public interface CuttingOrderRepository extends JpaRepository<CuttingOrder, Long> {

    Optional<CuttingOrder> findByOrderNo(String orderNo);

    List<CuttingOrder> findByStatus(String status);

    List<CuttingOrder> findByCustomerName(String customerName);

    /**
     * 按主键查订单并加写锁。
     *
     * <p>领料事务先锁订单行再锁母卷行（全局固定顺序，避免两笔事务互锁死锁）：
     * 锁住后订单状态不会在校验与扣减之间被别的事务改走（例如刚好被取消）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from CuttingOrder o where o.id = :id")
    Optional<CuttingOrder> findByIdForUpdate(@Param("id") Long id);
}
