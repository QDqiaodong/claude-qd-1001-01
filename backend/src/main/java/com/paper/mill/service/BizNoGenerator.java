package com.paper.mill.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paper.mill.entity.BizNoSeq;
import com.paper.mill.repository.BizNoSeqRepository;

/**
 * 业务单号发号器。
 *
 * <p>必须在调用方事务内调用：对 {@code biz_no_seq} 目标行加写锁后取号并自增，
 * 两名操作员并发领料时各自拿到不同号码，不会撞唯一约束，也不依赖
 * 「先插空号、拿到自增主键再回填」（IDENTITY 主键下那条路在首次 INSERT 时就会触发非空约束）。</p>
 */
@Service
public class BizNoGenerator {

    public static final String REQUISITION = "REQUISITION";

    private final BizNoSeqRepository seqRepository;

    public BizNoGenerator(BizNoSeqRepository seqRepository) {
        this.seqRepository = seqRepository;
    }

    @Transactional
    public String nextRequisitionNo() {
        long n = next(REQUISITION);
        return String.format("LL-%06d", n);
    }

    private long next(String name) {
        // 正常路径：schema.sql 已预置发号行，直接行锁取号
        BizNoSeq seq = seqRepository.findByNameForUpdate(name).orElse(null);
        if (seq == null) {
            // 全新库（如集成测试由 Hibernate 建表）首次发号：补建初始行，再锁取。
            // 极端并发下只有一个插入能成功，撞唯一约束的一方重走正常路径。
            try {
                BizNoSeq init = new BizNoSeq();
                init.setSeqName(name);
                init.setNextVal(2L);
                seqRepository.saveAndFlush(init);
                return 1L;
            } catch (RuntimeException dup) {
                seq = seqRepository.findByNameForUpdate(name)
                        .orElseThrow(() -> new IllegalStateException("发号器初始化失败：" + name));
            }
        }
        long current = seq.getNextVal();
        seq.setNextVal(current + 1);
        seqRepository.save(seq);
        return current;
    }
}
