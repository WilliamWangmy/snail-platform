-- 干什么: sys_user 增加密码最近修改时间列,空即待强制改密(下次登录成功后必须先改密)
-- 能否重跑: 不能;Flyway 版本迁移仅执行一次,重放需整库重建
ALTER TABLE sys_user
    ADD COLUMN password_modify_time DATETIME DEFAULT NULL
        COMMENT '密码最近修改时间:NULL 待强制改密,非空为最近一次改密时间' AFTER token_version;
