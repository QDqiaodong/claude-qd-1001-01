package com.paper.mill.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.paper.mill.common.BizException;
import com.paper.mill.dto.MarkUsableRequest;
import com.paper.mill.dto.ProductionRequest;
import com.paper.mill.entity.PaperRoll;
import com.paper.mill.entity.ProductionAttempt;
import com.paper.mill.service.ProductionTxService.ProductionBlockedException;

/**
 * 母卷产出准入门面：负责 requestToken 幂等重放，实际校验和写留痕由独立事务执行。
 */
@Service
public class ProductionAccessService {

    private final ProductionTxService txService;

    public ProductionAccessService(ProductionTxService txService) {
        this.txService = txService;
    }

    public PaperRoll createRoll(ProductionRequest req) {
        ProductionAttempt existing = txService.replay(req.getRequestToken());
        if (existing != null) {
            if (!ProductionAttempt.CREATE_ROLL.equals(existing.getActionType())) {
                throw new BizException("该请求令牌已用于其他产出动作，不能换动作重放");
            }
            throw new BizException(existing.getFailReason());
        }
        try {
            return txService.createRoll(req);
        } catch (ProductionBlockedException e) {
            throw new BizException(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            ProductionAttempt raced = txService.replay(req.getRequestToken());
            if (raced != null) {
                throw new BizException(raced.getFailReason());
            }
            throw e;
        }
    }

    public PaperRoll markUsable(Long rollId, MarkUsableRequest req) {
        ProductionAttempt existing = txService.replay(req.getRequestToken());
        if (existing != null) {
            if (!ProductionAttempt.MARK_USABLE.equals(existing.getActionType())) {
                throw new BizException("该请求令牌已用于其他产出动作，不能换动作重放");
            }
            if (existing.getRoll() != null && !existing.getRoll().getId().equals(rollId)) {
                throw new BizException("该请求令牌已用于其他母卷，不能跨母卷重放");
            }
            throw new BizException(existing.getFailReason());
        }
        try {
            return txService.markUsable(rollId, req);
        } catch (ProductionBlockedException e) {
            throw new BizException(e.getMessage());
        } catch (DataIntegrityViolationException e) {
            ProductionAttempt raced = txService.replay(req.getRequestToken());
            if (raced != null) {
                throw new BizException(raced.getFailReason());
            }
            throw e;
        }
    }
}
