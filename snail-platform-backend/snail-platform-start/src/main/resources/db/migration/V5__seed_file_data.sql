-- =========================================================
-- 文件面数据初始化:文件管理与平台文件的菜单节点与权限码
-- 建表在 V2__init_file_schema.sql,本文件只写数据——建表与数据初始化不混在同一文件
-- 下载权限码是补出来的口径:下载端点原先只要求登录,独立成码后才归角色授权管
-- 内置 admin 按 A 语义隐式持有全部 scope=2 权限码,不落 sys_role_menu;平台面(scope=1)要显式授,见文末
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path,           component,              perm_code,              icon,       sort, is_system, create_time)
VALUES
-- 租户面(scope=2):文件管理
(180,  1,   '文件管理', 2, 2, '/system/file',   'system/file/index',   'system:file:list',     'Document',   9, 1, NOW()),
(1801, 180, '删除文件', 3, 2, NULL,             NULL,                  'system:file:delete',   NULL,         1, 1, NOW()),
(1802, 180, '上传文件', 3, 2, NULL,             NULL,                  'system:file:upload',   NULL,         2, 1, NOW()),
(1803, 180, '下载文件', 3, 2, NULL,             NULL,                  'system:file:download', NULL,         3, 1, NOW()),
-- 平台面(scope=1):平台文件
(230,  2,   '平台文件', 2, 1, '/platform/file', 'platform/file/index', 'platform:file:list',   'Collection', 4, 1, NOW());

-- ---------- 默认租户 admin 的平台面授权 ----------
-- V4 文末的 CROSS JOIN 只覆盖当时已存在的 scope=1 行,所以带平台节点的域种子自备一条幂等补授(PK 是 role_id+menu_id)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.scope = 1 AND m.deleted = 0
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0;
