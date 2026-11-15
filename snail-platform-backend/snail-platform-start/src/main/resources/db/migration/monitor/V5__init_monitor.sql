-- =====================================================================
-- V5 系统监控:指标采样 / 告警规则 / 告警 / 诊断报告 四表 + 菜单与种子
-- 版本号全局递增(现最大 V4);Flyway 保证本脚本只执行一次,脚本自身不做 IF NOT EXISTS
-- 执行前置:无;存量数据处理:只增菜单/角色/规则种子,不改 sys_tenant 与既有列数据
-- 依据:docs/design/system-monitor-detailed-design.md 附录 A(v0.6 核对版)
-- =====================================================================

-- ---------- 采样表:平台进程与连接池的时序点,只增;保留 7 天由清理任务裁剪 ----------
CREATE TABLE sys_monitor_metric (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '采样ID',
    instance    VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '实例标识:一期单实例固定 default,多实例部署时填主机名/容器名',
    metric      VARCHAR(64)  NOT NULL COMMENT '指标键:MetricKey 枚举值,如 jvm.heap.used.percent',
    value       DOUBLE       NOT NULL COMMENT '采样值,单位随指标键',
    sample_time DATETIME     NOT NULL COMMENT '采样时间',
    PRIMARY KEY (id),
    KEY idx_mm_metric_time (metric, sample_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '监控指标采样:只增,按 metric + sample_time 聚合查询;租户拦截器 ignore 表';

-- ---------- 告警规则:配置数据,物理删除;名称唯一 ----------
CREATE TABLE sys_monitor_alert_rule (
    rule_id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '规则ID',
    name            VARCHAR(50)  NOT NULL COMMENT '规则名称,全局唯一',
    metric          VARCHAR(64)  NOT NULL COMMENT '指标键:MetricKey 枚举值',
    comparator      VARCHAR(8)   NOT NULL COMMENT '比较符:gt/gte/lt/lte/eq',
    threshold       DOUBLE       NOT NULL COMMENT '阈值',
    duration_points INT          NOT NULL DEFAULT 1 COMMENT '连续满足的采样点数才触发,1-10',
    level           VARCHAR(16)  NOT NULL COMMENT '级别:info/warning/critical',
    enabled         TINYINT      NOT NULL DEFAULT 0 COMMENT '开关:0 启用 1 停用;平台 0 正常/启用口径',
    is_system       TINYINT      NOT NULL DEFAULT 0 COMMENT '内置规则:0 否 1 是;内置不可删,只可改阈值/持续/级别/启停',
    silence_until   DATETIME     DEFAULT NULL COMMENT '静默截止时间,null 为未静默;期内不新开告警、不发通知',
    remark          VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by       BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time     DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by       BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time     DATETIME     DEFAULT NULL COMMENT '修改时间',
    PRIMARY KEY (rule_id),
    UNIQUE KEY uk_mar_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '监控告警规则:物理删除,删后规则名释放;租户拦截器 ignore 表';

-- ---------- 告警:一规则同时最多一条开放(firing/acked)告警;保留 90 天 ----------
CREATE TABLE sys_monitor_alert (
    alert_id      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '告警ID',
    rule_id       BIGINT       NOT NULL COMMENT '规则ID,规则删除后仍保留快照',
    rule_name     VARCHAR(50)  NOT NULL COMMENT '规则名称快照',
    metric        VARCHAR(64)  NOT NULL COMMENT '指标键快照',
    level         VARCHAR(16)  NOT NULL COMMENT '级别快照:info/warning/critical',
    status        VARCHAR(16)  NOT NULL COMMENT '状态:firing/acked/resolved',
    trigger_value DOUBLE       NOT NULL COMMENT '最近一次触发值',
    threshold     DOUBLE       NOT NULL COMMENT '阈值快照',
    comparator    VARCHAR(8)   NOT NULL COMMENT '比较符快照',
    first_time    DATETIME     NOT NULL COMMENT '首次触发时间',
    last_time     DATETIME     NOT NULL COMMENT '最近一次满足时间',
    resolve_time  DATETIME     DEFAULT NULL COMMENT '恢复时间',
    ack_by        VARCHAR(50)  DEFAULT NULL COMMENT '确认人显示名快照',
    ack_time      DATETIME     DEFAULT NULL COMMENT '确认时间',
    notify_log    JSON         DEFAULT NULL COMMENT '通知记录:[{time,type,recipients,channels,error}]',
    PRIMARY KEY (alert_id),
    KEY idx_ma_status (status),
    KEY idx_ma_rule_time (rule_id, first_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '监控告警:一规则同时最多一条开放告警;租户拦截器 ignore 表';

-- ---------- 诊断报告:保留 30 天 ----------
CREATE TABLE sys_monitor_diag_report (
    report_id    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '报告ID',
    trigger_by   VARCHAR(50)  NOT NULL COMMENT '触发人显示名快照',
    trigger_time DATETIME     NOT NULL COMMENT '触发时间',
    cost_ms      BIGINT       NOT NULL COMMENT '总耗时(毫秒)',
    overall      VARCHAR(16)  NOT NULL COMMENT '总体结论:pass/warn/fail',
    items        JSON         NOT NULL COMMENT '检查项结果:[{key,name,status,latencyMs,detail,suggestion}]',
    PRIMARY KEY (report_id),
    KEY idx_mdr_time (trigger_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '监控诊断报告:保留 30 天;租户拦截器 ignore 表';

-- ---------- 菜单 target 注释追加监控端 ----------
ALTER TABLE sys_menu
    MODIFY COLUMN target TINYINT NOT NULL DEFAULT 1
        COMMENT '展示端:1 管理端(admin 菜单树) 2 数据大屏(dashboard 菜单树,admin 仅配置不展示) 3 外链(admin 菜单树,新标签打开完整 URL) 4 流程端(workflow 菜单树) 5 移动端(app/小程序) 6 监控端(monitor 前端静态路由的权限树,不参与导航渲染);各端各自成树不互相嵌套;建后不可改';

-- ---------- 监控菜单:scope=1 平台面、target=6、visible=1(不参与 admin 导航) ----------
-- component 为占位值:权限树/角色授权树载体,真实页面由监控前端静态路由渲染(D-01)
INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, target, scope, path, component, perm_code, icon, sort, visible, is_system, create_time)
VALUES
(400,  0,   '监控中心', 1, 6, 1, '/monitor',           NULL,                     NULL,                            'Monitor',  9, 1, 1, NOW()),
(410,  400, '监控总览', 2, 6, 1, '/monitor/overview',  'monitor/overview/index', 'platform:monitor:overview',     'DataLine', 1, 1, 1, NOW()),
(420,  400, '告警中心', 2, 6, 1, '/monitor/alert',     'monitor/alert/index',    'platform:monitor:alert',        'Bell',     2, 1, 1, NOW()),
(430,  400, '日志查询', 2, 6, 1, '/monitor/log',       'monitor/log/index',      'platform:monitor:log',          'Document', 3, 1, 1, NOW()),
(440,  400, '系统诊断', 2, 6, 1, '/monitor/diag',      'monitor/diag/index',     'platform:monitor:diag',         'Tools',    4, 1, 1, NOW()),
(4201, 420, '规则管理', 3, 6, 1, NULL,                 NULL,                     'platform:monitor:rule',         NULL,       1, 0, 1, NOW()),
(4202, 420, '告警确认', 3, 6, 1, NULL,                 NULL,                     'platform:monitor:ack',          NULL,       2, 0, 1, NOW()),
(4203, 420, '告警静默', 3, 6, 1, NULL,                 NULL,                     'platform:monitor:silence',      NULL,       3, 0, 1, NOW()),
(4401, 440, '线程快照', 3, 6, 1, NULL,                 NULL,                     'platform:monitor:threads',      NULL,       1, 0, 1, NOW()),
(4402, 440, '环境快照', 3, 6, 1, NULL,                 NULL,                     'platform:monitor:env',          NULL,       2, 0, 1, NOW()),
(4403, 440, 'Arthas 在线诊断', 3, 6, 1, NULL,          NULL,                     'platform:monitor:arthas',       NULL,       3, 0, 1, NOW()),
(4404, 440, '数据库面板', 3, 6, 1, NULL,               NULL,                     'platform:monitor:db',           NULL,       4, 0, 1, NOW());

-- ---------- 运维角色:平台面数据权限、内置不可删;admin 账号同时持有 ----------
INSERT INTO sys_role
(role_id, tenant_id, role_name, role_code, data_scope, is_system, sort, create_time)
VALUES (3, 1, '平台运维', 'monitor_ops', 1, 1, 3, NOW());

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 3);

-- 运维角色:全部监控菜单(含按钮)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, m.menu_id
FROM sys_menu m
WHERE m.scope = 1 AND m.target = 6 AND m.deleted = 0;

-- 运维角色:补授操作/登录日志查看码(监控端日志页复用既有端点)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 3, m.menu_id
FROM sys_menu m
WHERE m.perm_code IN ('system:operlog:list', 'system:loginlog:list')
  AND m.deleted = 0;

-- admin 角色的平台面授权:照 V4 的 INSERT IGNORE SELECT 重放,覆盖新增菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.scope = 1 AND m.deleted = 0
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0;

-- ---------- 内置告警规则 8 条:enabled=0 即启用;阈值/持续点数可按运行情况调整 ----------
INSERT INTO sys_monitor_alert_rule
(rule_id, name, metric, comparator, threshold, duration_points, level, enabled, is_system, remark, create_time)
VALUES
(1, 'CPU 使用率过高',    'system.cpu.usage',        'gt', 85, 5, 'warning',  0, 1, '连续 5 个采样点 CPU 使用率 >85%', NOW()),
(2, 'JVM 堆使用率过高',  'jvm.heap.used.percent',   'gt', 90, 5, 'critical', 0, 1, '连续 5 个采样点堆使用率 >90%', NOW()),
(3, '磁盘使用率过高',    'disk.usage.percent',      'gt', 90, 1, 'critical', 0, 1, '任一采样点磁盘使用率 >90%', NOW()),
(4, '数据库连接失败',    'db.up',                   'lt', 1,  1, 'critical', 0, 1, '数据库探活失败即触发', NOW()),
(5, 'Valkey 连接失败',   'valkey.up',               'lt', 1,  1, 'critical', 0, 1, 'Valkey 探活失败即触发', NOW()),
(6, 'HTTP 5xx 比例过高', 'http.server.error.rate',  'gt', 5,  3, 'warning',  0, 1, '连续 3 个采样点 5xx 占比 >5%', NOW()),
(7, '连接池使用率过高',  'db.pool.usage.percent',   'gt', 90, 3, 'warning',  0, 1, '连续 3 个采样点连接池使用率 >90%', NOW()),
(8, '慢 SQL 数量过多',   'db.sql.slow.5m',          'gt', 10, 1, 'warning',  0, 1, '近 5 分钟慢 SQL 超过 10 次', NOW());
