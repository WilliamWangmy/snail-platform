-- =========================================================
-- V4:通知中心种子数据——菜单目录 + 平台面授权
-- 口径:160 通知中心挂租户面(scope=2),admin 角色 A 语义隐式全量,provision 模板零改动;
--       220/2201 是平台面(scope=1),为默认租户 admin 显式落授权(沿用 V2 显式 ID 种子风格);
--       按钮归属面与父菜单一致,不引入 V2 中"平台按钮挂租户父节点"的混挂形态
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
(160,  1,   '通知中心', 2, 2, '/system/notify',  'system/notify/index',  'system:notify:list',   'Bell',    7, 1, NOW()),
(220,  2,   '公告管理', 2, 1, '/platform/notify', 'platform/notify/index', 'platform:notify:list', 'Message', 3, 1, NOW()),
(2201, 220, '发送公告', 3, 1, NULL,               NULL,                    'platform:notify:send', NULL,      1, 1, NOW());

-- ---------- 默认租户 admin 的平台面授权:仅本次新增的两个节点 ----------
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.menu_id IN (220, 2201)
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0
  AND m.scope = 1
  AND m.deleted = 0;
