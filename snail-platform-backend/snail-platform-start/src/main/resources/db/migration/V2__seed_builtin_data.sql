-- =========================================================
-- 默认租户 + 内置角色 + admin 账号
-- admin 口令为 admin123 的 BCrypt 密文(Hutool 实算),上线前必须改
-- 新租户开通不靠迁移脚本:由平台管理功能在运行时按本模板复制
-- =========================================================

INSERT INTO sys_tenant (tenant_id, tenant_code, name, status, create_time)
VALUES (1, 'default', '默认租户', 0, NOW());

INSERT INTO sys_org (org_id, tenant_id, parent_id, name, ancestors, sort, create_time)
VALUES (1, 1, 0, 'Snail', '0', 0, NOW());

INSERT INTO sys_role (role_id, tenant_id, role_name, role_code, data_scope, is_system, sort, create_time) VALUES
(1, 1, '超级管理员', 'admin',  1, 1, 1, NOW()),
(2, 1, '普通角色',   'common', 4, 1, 2, NOW());

INSERT INTO sys_user (user_id, tenant_id, username, nickname, password, org_id, status, create_time)
VALUES (1, 1, 'admin', '超级管理员', '$2a$10$NPfvBiM7OT1Ibw.574fQ.ukPvo4jlW31/JLF3R3T/IRv8yMjEn8xy', 1, 0, NOW());

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);
