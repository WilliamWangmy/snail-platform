-- =========================================================
-- 文件存储域全量脚本:建表 + 内置文件类型字典 + 菜单权限种子,一个文件装齐
-- 干什么:sys_file 元数据表(含 file_type);sys_dict_type 加 is_system 内置标记;
--         种子内置字典 sys_file_type(文件类型)与默认值 平台文件(platform);
--         文件管理/平台文件菜单与权限码;默认租户 admin 平台面补授
-- 能否重跑:不能;Flyway 版本迁移仅执行一次,重放需整库重建
-- 前置条件:先跑 V1(系统域,含 sys_dict_type),菜单挂在 sys_menu 的 1/2 号目录下
-- 对象内容不在库,平台只存元数据与 storage_key
-- 配额列挂在 sys_tenant.storage_limit_mb,不在本表算总量;无唯一键(同名文件合法)
-- 删除走逻辑删,物理残留由将来的 GC 任务清扫(MVP 不算内容摘要,sha256 列与去重同批后置)
-- 口径:file_type 存 sys_file_type 的 dict_value,缺省 platform=平台文件;内置字典
--      (is_system=1)与其数据项不可删除,后续开通的租户由 TenantService.provision 复制同款
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
    file_type      VARCHAR(50)  NOT NULL DEFAULT 'platform' COMMENT '文件类型,内置字典 sys_file_type 的值,缺省 platform=平台文件',
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
    KEY idx_file_name (tenant_id, file_name),
    KEY idx_file_type (tenant_id, file_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件元数据,租户托管';

-- ---------- 菜单与权限码种子 ----------
-- 内置 admin 按 A 语义隐式持有全部 scope=2 权限码,不落 sys_role_menu;平台面(scope=1)要显式授,见文末

INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path,           component,              perm_code,              icon,       sort, is_system, create_time)
VALUES
-- 租户面(scope=2):文件管理
(180,  1,   '文件管理', 2, 2, '/system/file',   'system/file/index',   'system:file:list',     'Document',   9, 1, NOW()),
(1801, 180, '删除文件', 3, 2, NULL,             NULL,                  'system:file:delete',   NULL,         1, 1, NOW()),
(1802, 180, '上传文件', 3, 2, NULL,             NULL,                  'system:file:upload',   NULL,         2, 1, NOW()),
(1803, 180, '下载文件', 3, 2, NULL,             NULL,                  'system:file:download', NULL,         3, 1, NOW()),
-- 平台面(scope=1):平台文件
(230,  2,   '平台文件', 2, 1, '/platform/file', 'platform/file/index', 'platform:file:list',   'Collection', 4, 1, NOW());

-- ---------- 默认租户 admin 的平台面授权 ----------
-- V1 文末的 CROSS JOIN 只覆盖当时已存在的 scope=1 行,所以带平台节点的域种子自备一条幂等补授(PK 是 role_id+menu_id)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
JOIN sys_menu m ON m.scope = 1 AND m.deleted = 0
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0;

-- ---------- 字典内置标记 + 内置文件类型字典种子 ----------
-- is_system=1 的类型只有种子与租户开通模板(TenantService.provision)能造,DictService 的删除接口
-- 对内置类型与其数据项一律业务拒绝——字典值可以维护(增改/启停)但不许删
ALTER TABLE sys_dict_type
    ADD COLUMN is_system TINYINT NOT NULL DEFAULT 0 COMMENT '内置标记:0 普通 1 内置;内置类型与数据项不可删除' AFTER status;

INSERT INTO sys_dict_type (tenant_id, dict_name, dict_type, status, is_system, remark, create_time)
VALUES (1, '文件类型', 'sys_file_type', 0, 1, '内置字典:文件管理的文件类型,可维护字典值但不可删除', NOW());

INSERT INTO sys_dict_data (tenant_id, dict_id, dict_label, dict_value, sort, status, remark, create_time)
SELECT 1, dict_id, '平台文件', 'platform', 0, 0, '内置默认值,不可删除', NOW()
FROM sys_dict_type
WHERE tenant_id = 1 AND dict_type = 'sys_file_type' AND deleted = 0;
