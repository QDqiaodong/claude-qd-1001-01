package com.paper.mill.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 业务单号发号表：一行一类单号，{@code next_val} 在行锁内读取并自增，
 * 保证切裁领料单号（LL-xxxxxx）在并发提交下连续、唯一、不取数据库自增主键。
 */
@Entity
@Table(name = "biz_no_seq")
@Getter
@Setter
public class BizNoSeq {

    /** 单号类别，如 REQUISITION */
    @Id
    @Column(name = "seq_name", length = 32)
    private String seqName;

    @Column(name = "next_val", nullable = false)
    private Long nextVal;
}
