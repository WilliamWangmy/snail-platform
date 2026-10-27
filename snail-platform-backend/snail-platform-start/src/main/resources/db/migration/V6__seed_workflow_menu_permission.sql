-- =========================================================
-- 流程中心菜单与权限码(Flowable 集成 P2)
-- 菜单口径同 V2:scope=2 租户面、scope=1 平台面;perm_code 全局唯一含已删记录,建后不可改
-- 内置 admin 按 A 语义隐式持有全部 scope=2 权限码,不落 sys_role_menu;平台面(scope=1)要显式授,见文末
-- 组件路径按 admin 前端 views 目录约定(workflow/*/index、platform/workflow/*/index),页面在前端批次落地
-- 待办与已办是两个独立列表页,权限码必须分开:sys_menu.perm_code 全局唯一,同码只能挂一个节点
-- =========================================================

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
-- 租户面(scope=2):流程中心
(3,    0,   '流程中心',   1, 2, '/workflow',              NULL,                          NULL,                      'Stamp',     3, 1, NOW()),
(300,  3,   '模型管理',   2, 2, '/workflow/model',        'workflow/model/index',        'workflow:model:list',      'SetUp',     1, 1, NOW()),
(3001, 300, '新增模型',   3, 2, NULL,                     NULL,                          'workflow:model:create',    NULL,        1, 1, NOW()),
(3002, 300, '修改模型',   3, 2, NULL,                     NULL,                          'workflow:model:update',    NULL,        2, 1, NOW()),
(3003, 300, '删除模型',   3, 2, NULL,                     NULL,                          'workflow:model:delete',    NULL,        3, 1, NOW()),
(3004, 300, '发布流程',   3, 2, NULL,                     NULL,                          'workflow:model:publish',   NULL,        4, 1, NOW()),
(310,  3,   '我的待办',   2, 2, '/workflow/todo',         'workflow/todo/index',         'workflow:task:list',       'Clock',     2, 1, NOW()),
(3101, 310, '办理任务',   3, 2, NULL,                     NULL,                          'workflow:task:handle',     NULL,        1, 1, NOW()),
(320,  3,   '我的已办',   2, 2, '/workflow/done',         'workflow/done/index',         'workflow:task:done',       'Checked',   3, 1, NOW()),
(330,  3,   '我发起的',   2, 2, '/workflow/started',      'workflow/started/index',      'workflow:instance:list',   'Promotion', 4, 1, NOW()),
(3301, 330, '发起流程',   3, 2, NULL,                     NULL,                          'workflow:instance:start',  NULL,        1, 1, NOW()),
(3302, 330, '撤销申请',   3, 2, NULL,                     NULL,                          'workflow:instance:withdraw',NULL,       2, 1, NOW()),
-- 平台面(scope=1):流程模板目录(平台维护一份,租户只读来建模型)
(240,  2,   '流程模板',   2, 1, '/platform/workflow/template', 'platform/workflow/template/index', 'platform:workflow:template:list', 'Files', 5, 1, NOW()),
(2401, 240, '新增模板',   3, 1, NULL,                     NULL,                          'platform:workflow:template:create', NULL, 1, 1, NOW()),
(2402, 240, '修改模板',   3, 1, NULL,                     NULL,                          'platform:workflow:template:update', NULL, 2, 1, NOW()),
(2403, 240, '删除模板',   3, 1, NULL,                     NULL,                          'platform:workflow:template:delete', NULL, 3, 1, NOW());

-- ---------- 默认租户 admin 的平台面授权 ----------
-- V2 尾段的 CROSS JOIN 只覆盖当时已存在的 scope=1 行,本文件新增的 4 个节点必须在这里补授,
-- 否则平台管理员看不到"流程模板"菜单。INSERT IGNORE 保幂等(PK 是 role_id+menu_id)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.scope = 1 AND m.deleted = 0
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0;
