-- =========================================================
-- 文件存储建表:对象内容不在库,平台只存元数据与 storage_key
-- 配额列挂在 sys_tenant.storage_limit_mb,不在本表算总量;无唯一键(同名文件合法)
-- 删除走逻辑删,物理残留由将来的 GC 任务清扫(MVP 不算内容摘要,sha256 列与去重同批后置)
-- 上传/下载权限码属数据初始化,见 V5__seed_file_data.sql
-- =========================================================

CREATE TABLE sys_file (
    file_id        BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文件ID',
    tenant_id      BIGINT       NOT NULL COMMENT '租户ID,拦截器自动填充',
    file_name      VARCHAR(255) NOT NULL COMMENT '原始文件名,已清洗路径与控制字符',
    ext            VARCHAR(20)  DEFAULT NULL COMMENT '小写扩展名,无点',
    content_type   VARCHAR(100) DEFAULT NULL COMMENT '客户端声明的类型,不信任仅记录',
    size           BIGINT       NOT NULL DEFAULT 0 COMMENT '字节数',
    storage_key    VARCHAR(500) NOT NULL COMMENT '对象键 {tenantId}/{yyyyMM}/{uuid}.{ext}',
    storage_type   VARCHAR(20)  NOT NULL COMMENT '存储后端:sftp | s3',
    biz_type       VARCHAR(50)  DEFAULT NULL COMMENT '业务域标签:contract/avatar/…',
    biz_id         BIGINT       DEFAULT NULL COMMENT '关联业务ID',
    upload_by      BIGINT       DEFAULT NULL COMMENT '上传人用户ID',
    upload_by_name VARCHAR(30)  DEFAULT NULL COMMENT '上传人显示名快照,管理页免跨模块读',
    create_by      BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time    DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by      BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time    DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (file_id),
    KEY idx_file_biz (tenant_id, biz_type, biz_id),
    KEY idx_file_uploader (tenant_id, upload_by),
    KEY idx_file_name (tenant_id, file_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件元数据,租户托管';
