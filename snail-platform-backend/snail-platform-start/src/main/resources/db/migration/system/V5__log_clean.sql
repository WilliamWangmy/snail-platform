-- =========================================================
-- 日志清除按钮种子:操作日志/登录日志(租户面)与第三方调用流水(平台面)的清除权限
-- 干什么:sys_menu 补三颗按钮(1403/1503/2221);默认租户 admin 的平台面补授 scope=1 节点
-- 能否重跑:不能;Flyway 版本迁移仅执行一次,重放需整库重建
-- 前置条件:先跑 V1(140 登录日志/150 操作日志菜单)与 V4(222 第三方调用菜单)
-- 清除口径:三档保留期 1m/6m/1y 由接口白名单正则锁死;删除物理执行、不可恢复;
--           清除动作本身经 @OperLog 落一条 DELETE 审计
-- =========================================================

-- 菜单口径同 V1/V4:menu_type=3 按钮节点,path/component/icon 恒 NULL;perm_code 全局唯一,建后不可改
-- 1403/1503 挂租户面(scope=2):租户管理员可清本租户历史日志;2221 挂平台面(scope=1)
INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
(1403, 140, '清除登录日志', 3, 2, NULL, NULL, 'system:loginlog:clean', NULL, 3, 1, NOW()),
(1503, 150, '清除操作日志', 3, 2, NULL, NULL, 'system:operlog:clean', NULL, 3, 1, NOW()),
(2221, 222, '清除调用流水', 3, 1, NULL, NULL, 'platform:external:clean', NULL, 1, 1, NOW());

-- ---------- sys_login_log.login_type 注释扩位 ----------
-- 单点跳转签到(login_type=sso)落地:admin 向 workflow/dashboard 等独立端共用会话免登,
-- 落地端经 POST /system/auth/sso 上报 scene 补登录日志;仅改注释对齐口径,列定义不变
ALTER TABLE sys_login_log
    MODIFY COLUMN login_type VARCHAR(20) NOT NULL DEFAULT 'password'
    COMMENT '登录方式编码:password/sms/sso(单点跳转签到),后续 feishu/wecom/dingtalk';

-- ---------- 默认租户 admin 的平台面授权 ----------
-- 与 V1/V2/V3/V4 同口径:INSERT IGNORE 保幂等(PK 是 role_id+menu_id);谓词按 m.scope=1 一刀切
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.scope = 1 AND m.deleted = 0
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0;
