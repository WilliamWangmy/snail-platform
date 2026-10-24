-- =========================================================
-- V6:自助安全种子数据——在线用户菜单与强制下线按钮
-- 口径:170 在线用户挂租户面(scope=2),admin 角色 A 语义隐式全量,provision 模板零改动;
--       1701/1006 均为租户面按钮,同样隐式;无平台面节点,故无显式授权种子
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
(170,  1,   '在线用户', 2, 2, '/system/online', 'system/online/index', 'system:online:list', 'User', 8, 1, NOW()),
(1701, 170, '强制下线', 3, 2, NULL,             NULL,                  'system:online:kick', NULL,  1, 1, NOW()),
(1006, 100, '强制下线', 3, 2, NULL,             NULL,                  'system:user:kick',   NULL,  6, 1, NOW());
