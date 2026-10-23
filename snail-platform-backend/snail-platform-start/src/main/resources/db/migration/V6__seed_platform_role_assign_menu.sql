-- =========================================================
-- 平台面权限码补齐:角色授权弹窗的“平台授权”隐藏节点
-- V5 已执行,按“迁移只增不改”追加本脚本
-- A 语义下平台面只认显式授权:仅默认租户 admin 落这一行
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
SELECT 1105, 110, '平台授权', 3, 1, NULL, NULL, 'platform:role:assign-menu', NULL, 5, 1, NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 1105);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, 1105
FROM sys_role r
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.role_id AND rm.menu_id = 1105);
