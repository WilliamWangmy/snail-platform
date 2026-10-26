-- =========================================================
-- 文件下载权限码:下载端点原先只要求登录,补独立权限码后由角色授权控制
-- 内置 admin 按 A 语义隐式持有全部 scope=2 权限码,不落 sys_role_menu;普通角色需显式授权
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
(1803, 180, '下载文件', 3, 2, NULL, NULL, 'system:file:download', NULL, 3, 1, NOW());
