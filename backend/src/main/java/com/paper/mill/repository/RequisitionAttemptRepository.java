package com.paper.mill.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.paper.mill.entity.RequisitionAttempt;

public interface RequisitionAttemptRepository extends JpaRepository<RequisitionAttempt, Long> {

    /** 幂等：同一请求令牌重复到达时取回上一次结果 */
    Optional<RequisitionAttempt> findByRequestToken(String requestToken);

    /** 订单详情里看最近的领料失败原因（成功/失败都在留痕表，失败原因只在失败行上） */
    List<RequisitionAttempt> findByOrderIdOrderByAttemptedAtDesc(Long orderId);

    /** 母卷导轨上看最近的领料失败原因 */
    List<RequisitionAttempt> findByRollIdOrderByAttemptedAtDesc(Long rollId);
}
