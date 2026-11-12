-- =========================================================
-- 第三方接入域全量脚本:建表 + 菜单权限种子,一个文件装齐(设计见 docs/design/external-gateway.md)
-- 干什么:sys_external_call_log 调用流水表;第三方调用菜单与权限码;默认租户 admin 平台面补授
-- 能否重跑:不能;Flyway 版本迁移仅执行一次,重放需整库重建
-- 前置条件:先跑 V1(系统域),菜单挂在 sys_menu 的 2 号目录下
-- 只增不改的账本:无审计四件套、无逻辑删除,清理策略(保留期/归档)另行拍板
-- 表在 application.yml 的 snail.db.tenant.ignore-tables:落账在异步线程,
-- 租户/操作人靠行内快照列,平台级调用(如短信验证码)tenant_id 为 NULL
-- =========================================================

CREATE TABLE sys_external_call_log (
    call_id     BIGINT        NOT NULL AUTO_INCREMENT COMMENT '调用ID',
    vendor      VARCHAR(30)   NOT NULL COMMENT '厂商标识:smtp-mail/aliyun-sms/amap',
    action      VARCHAR(50)   NOT NULL COMMENT '动作:send-mail/send-sms/geo-ip',
    caller      VARCHAR(30)   NOT NULL DEFAULT 'unknown' COMMENT '调用方标识,自报:notify/auth/system',
    biz_type    VARCHAR(50)   DEFAULT NULL COMMENT '业务域标签,调用方自填',
    biz_id      BIGINT        DEFAULT NULL COMMENT '关联业务ID',
    request     VARCHAR(2000) DEFAULT NULL COMMENT '请求快照,脱敏+截断',
    response    VARCHAR(2000) DEFAULT NULL COMMENT '响应摘要,脱敏+截断',
    status      TINYINT       NOT NULL DEFAULT 0 COMMENT '调用终态:0 成功 1 失败',
    error       VARCHAR(500)  DEFAULT NULL COMMENT '失败原因,截断',
    cost_ms     INT           NOT NULL DEFAULT 0 COMMENT '耗时毫秒',
    trace_id    VARCHAR(64)   DEFAULT NULL COMMENT '链路ID,MDC 透传,无链路体系时为 NULL',
    operator_id BIGINT        DEFAULT NULL COMMENT '触发人用户ID快照,系统触发为 NULL',
    tenant_id   BIGINT        DEFAULT NULL COMMENT '租户快照,平台级调用为 NULL',
    create_time DATETIME      DEFAULT NULL COMMENT '调用时间,落账线程显式写入',
    PRIMARY KEY (call_id),
    KEY idx_ecl_vendor (vendor, status, call_id),
    KEY idx_ecl_biz (caller, biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '第三方接口调用流水,只增不改';

-- ---------- 菜单与权限码种子 ----------
-- 菜单口径同 V1/V3:scope=1 平台面;perm_code 全局唯一,建后不可改
-- 流水页面只读,唯一种子按钮是清除调用流水:三档保留期 1m/6m/1y 由接口白名单正则锁死;
-- 物理删除不可恢复,清除动作本身经 @OperLog 落一条 DELETE 审计

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
(222,  2,  '第三方调用', 2, 1, '/platform/external', 'platform/external/index', 'platform:external:log', 'Connection', 6, 1, NOW()),
(2221, 222, '清除调用流水', 3, 1, NULL, NULL, 'platform:external:clean', NULL, 1, 1, NOW());

-- ---------- 默认租户 admin 的平台面授权 ----------
-- 与 V2/V3 同口径:INSERT IGNORE 保幂等(PK 是 role_id+menu_id)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.scope = 1 AND m.deleted = 0
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0;
