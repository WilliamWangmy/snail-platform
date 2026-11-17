-- 用户批量导入:模板下载与批量导入共用一个按钮级权限码,导入入口在用户管理页
-- 租户面(scope=2)按 A 语义隐式全量,无需 sys_role_menu 补授;menu_id 接在 1001-1006 之后取 1007
INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
(1007, 100, '导入用户', 3, 2, NULL, NULL, 'system:user:import', NULL, 7, 1, NOW());
