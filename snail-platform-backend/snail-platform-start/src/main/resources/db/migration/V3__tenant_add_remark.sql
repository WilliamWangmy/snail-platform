-- =========================================================
-- sys_tenant 补备注列:其他业务表均有 remark,租户表对齐口径
-- (实体 SysTenant 与库表差异在阶段②集成测试暴露)
-- =========================================================

ALTER TABLE sys_tenant ADD COLUMN remark VARCHAR(500) DEFAULT NULL COMMENT '备注' AFTER status;
