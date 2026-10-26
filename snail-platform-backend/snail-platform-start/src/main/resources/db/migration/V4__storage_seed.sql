-- =========================================================
-- V4:文件存储菜单种子——文件管理(租户面 180)与平台文件(平台面 230)
-- 平台面授权按 A 语义补 admin 角色(租户 1)显式授权,与 V2 的 scope=1 全量授权同一口径;
-- 租户面隐式全量不落库
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
-- 租户面(scope=2)
(180,  1,   '文件管理',     2, 2, '/system/file',     'system/file/index',      'system:file:list',           'Document',       9, 1, NOW()),
(1801, 180, '删除文件',     3, 2, NULL,               NULL,                     'system:file:delete',         NULL,             1, 1, NOW()),
(1802, 180, '上传文件',     3, 2, NULL,               NULL,                     'system:file:upload',         NULL,             2, 1, NOW()),
-- 平台面(scope=1)
(230,  2,   '平台文件',     2, 1, '/platform/file',   'platform/file/index',    'platform:file:list',         'Collection',     4, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0
  AND m.menu_id = 230
  AND m.deleted = 0;
