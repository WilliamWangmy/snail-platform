-- =========================================================
-- V3:通知中心表结构——消息主体 + 收件行 + 出站投递
-- 口径:收件行一人一行,收件箱与已读的事实源;显示名发送时快照,改名不回溯;
--       outbound 是队列状态表,在租户拦截器 ignore 清单(见 application.yml),租户归属靠行内快照列;
--       消息主体/收件行是租户托管表,走拦截器自动圈定
-- =========================================================

CREATE TABLE sys_notify_message (
    message_id  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    tenant_id   BIGINT       NOT NULL COMMENT '租户ID,拦截器自动填充',
    title       VARCHAR(100) NOT NULL COMMENT '标题',
    content     TEXT         NOT NULL COMMENT '正文,纯文本',
    biz_type    VARCHAR(50)  DEFAULT NULL COMMENT '业务域标签:announcement/account/…',
    biz_id      BIGINT       DEFAULT NULL COMMENT '关联业务ID',
    sender_id   BIGINT       DEFAULT NULL COMMENT '发送人用户ID,系统代发为 NULL',
    sender_name VARCHAR(30)  DEFAULT NULL COMMENT '发送人显示名快照,发送时刻定格',
    send_time   DATETIME     DEFAULT NULL COMMENT '发送时间',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (message_id),
    KEY idx_nm_biz (tenant_id, biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知消息主体,租户托管';

CREATE TABLE sys_notify_recipient (
    recipient_id BIGINT      NOT NULL AUTO_INCREMENT COMMENT '收件行ID',
    message_id   BIGINT      NOT NULL COMMENT '消息ID,sys_notify_message.message_id',
    tenant_id    BIGINT      NOT NULL COMMENT '租户ID,拦截器自动填充',
    user_id      BIGINT      NOT NULL COMMENT '收件人用户ID,收件箱谓词',
    nickname     VARCHAR(30) DEFAULT NULL COMMENT '收件人显示名快照,发送时刻定格',
    read_status  TINYINT     NOT NULL DEFAULT 0 COMMENT '已读:0 未读 1 已读',
    read_time    DATETIME    DEFAULT NULL COMMENT '已读时间',
    create_by    BIGINT      DEFAULT NULL COMMENT '创建人用户ID',
    create_time  DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by    BIGINT      DEFAULT NULL COMMENT '修改人用户ID',
    update_time  DATETIME    DEFAULT NULL COMMENT '修改时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删;收件箱移除即置 1',
    PRIMARY KEY (recipient_id),
    KEY idx_nr_inbox (tenant_id, user_id, read_status, recipient_id),
    KEY idx_nr_message (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知收件行,一人一行';

CREATE TABLE sys_notify_outbound (
    outbound_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '投递记录ID',
    message_id  BIGINT       NOT NULL COMMENT '消息ID,sys_notify_message.message_id',
    tenant_id   BIGINT       DEFAULT NULL COMMENT '消息归属租户快照,溯源用',
    channel     VARCHAR(20)  NOT NULL COMMENT '通道:mail | log',
    target      VARCHAR(100) DEFAULT NULL COMMENT '寻址摘要:mail 记 bcc×N,log 记无通道说明',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '投递状态:1 成功 2 失败',
    error       VARCHAR(500) DEFAULT NULL COMMENT '失败原因,截断落库',
    retry_count INT          NOT NULL DEFAULT 0 COMMENT '重试次数,0 即一次投递成功',
    send_time   DATETIME     DEFAULT NULL COMMENT '投递完成时间',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人用户ID,投递线程写入留空',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删,本表不提供删除入口',
    PRIMARY KEY (outbound_id),
    KEY idx_no_message (message_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知出站投递记录,异步线程写入';
