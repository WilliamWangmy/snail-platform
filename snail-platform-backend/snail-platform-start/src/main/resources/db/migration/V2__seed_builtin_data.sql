-- =========================================================
-- Snail Platform 内置种子数据:默认租户 + admin 账号 + 全局菜单目录 + 平台面授权
-- admin 口令为 admin123 的 BCrypt 密文(Hutool 实算),上线前必须改
-- 新租户开通不靠迁移脚本:由平台管理功能在运行时按本模板复制,且不写 sys_menu、不复制目录
-- 目录内节点全部 is_system=1;现有页面均为站内菜单,target 走默认 1
-- dashboard/外链菜单待对应页面落地后再追加种子
-- 授权按 A 语义:租户面(scope=2)隐式全量,不落库;只显式授平台面(scope=1)节点
-- 若改走 B 语义(租户面也全量显式):追加 scope=2 全量授权,provision 同步复制
-- =========================================================

-- ---------- 默认租户与初始组织 ----------
INSERT INTO sys_tenant (tenant_id, tenant_code, name, status, create_time)
VALUES (1, 'default', '默认租户', 0, NOW());

INSERT INTO sys_org (org_id, tenant_id, parent_id, name, ancestors, sort, create_time)
VALUES (1, 1, 0, 'Snail', '0', 0, NOW());

-- ---------- 内置角色与超级管理员 ----------
INSERT INTO sys_role (role_id, tenant_id, role_name, role_code, data_scope, is_system, sort, create_time) VALUES
(1, 1, '超级管理员', 'admin',  1, 1, 1, NOW()),
(2, 1, '普通角色',   'common', 4, 1, 2, NOW());

INSERT INTO sys_user (user_id, tenant_id, username, nickname, password, org_id, status, create_time)
VALUES (1, 1, 'admin', '超级管理员', '$2a$10$NPfvBiM7OT1Ibw.574fQ.ukPvo4jlW31/JLF3R3T/IRv8yMjEn8xy', 1, 0, NOW());

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- ---------- 全局菜单目录 ----------
INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
-- 租户面(scope=2)
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
(1105, 110, '平台授权',     3, 1, NULL,               NULL,                     'platform:role:assign-menu',  NULL,             5, 1, NOW()),
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
-- 平台面(scope=1)
(2,    0,   '平台管理',     1, 1, '/platform',        NULL,                     NULL,                         'Platform',       2, 1, NOW()),
(200,  2,   '租户管理',     2, 1, '/platform/tenant', 'platform/tenant/index',  'platform:tenant:list',       'School',         1, 1, NOW()),
(2001, 200, '新增租户',     3, 1, NULL,               NULL,                     'platform:tenant:create',     NULL,             1, 1, NOW()),
(2002, 200, '修改租户',     3, 1, NULL,               NULL,                     'platform:tenant:update',     NULL,             2, 1, NOW()),
(210,  2,   '菜单管理',     2, 1, '/platform/menu',   'platform/menu/index',    'platform:menu:list',         'Menu',           2, 1, NOW()),
(2101, 210, '新增菜单',     3, 1, NULL,               NULL,                     'platform:menu:create',       NULL,             1, 1, NOW()),
(2102, 210, '修改菜单',     3, 1, NULL,               NULL,                     'platform:menu:update',       NULL,             2, 1, NOW()),
(2103, 210, '删除菜单',     3, 1, NULL,               NULL,                     'platform:menu:delete',       NULL,             3, 1, NOW());

-- ---------- 默认租户 admin 的平台面授权 ----------
-- A 语义下租户面隐式全量,这里只落 scope=1 的 11 个节点(2/200/2001/2002/210/2101/2102/2103/1105/1402/1502)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0
  AND m.scope = 1
  AND m.deleted = 0;
