-- =========================================================
-- 第三方接口网关:调用流水表(设计见 docs/design/external-gateway.md)
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
