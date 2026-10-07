package com.paper.mill.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.repository.ProductionAttemptRepository;

/**
 * 母卷产出准入阻止留痕。
 *
 * <p>检修准入拒绝必须独立事务落库：调用方随后会抛业务异常并回滚未完成的新建/标记动作，
 * 但被阻止原因不能被一起回滚。</p>
 */
@Service
public class ProductionAttemptService {

    private final ProductionAttemptRepository attemptRepository;

    public ProductionAttemptService(ProductionAttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordBlocked(Long machineId, Long rollId, String actionType,
                              String rollNo, String operator, String reason) {
        ProductionAttempt attempt = new ProductionAttempt();
        attempt.setMachineId(machineId);
        attempt.setRollId(rollId);
        attempt.setActionType(actionType);
        attempt.setRollNo(rollNo);
        attempt.setOperator(operator == null || operator.isBlank() ? null : operator.trim());
        attempt.setBlockReason(reason);
        attempt.setAttemptedAt(LocalDateTime.now());
        attemptRepository.save(attempt);
    }

    @Transactional(readOnly = true)
    public List<ProductionAttempt> listByMachine(Long machineId) {
        return attemptRepository.findByMachineIdOrderByAttemptedAtDescIdDesc(machineId);
    }
}
