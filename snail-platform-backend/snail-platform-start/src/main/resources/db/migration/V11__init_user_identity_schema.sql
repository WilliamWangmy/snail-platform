-- =========================================================
-- 第三方单点登录(设计见 docs/单点登录技术方案_v1.md §6)
-- 用户第三方身份绑定表:一行=厂商身份x平台账号,登录查询键(source,open_id)
-- open_id 口径:钉钉 unionId / 飞书 openId / 企微 userid
-- 同一 (source,open_id) 允许多行(多租户撞号,登录走 pickKey 二段式);
-- 唯一键挡并发首绑的重复行。不设软删列:本期无解绑功能,错绑物理删行
-- 回滚:DROP TABLE sys_user_identity(上线初期表为空,无数据方向依赖)
-- =========================================================

CREATE TABLE sys_user_identity (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT       NOT NULL COMMENT '平台用户ID,sys_user.user_id',
    tenant_id       BIGINT       NOT NULL DEFAULT 0 COMMENT '绑定时刻的用户租户快照,仅展示口径,不做过滤列',
    source          VARCHAR(20)  NOT NULL COMMENT '来源编码:dingtalk/feishu/wecom',
    open_id         VARCHAR(128) NOT NULL COMMENT '厂商内稳定标识:钉钉unionId/飞书openId/企微userid',
    union_id        VARCHAR(128) NULL COMMENT '厂商跨应用标识:钉钉与open_id同值,飞书预留',
    nickname        VARCHAR(64)  NULL COMMENT '最近一次厂商昵称快照',
    last_login_time DATETIME(3)  NULL COMMENT '最近一次经此身份登录时间',
    create_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_identity (source, open_id, user_id),
    KEY idx_user_identity_user (user_id)
) ENGINE = InnoDB COMMENT ='第三方身份绑定:一行=厂商身份x平台账号,登录查询键(source,open_id)';
