package com.paper.mill.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.CuttingRequisition;

public interface CuttingRequisitionRepository extends JpaRepository<CuttingRequisition, Long> {

    /** 订单详情追溯：按操作时间倒序看本订单的每一次领料 */
    List<CuttingRequisition> findByOrderIdOrderByOperatedAtDesc(Long orderId);

    /** 母卷导轨追溯：按操作时间倒序看本卷被领走过哪些料 */
    List<CuttingRequisition> findByRollIdOrderByOperatedAtDesc(Long rollId);
}
