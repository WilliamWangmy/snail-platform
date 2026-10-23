-- =========================================================
-- 内置菜单目录 + 默认租户 admin 的平台面授权
-- 目录全局唯一一份:新租户开通不写 sys_menu,也不复制目录
-- 现有 34 行全部为站内菜单,target 走默认 1;dashboard/外链待对应页面落地后再加种子
-- 本脚本按 A 语义(admin 隐式持有全部租户面权限):只授 scope=1 节点
-- 若改走 B 语义(全量显式):本脚本追加 scope=2 全量授权,provision 同步复制
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
-- ---------- 租户面(scope=2) ----------
(1,    0,   '系统管理',     1, 2, '/system',          NULL,                     NULL,                         'Setting',        1, 1, NOW()),
(100,  1,   '用户管理',     2, 2, '/system/user',     'system/user/index',      'system:user:list',           'User',           1, 1, NOW()),
(1001, 100, '新增用户',     3, 2, NULL,               NULL,                     'system:user:create',         NULL,             1, 1, NOW()),
(1002, 100, '修改用户',     3, 2, NULL,               NULL,                     'system:user:update',         NULL,             2, 1, NOW()),
(1003, 100, '删除用户',     3, 2, NULL,               NULL,                     'system:user:delete',         NULL,             3, 1, NOW()),
(1004, 100, '重置密码',     3, 2, NULL,               NULL,                     'system:user:reset-password', NULL,             4, 1, NOW()),
(1005, 100, '分配角色',     3, 2, NULL,               NULL,                     'system:user:assign-role',    NULL,             5, 1, NOW()),
(110,  1,   '角色管理',     2, 2, '/system/role',     'system/role/index',      'system:role:list',           'UserFilled',     2, 1, NOW()),
(1101, 110, '新增角色',     3, 2, NULL,               NULL,                     'system:role:create',         NULL,             1, 1, NOW()),
(1102, 110, '修改角色',     3, 2, NULL,               NULL,                     'system:role:update',         NULL,             2, 1, NOW()),
(1103, 110, '删除角色',     3, 2, NULL,               NULL,                     'system:role:delete',         NULL,             3, 1, NOW()),
(1104, 110, '分配菜单',     3, 2, NULL,               NULL,                     'system:role:assign-menu',    NULL,             4, 1, NOW()),
(120,  1,   '组织管理',     2, 2, '/system/org',      'system/org/index',       'system:org:list',            'OfficeBuilding', 3, 1, NOW()),
(1201, 120, '新增组织',     3, 2, NULL,               NULL,                     'system:org:create',          NULL,             1, 1, NOW()),
(1202, 120, '修改组织',     3, 2, NULL,               NULL,                     'system:org:update',          NULL,             2, 1, NOW()),
(1203, 120, '删除组织',     3, 2, NULL,               NULL,                     'system:org:delete',          NULL,             3, 1, NOW()),
(130,  1,   '字典管理',     2, 2, '/system/dict',     'system/dict/index',      'system:dict:list',           'Collection',     4, 1, NOW()),
(1301, 130, '新增字典',     3, 2, NULL,               NULL,                     'system:dict:create',         NULL,             1, 1, NOW()),
(1302, 130, '修改字典',     3, 2, NULL,               NULL,                     'system:dict:update',         NULL,             2, 1, NOW()),
(1303, 130, '删除字典',     3, 2, NULL,               NULL,                     'system:dict:delete',         NULL,             3, 1, NOW()),
(140,  1,   '登录日志',     2, 2, '/system/loginlog', 'system/loginlog/index',  'system:loginlog:list',       'Key',            5, 1, NOW()),
(1401, 140, '导出登录日志', 3, 2, NULL,               NULL,                     'system:loginlog:export',     NULL,             1, 1, NOW()),
(1402, 140, '跨租户查看',   3, 1, NULL,               NULL,                     'platform:loginlog:all',      NULL,             2, 1, NOW()),
(150,  1,   '操作日志',     2, 2, '/system/operlog',  'system/operlog/index',   'system:operlog:list',        'Document',       6, 1, NOW()),
(1501, 150, '导出操作日志', 3, 2, NULL,               NULL,                     'system:operlog:export',      NULL,             1, 1, NOW()),
(1502, 150, '跨租户查看',   3, 1, NULL,               NULL,                     'platform:operlog:all',       NULL,             2, 1, NOW()),
-- ---------- 平台面(scope=1) ----------
(2,    0,   '平台管理',     1, 1, '/platform',        NULL,                     NULL,                         'Platform',       2, 1, NOW()),
(200,  2,   '租户管理',     2, 1, '/platform/tenant', 'platform/tenant/index',  'platform:tenant:list',       'School',         1, 1, NOW()),
(2001, 200, '新增租户',     3, 1, NULL,               NULL,                     'platform:tenant:create',     NULL,             1, 1, NOW()),
(2002, 200, '修改租户',     3, 1, NULL,               NULL,                     'platform:tenant:update',     NULL,             2, 1, NOW()),
(210,  2,   '菜单管理',     2, 1, '/platform/menu',   'platform/menu/index',    'platform:menu:list',         'Menu',           2, 1, NOW()),
(2101, 210, '新增菜单',     3, 1, NULL,               NULL,                     'platform:menu:create',       NULL,             1, 1, NOW()),
(2102, 210, '修改菜单',     3, 1, NULL,               NULL,                     'platform:menu:update',       NULL,             2, 1, NOW()),
(2103, 210, '删除菜单',     3, 1, NULL,               NULL,                     'platform:menu:delete',       NULL,             3, 1, NOW());

-- A 语义:租户面隐式全量,只落平台面 10 行(2/200/2001/2002/210/2101/2102/2103/1402/1502)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0
  AND m.scope = 1
  AND m.deleted = 0;
