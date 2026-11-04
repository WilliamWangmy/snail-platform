-- =========================================================
-- 第三方接口网关:菜单与权限码(建表在 V8__init_external_schema.sql)
-- 菜单口径同 V4/V6:scope=1 平台面;perm_code 全局唯一,建后不可改
-- 流水是平台管理动作,只读页面,无按钮种子;默认租户 admin 的平台面授权见文末
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
(222,  2,  '第三方调用', 2, 1, '/platform/external', 'platform/external/index', 'platform:external:log', 'Connection', 6, 1, NOW());

-- ---------- 默认租户 admin 的平台面授权 ----------
-- 与 V6 同口径:INSERT IGNORE 保幂等(PK 是 role_id+menu_id)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.scope = 1 AND m.deleted = 0
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0;
