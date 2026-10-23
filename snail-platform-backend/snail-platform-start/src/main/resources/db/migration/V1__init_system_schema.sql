-- =========================================================
-- Snail Platform 系统基础表(多租户版)
-- 租户模式:共享库共享表 + tenant_id 判别列,MP TenantLineInnerInterceptor 默认全员注入
-- ignore 清单:sys_tenant / sys_user_role / 两张日志表(日志带 tenant_id,由查询侧或切面显式写)
-- 登录口径:不选租户,账号+密码+验证码;username 全表唯一,按用户名自动定位租户
-- 业务表:审计四件套 + 逻辑删除;日志表只增,不配逻辑删除,时间毫秒
-- 唯一键口径:被逻辑删除记录的键值不释放、不可复用
-- =========================================================

CREATE TABLE sys_tenant (
    tenant_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    tenant_code   VARCHAR(64)  NOT NULL COMMENT '租户标识,平台侧指代租户的业务坐标,建后不可改,全局唯一',
    name          VARCHAR(50)  NOT NULL COMMENT '租户名称',
    contact_user  VARCHAR(30)  DEFAULT NULL COMMENT '联系人',
    contact_phone VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    expire_time   DATETIME     DEFAULT NULL COMMENT '到期时间,NULL 为不限',
    account_limit INT          DEFAULT NULL COMMENT '账号数上限,NULL 为不限',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 正常 1 停用',
    create_by     BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time   DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by     BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time   DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (tenant_id),
    UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '租户:平台级表,租户拦截器 ignore 清单内,仅平台管理员可见全量';

CREATE TABLE sys_user (
    user_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID,拦截器自动填充,业务代码不手写',
    username    VARCHAR(30)  NOT NULL COMMENT '登录名,全表唯一;登录自动定位所属租户',
    nickname    VARCHAR(30)  NOT NULL COMMENT '显示名',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文',
    email       VARCHAR(50)  DEFAULT NULL COMMENT '邮箱',
    phone       VARCHAR(20)  DEFAULT NULL COMMENT '手机号',
    org_id      BIGINT       DEFAULT NULL COMMENT '所属组织,关联 sys_org.org_id;一用户一主组织',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 正常 1 停用',
    remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_user_username (username),
    KEY idx_user_org (tenant_id, org_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户:认证主体与档案,不含登录足迹;租户拦截器生效';

CREATE TABLE sys_org (
    org_id      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '组织ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID,拦截器自动填充',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父组织ID,根节点记 0',
    name        VARCHAR(50)  NOT NULL COMMENT '组织名称',
    ancestors   VARCHAR(500) NOT NULL DEFAULT '0' COMMENT '祖级路径,逗号分隔如 0,1,5;子树查询与数据权限靠它,租户内自洽',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '同级显示顺序',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 正常 1 停用',
    remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (org_id),
    KEY idx_org_parent (tenant_id, parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '组织:每租户各自一棵树,部门/单位同构;层级由 ancestors 推导不另存;租户拦截器生效';

CREATE TABLE sys_role (
    role_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID,拦截器自动填充',
    role_name   VARCHAR(30)  NOT NULL COMMENT '角色名称',
    role_code   VARCHAR(64)  NOT NULL COMMENT '角色标识,程序判权用,如 admin;租户内唯一,建后不可改',
    data_scope  TINYINT      NOT NULL DEFAULT 4 COMMENT '数据权限:1 全部 2 本组织 3 本组织及以下 4 本人;多角色取各谓词并集',
    is_system   TINYINT      NOT NULL DEFAULT 0 COMMENT '内置角色:0 否 1 是;内置角色不可删、不可改 role_code,由种子数据写入',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 正常 1 停用',
    remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (role_id),
    UNIQUE KEY uk_role_code (tenant_id, role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色:权限包的身份面;数据权限只按 data_scope 枚举,不配组织清单表;租户拦截器生效';

-- ---------- 关系表:关联即事实,不配审计字段,不加 tenant_id ----------
-- user/role 本身已被租户圈定,经此表关联不会漏出跨租户数据;列入拦截器 ignore 清单

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (user_id, role_id),
    KEY idx_ur_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户-角色授权,N:N;租户拦截器 ignore 表';

-- ---------- 日志表:只增不改,无逻辑删除;带 tenant_id 供筛选但进 ignore 清单 ----------
-- 平台管理员要跨租户看日志,拦截器一刀切会挡正路;租户管理员视图由查询侧显式加 tenant_id 条件

CREATE TABLE sys_login_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    tenant_id  BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID,请求线程定位用户后显式写入;0=用户不存在的归属不明失败',
    username   VARCHAR(50)  NOT NULL COMMENT '登录名快照:失败时用户可能不存在,删除后日志仍要可读;用户名全局唯一,快照即够定位',
    user_id    BIGINT       DEFAULT NULL COMMENT '用户ID,登录成功时回填',
    ip         VARCHAR(50)  DEFAULT NULL COMMENT '客户端IP',
    browser    VARCHAR(100) DEFAULT NULL COMMENT '浏览器',
    os         VARCHAR(100) DEFAULT NULL COMMENT '操作系统',
    status     TINYINT      NOT NULL COMMENT '结果:1 成功 0 失败',
    msg        VARCHAR(255) DEFAULT NULL COMMENT '失败原因',
    login_time DATETIME(3)  NOT NULL COMMENT '登录时间',
    PRIMARY KEY (id),
    KEY idx_ll_tenant_time (tenant_id, login_time),
    KEY idx_ll_username (tenant_id, username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '登录日志:成功与失败都记,只增;租户拦截器 ignore 表';

CREATE TABLE sys_oper_log (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '日志ID',
    tenant_id      BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID,请求线程取好后由 framework-log 切面带入(异步落库线程无租户上下文)',
    trace_id       VARCHAR(32)   DEFAULT NULL COMMENT '链路ID,与应用日志互查',
    module         VARCHAR(50)   NOT NULL COMMENT '功能模块标题',
    business_type  TINYINT       NOT NULL DEFAULT 9 COMMENT '业务类型:1 新增 2 修改 3 删除 4 导出 5 导入 9 其他',
    method         VARCHAR(200)  NOT NULL COMMENT 'Java 方法,类名.方法名',
    request_method VARCHAR(10)   DEFAULT NULL COMMENT 'HTTP 方法',
    operator       VARCHAR(50)   DEFAULT NULL COMMENT '操作人登录名快照',
    org_id         BIGINT        DEFAULT NULL COMMENT '操作人组织ID,按部门筛选用',
    ip             VARCHAR(50)   DEFAULT NULL COMMENT '客户端IP',
    url            VARCHAR(255)  DEFAULT NULL COMMENT '请求地址',
    param          TEXT          COMMENT '请求入参,落库前必须脱敏(密码等字段替换为 ******)',
    result         TEXT          COMMENT '响应出参 JSON',
    status         TINYINT       NOT NULL COMMENT '结果:1 成功 0 失败',
    error_msg      VARCHAR(2000) DEFAULT NULL COMMENT '异常信息,超长截断',
    cost_time      BIGINT        DEFAULT NULL COMMENT '耗时(毫秒)',
    oper_time      DATETIME(3)   NOT NULL COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_ol_tenant_time (tenant_id, oper_time),
    KEY idx_ol_trace (trace_id),
    KEY idx_ol_operator (tenant_id, operator)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作日志:framework-log 切面异步落库,只增;租户拦截器 ignore 表';
