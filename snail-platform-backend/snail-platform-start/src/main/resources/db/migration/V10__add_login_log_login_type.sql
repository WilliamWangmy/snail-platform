-- =========================================================
-- 手机验证码登录(设计见 docs/design/sms-login.md)
-- 登录日志加登录方式列:编码字符串自明语义,新增登录方式(飞书/企微/钉钉)零迁移
-- 存量行 'password' 由 DEFAULT 回填,写路径补显式赋值,口径不变
-- =========================================================

ALTER TABLE sys_login_log
    ADD COLUMN login_type VARCHAR(20) NOT NULL DEFAULT 'password'
        COMMENT '登录方式编码:password/sms,后续 feishu/wecom/dingtalk' AFTER os;
