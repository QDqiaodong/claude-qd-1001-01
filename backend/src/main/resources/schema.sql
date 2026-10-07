SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 造纸厂 · 制浆抄纸与纸卷切裁管理
-- 库名 paper_mill；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS paper_mill
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE paper_mill;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS finished_bundle;
DROP TABLE IF EXISTS requisition_attempt;
DROP TABLE IF EXISTS cutting_requisition;
DROP TABLE IF EXISTS cutting_order;
DROP TABLE IF EXISTS production_attempt;
DROP TABLE IF EXISTS paper_roll;
DROP TABLE IF EXISTS machine_maintenance;
DROP TABLE IF EXISTS paper_machine;
DROP TABLE IF EXISTS pulp_chest;
DROP TABLE IF EXISTS biz_no_seq;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 制浆工段 · 浆池台账 ----------------
CREATE TABLE pulp_chest (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    chest_code    VARCHAR(32)  NOT NULL UNIQUE,
    chest_name    VARCHAR(64)  NOT NULL,
    pulp_type     VARCHAR(32)  NOT NULL COMMENT '针叶浆/阔叶浆/废纸浆/竹浆',
    capacity_m3   DECIMAL(10,2) NOT NULL,
    current_m3    DECIMAL(10,2) NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL COMMENT '在用/检修/停用',
    remark        VARCHAR(128)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 抄纸工段 · 纸机台账 ----------------
CREATE TABLE paper_machine (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    machine_code    VARCHAR(32) NOT NULL UNIQUE,
    machine_name    VARCHAR(64) NOT NULL,
    design_width_mm INT         NOT NULL COMMENT '设计幅宽，纸卷幅宽不得超过此值',
    design_speed_mpm INT        NOT NULL COMMENT '设计车速 m/min',
    status          VARCHAR(16) NOT NULL COMMENT '运行/停机/检修',
    last_maintain_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 抄纸工段 · 纸机检修单（只增不改） ----------------
-- 运行/停机进入检修时必须记录原因、开始时间、预计结束时间和经办人。
-- 检修完成只能回停机；同一经办人确认后才恢复运行。检修历史不因纸机状态变化删除。
CREATE TABLE machine_maintenance (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    maintenance_no   VARCHAR(32) NOT NULL UNIQUE COMMENT '检修单号 WX-xxxxxx',
    machine_id       BIGINT      NOT NULL,
    reason           VARCHAR(256) NOT NULL COMMENT '检修原因',
    started_at       DATETIME    NOT NULL COMMENT '检修开始时间',
    expected_end_at  DATETIME    NOT NULL COMMENT '预计结束时间',
    finished_at      DATETIME    NULL COMMENT '实际完成时间，完成后纸机回停机',
    resumed_at       DATETIME    NULL COMMENT '经办人确认复机时间',
    operator         VARCHAR(32) NOT NULL COMMENT '发起检修的经办人',
    finish_operator  VARCHAR(32) NULL,
    resume_operator  VARCHAR(32) NULL,
    record_status    VARCHAR(16) NOT NULL COMMENT '检修中/待复机确认/已复机',
    created_at       DATETIME    NOT NULL,
    CONSTRAINT fk_maintenance_machine FOREIGN KEY (machine_id) REFERENCES paper_machine (id),
    INDEX idx_maintenance_machine_started (machine_id, started_at),
    INDEX idx_maintenance_status (record_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 抄纸工段 · 纸卷（母卷）----------------
CREATE TABLE paper_roll (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    roll_no      VARCHAR(32)  NOT NULL UNIQUE,
    machine_id   BIGINT       NOT NULL,
    grammage_gsm INT          NOT NULL COMMENT '定量 g/m2',
    width_mm     INT          NOT NULL,
    length_m     INT          NOT NULL COMMENT '剩余长度，切裁会从这里扣',
    weight_kg    DECIMAL(10,2) NOT NULL,
    grade        VARCHAR(16)  NOT NULL COMMENT '一等/二等/等外',
    status       VARCHAR(16)  NOT NULL COMMENT '待检/可用/已切完/报废',
    produced_at  DATETIME,
    CONSTRAINT fk_roll_machine FOREIGN KEY (machine_id) REFERENCES paper_machine (id),
    INDEX idx_roll_machine_status (machine_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 抄纸工段 · 检修期产出准入阻止留痕 ----------------
-- 检修中新建母卷、或将新母卷标成可用时，不产生母卷、不改状态，但阻止原因落库；
-- 刷新页面后纸机导轨仍能看到被谁、在何时、因为哪个检修单阻止。
CREATE TABLE production_attempt (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    machine_id    BIGINT       NOT NULL,
    roll_id       BIGINT       NULL,
    action_type   VARCHAR(16)  NOT NULL COMMENT '新建母卷/标记可用',
    roll_no       VARCHAR(32)  NULL,
    operator      VARCHAR(32)  NULL,
    block_reason  VARCHAR(512) NOT NULL,
    attempted_at  DATETIME     NOT NULL,
    INDEX idx_production_attempt_machine_time (machine_id, attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 切纸工段 · 切纸订单 ----------------
CREATE TABLE cutting_order (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no         VARCHAR(32)  NOT NULL UNIQUE,
    customer_name    VARCHAR(64)  NOT NULL,
    target_grammage  INT          NOT NULL COMMENT '目标定量',
    target_width_mm  INT          NOT NULL COMMENT '目标幅宽，必须 <= 母卷幅宽',
    target_weight_kg DECIMAL(10,2) NOT NULL,
    done_weight_kg   DECIMAL(10,2) NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL COMMENT '待排产/切裁中/已完成/已取消',
    deadline         DATE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 切纸工段 · 切裁领料记录（闭环账本，只增不改） ----------------
-- 每一条都是一次成功的切裁领料：订单 + 母卷 + 本次长度/重量 + 操作时间/操作人。
-- 记录一旦写入不随订单或母卷状态变化而消失，用于订单详情与母卷导轨双向追溯。
CREATE TABLE cutting_requisition (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    requisition_no VARCHAR(32) NOT NULL UNIQUE COMMENT '领料单号',
    order_id      BIGINT       NOT NULL,
    roll_id       BIGINT       NOT NULL,
    length_m      INT          NOT NULL COMMENT '本次领料长度（米）',
    weight_kg     DECIMAL(10,2) NOT NULL COMMENT '本次领料重量（千克）',
    operator      VARCHAR(32)  NOT NULL COMMENT '操作人',
    operated_at   DATETIME     NOT NULL COMMENT '操作时间',
    remain_length_m INT        NOT NULL COMMENT '扣减后母卷余长（米）',
    remain_weight_kg DECIMAL(10,2) NOT NULL COMMENT '扣减后母卷余重（千克）',
    CONSTRAINT fk_requisition_order FOREIGN KEY (order_id) REFERENCES cutting_order (id),
    CONSTRAINT fk_requisition_roll  FOREIGN KEY (roll_id)  REFERENCES paper_roll (id),
    INDEX idx_requisition_order (order_id),
    INDEX idx_requisition_roll (roll_id),
    INDEX idx_requisition_operated (operated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 切纸工段 · 领料请求留痕（幂等 + 失败原因持久化） ----------------
-- 每次领料请求一行：成功行回链 cutting_requisition，失败行记 fail_reason。
-- request_token 唯一：同一请求重复到达（重复点击/重试）只能拿到原结果；
-- 失败原因落在库里，刷新或重开页面仍能看到真实余量与失败原因。
CREATE TABLE requisition_attempt (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_token VARCHAR(64) NOT NULL UNIQUE COMMENT '请求幂等令牌（一次点击一个）',
    order_id      BIGINT       NULL COMMENT '失败时订单可能不存在/不可领，故允许为空',
    roll_id       BIGINT       NULL,
    requisition_id BIGINT      NULL COMMENT '成功时回链领料记录',
    length_m      INT          NULL COMMENT '本次请求长度',
    weight_kg     DECIMAL(10,2) NULL COMMENT '本次请求重量',
    operator      VARCHAR(32)  NULL,
    result        VARCHAR(8)   NOT NULL COMMENT 'SUCCESS / FAILED',
    fail_reason   VARCHAR(512) NULL COMMENT '失败明确原因（多条以；连接）',
    attempted_at  DATETIME     NOT NULL,
    CONSTRAINT fk_attempt_order FOREIGN KEY (order_id) REFERENCES cutting_order (id),
    CONSTRAINT fk_attempt_roll  FOREIGN KEY (roll_id)  REFERENCES paper_roll (id),
    CONSTRAINT fk_attempt_requisition FOREIGN KEY (requisition_id) REFERENCES cutting_requisition (id),
    INDEX idx_attempt_order (order_id),
    INDEX idx_attempt_roll (roll_id),
    INDEX idx_attempt_attempted (attempted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 业务单号发号表（行锁内取号，并发不撞号） ----------------
CREATE TABLE biz_no_seq (
    seq_name  VARCHAR(32) NOT NULL PRIMARY KEY COMMENT '单号类别：REQUISITION=切裁领料单，MAINTENANCE=检修单',
    next_val  BIGINT      NOT NULL COMMENT '下一个可用号码'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 成品库 · 成品件与出入库 ----------------
CREATE TABLE finished_bundle (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    bundle_no     VARCHAR(32)  NOT NULL UNIQUE,
    order_id      BIGINT       NOT NULL,
    grammage_gsm  INT          NOT NULL,
    width_mm      INT          NOT NULL,
    sheet_count   INT          NOT NULL,
    weight_kg     DECIMAL(10,2) NOT NULL,
    warehouse_zone VARCHAR(32) NOT NULL COMMENT '库区 A/B/C',
    status        VARCHAR(16)  NOT NULL COMMENT '已入库/已出库',
    stored_at     DATETIME,
    outbound_at   DATETIME     NULL,
    CONSTRAINT fk_bundle_order FOREIGN KEY (order_id) REFERENCES cutting_order (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO pulp_chest (chest_code, chest_name, pulp_type, capacity_m3, current_m3, status, remark) VALUES
('PC-01', '1号针叶浆池', '针叶浆', 120.00,  86.50, '在用', '长纤维，配抄高强度纸'),
('PC-02', '2号阔叶浆池', '阔叶浆', 100.00,  92.00, '在用', '接近满池，补浆极易触发容量校验'),
('PC-03', '3号废纸浆池', '废纸浆', 150.00,  40.00, '在用', '脱墨浆'),
('PC-04', '4号竹浆池',   '竹浆',    80.00,   0.00, '检修', '内壁衬胶更换中'),
('PC-05', '5号备用浆池', '针叶浆',  60.00,  12.00, '停用', '停用池不允许补浆');

INSERT INTO paper_machine (machine_code, machine_name, design_width_mm, design_speed_mpm, status, last_maintain_at) VALUES
('PM-01', '1号纸机', 3200, 650, '运行', UTC_TIMESTAMP() - INTERVAL 12 DAY),
('PM-02', '2号纸机', 2400, 480, '运行', UTC_TIMESTAMP() - INTERVAL 30 DAY),
('PM-03', '3号纸机', 1800, 320, '停机', UTC_TIMESTAMP() - INTERVAL 90 DAY),
('PM-04', '4号纸机', 2800, 560, '检修', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY);

-- 纸卷：PM-01 设计幅宽 3200，PM-02 设计幅宽 2400
INSERT INTO paper_roll (roll_no, machine_id, grammage_gsm, width_mm, length_m, weight_kg, grade, status, produced_at) VALUES
('R-2026001', 1, 70, 3150, 11000, 2425.50, '一等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
('R-2026002', 1, 70, 3150,  9500, 2094.75, '二等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
('R-2026003', 1, 80, 2400,  6000, 1152.00, '一等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('R-2026004', 2, 60, 2350,  4200,  591.60, '一等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
('R-2026005', 2, 60, 2350,     0,    0.00, '二等', '已切完', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('R-2026006', 2, 90, 1200,  1500,  162.00, '等外', '待检',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
('R-2026007', 1, 70, 3150,  8000, 1764.00, '一等', '可用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- 检修台账：历史检修已复机；PM-04 当前检修中。母卷归属和历史台账均不随状态变化消失。
INSERT INTO machine_maintenance
    (maintenance_no, machine_id, reason, started_at, expected_end_at, finished_at, resumed_at,
     operator, finish_operator, resume_operator, record_status, created_at) VALUES
('WX-000001', 1, '例行更换成形网并校准横幅定量',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 DAY + INTERVAL 6 HOUR,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 11 DAY + INTERVAL 7 HOUR,
 '王建国', '王建国', '王建国', '已复机',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 DAY),
('WX-000002', 2, '真空泵检修与润滑保养',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY + INTERVAL 4 HOUR,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 29 DAY + INTERVAL 5 HOUR,
 '李秀兰', '李秀兰', '李秀兰', '已复机',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
('WX-000003', 3, '传动轴承异响处理',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 89 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 89 DAY + INTERVAL 3 HOUR,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 89 DAY + INTERVAL 4 HOUR,
 '赵强', '赵强', '赵强', '已复机',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 90 DAY),
('WX-000004', 4, '烘缸表面检查及刮刀更换',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 1 DAY,
 NULL, NULL, '赵强', NULL, NULL, '检修中',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY);

-- PM-04 检修中试图新建母卷，被准入规则阻止；母卷未产生，原因刷新后仍在。
INSERT INTO production_attempt (machine_id, roll_id, action_type, roll_no, operator, block_reason, attempted_at) VALUES
(4, NULL, '新建母卷', 'R-BLOCK-0001', '赵强',
 '检修中不能新建纸卷；纸机 PM-04 当前为「检修」状态，新母卷不会获得准入，已有母卷归属和台账保持不变（本次新建母卷已阻止并留痕）',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR);

INSERT INTO cutting_order (order_no, customer_name, target_grammage, target_width_mm, target_weight_kg, done_weight_kg, status, deadline) VALUES
('CO-2026001', '华东彩印', 70,  787, 1800.00,  620.00, '切裁中', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 6 DAY),
('CO-2026002', '新华印务', 70, 1092, 2400.00, 2400.00, '已完成', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 3 DAY),
('CO-2026003', '时代包装', 60,  640,  900.00,    0.00, '待排产', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 10 DAY),
('CO-2026004', '联合书刊', 80,  850, 1500.00,  430.00, '切裁中', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 4 DAY),
('CO-2026005', '老客户A',  90, 1500,  500.00,    0.00, '待排产', UTC_TIMESTAMP() + INTERVAL 8 HOUR + INTERVAL 8 DAY);

INSERT INTO finished_bundle (bundle_no, order_id, grammage_gsm, width_mm, sheet_count, weight_kg, warehouse_zone, status, stored_at, outbound_at) VALUES
('FB-2026001', 1, 70, 787,  12000, 620.00, 'A区', '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL),
('FB-2026002', 2, 70, 1092, 18000, 1400.00, 'A区', '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, NULL),
('FB-2026003', 2, 70, 1092, 12000, 1000.00, 'B区', '已出库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
('FB-2026004', 4, 80,  850,  9000, 430.00, 'B区', '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL),
('FB-2026005', 1, 70, 787,  8000, 410.00, 'C区', '已入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, NULL);

-- 切裁领料种子：CO-2026001 从 R-2026001 领过一次（1000m / 220.50kg），
-- R-2026001 当前余长 11000m、余重 2425.50kg 即 12000m/2646.00kg 扣减后的结果，账本自洽。
INSERT INTO cutting_requisition
    (requisition_no, order_id, roll_id, length_m, weight_kg, operator, operated_at, remain_length_m, remain_weight_kg) VALUES
('LL-000001', 1, 1, 1000, 220.50, '王建国', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, 11000, 2425.50);

-- 一次失败的领料留痕：CO-2026001 试图从待检卷 R-2026006 领料（定量也不符），
-- 母卷未扣、订单未动，失败原因留在库里，重开页面仍可追溯。
INSERT INTO requisition_attempt
    (request_token, order_id, roll_id, requisition_id, length_m, weight_kg, operator, result, fail_reason, attempted_at) VALUES
('seed-fail-0001', 1, 6, NULL, 500, 54.00, '王建国', 'FAILED',
 '母卷状态为「待检」，不可领料（只有「可用」母卷可领）；母卷定量 90 与订单目标 70 偏差超过 3',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR);

-- 成功行对应的请求留痕（request_token 唯一，重放只能拿回原结果）
INSERT INTO requisition_attempt
    (request_token, order_id, roll_id, requisition_id, length_m, weight_kg, operator, result, fail_reason, attempted_at) VALUES
('seed-ok-0001', 1, 1, 1, 1000, 220.50, '王建国', 'SUCCESS', NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR);

-- 发号器初值：种子已占用 LL-000001 与 WX-000001~000004。
INSERT INTO biz_no_seq (seq_name, next_val) VALUES
('REQUISITION', 2),
('MAINTENANCE', 5);
