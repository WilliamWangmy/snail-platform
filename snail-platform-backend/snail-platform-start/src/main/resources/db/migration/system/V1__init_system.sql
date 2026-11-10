-- =========================================================
-- Snail Platform 系统域全量脚本(多租户版):建表 + 内置种子,一个文件装齐
-- 干什么:系统/通知基础表 + 第三方身份绑定表;默认租户 + admin 账号 + 全局菜单目录 + 平台面授权
-- 能否重跑:不能;Flyway 版本迁移仅执行一次,重放需整库重建
-- 前置条件:admin 口令以 BCrypt 密文(非明文)经 SNAIL_SEED_ADMIN_PASSWORD 注入 ${seedAdminPassword} 占位符;
--          dev 缺省为 admin123 密文(首登强制改密兜底),prod 缺该变量属性解析失败、启动被拦;
--          密文用与业务侧一致的 Hutool BCrypt.hashpw(明文) 离线生成
-- 租户模式:共享库共享表 + tenant_id 判别列,MP TenantLineInnerInterceptor 默认全员注入
-- ignore 清单:sys_tenant / sys_user_role / sys_menu / sys_role_menu / 两张日志表
--              / sys_notify_outbound(通知出站队列表,异步线程写入,租户归属靠行内快照)
--              / sys_user_identity(第三方身份绑定,登录按(source,open_id)跨租户定位)
--              (日志与队列表带 tenant_id,由查询侧或切面显式写)
-- 登录口径:不选租户,账号+密码+验证码;username 全表唯一,按用户名自动定位租户
-- 业务表:审计四件套 + 逻辑删除;日志表只增,不配逻辑删除,时间毫秒
-- 唯一键口径:被逻辑删除记录的键值不释放、不可复用
-- 菜单口径:sys_menu 为全局目录,平台维护一份,租户只经 sys_role_menu 授权,不持有副本
-- target:1 站内(admin 菜单树) 2 数据大屏(dashboard 菜单树,admin 只配置) 3 外链(admin 菜单树,新标签)
-- 权限码三段式 <面>:<模块>:<动作>;面随 scope 走:1 平台用 platform:,2 租户用 system:
-- 字典租户各自维护:无平台内置、无并集、无默认种子
-- 目录与外链 perm_code 为 NULL;MySQL 唯一索引允许多个 NULL
-- 种子只装系统面:文件面/流程面/第三方面种子在 V2/V3/V4 各自文件末尾
-- =========================================================

CREATE TABLE sys_tenant (
    tenant_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    tenant_code   VARCHAR(64)  NOT NULL COMMENT '租户标识,平台侧指代租户的业务坐标,建后不可改,全局唯一',
    name          VARCHAR(50)  NOT NULL COMMENT '租户名称',
    contact_user  VARCHAR(30)  DEFAULT NULL COMMENT '联系人',
    contact_phone VARCHAR(20)  DEFAULT NULL COMMENT '联系电话',
    expire_time   DATETIME     DEFAULT NULL COMMENT '到期时间,NULL 为不限',
    account_limit INT          DEFAULT NULL COMMENT '账号数上限,NULL 为不限',
    storage_limit_mb BIGINT    DEFAULT NULL COMMENT '存储配额 MB,NULL 为不限',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 正常 1 停用',
    remark        VARCHAR(500) DEFAULT NULL COMMENT '备注',
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
    token_version BIGINT     NOT NULL DEFAULT 0 COMMENT '令牌版本,改密/重置/停用/强制下线自增,进 JWT ver 声明实现吊销',
    password_modify_time DATETIME DEFAULT NULL COMMENT '密码最近修改时间:NULL 待强制改密,非空为最近一次改密时间',
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

CREATE TABLE sys_menu (
    menu_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID,根节点记 0',
    menu_name   VARCHAR(50)  NOT NULL COMMENT '菜单名称,界面展示用',
    menu_type   TINYINT      NOT NULL DEFAULT 2 COMMENT '类型:1 目录 2 菜单 3 按钮;建后不可改',
    target      TINYINT      NOT NULL DEFAULT 1 COMMENT '展示端:1 站内(admin 菜单树) 2 数据大屏(dashboard 菜单树,admin 仅配置不展示) 3 外链(admin 菜单树,新标签打开完整 URL);建后不可改',
    scope       TINYINT      NOT NULL DEFAULT 2 COMMENT '归属面:1 平台 2 租户;平台面仅平台管理员可见可授;建后不可改',
    path        VARCHAR(500) DEFAULT NULL COMMENT '路由/地址:站内为 admin 路由,数据大屏为 dashboard 路由,外链为完整 URL',
    component   VARCHAR(255) DEFAULT NULL COMMENT '前端组件路径,站内与数据大屏菜单必填',
    perm_code   VARCHAR(100) DEFAULT NULL COMMENT '权限码:目录与外链为空,其余菜单/按钮必填;全局唯一含已删记录,建后不可改',
    icon        VARCHAR(100) DEFAULT NULL COMMENT '图标名,目录与菜单使用',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '同级显示顺序',
    visible     TINYINT      NOT NULL DEFAULT 0 COMMENT '显示:0 显示 1 隐藏;只影响导航渲染,按钮固定 0',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 正常 1 停用;停用后该节点及子树退出菜单树与权限解析',
    is_system   TINYINT      NOT NULL DEFAULT 0 COMMENT '内置节点:0 否 1 是;内置节点不可删、不可停用,种子目录一律为 1',
    remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (menu_id),
    UNIQUE KEY uk_menu_perm_code (perm_code),
    KEY idx_menu_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '菜单:全局目录一份,平台维护,租户只授不建;admin 与 dashboard 按 target 各取各树;租户拦截器 ignore 表';

CREATE TABLE sys_dict_type (
    dict_id     BIGINT       NOT NULL AUTO_INCREMENT COMMENT '字典类型ID',
    tenant_id   BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID,拦截器自动填充',
    dict_name   VARCHAR(100) NOT NULL COMMENT '字典名称,界面展示用',
    dict_type   VARCHAR(100) NOT NULL COMMENT '字典类型标识,程序按此取值;租户内唯一含已删记录,建后不可改',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 正常 1 停用;停用后该类型下数据不可取',
    remark      VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by   BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (dict_id),
    UNIQUE KEY uk_dict_type (tenant_id, dict_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '字典类型:租户各自维护,无平台内置与并集;租户拦截器生效';

CREATE TABLE sys_dict_data (
    dict_data_id BIGINT       NOT NULL AUTO_INCREMENT COMMENT '字典数据ID',
    tenant_id    BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID,拦截器自动填充',
    dict_id      BIGINT       NOT NULL COMMENT '所属字典类型ID,关联 sys_dict_type.dict_id',
    dict_label   VARCHAR(100) NOT NULL COMMENT '字典标签,界面展示用',
    dict_value   VARCHAR(100) NOT NULL COMMENT '字典值,业务落库用;同一类型内唯一含已删记录,建后不可改',
    sort         INT          NOT NULL DEFAULT 0 COMMENT '同类型内显示顺序',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 正常 1 停用',
    remark       VARCHAR(500) DEFAULT NULL COMMENT '备注',
    create_by    BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by    BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time  DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (dict_data_id),
    UNIQUE KEY uk_dict_data (tenant_id, dict_id, dict_value)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '字典数据:租户内归属某一字典类型,删类型时同事务逻辑删数据;租户拦截器生效';

-- ---------- 关系表:关联即事实,不配审计字段,不加 tenant_id ----------
-- user/role 与 role/menu 两端已被租户或全局目录圈定,经此表关联不会漏出跨租户数据;列入拦截器 ignore 清单

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (user_id, role_id),
    KEY idx_ur_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '用户-角色授权,N:N;租户拦截器 ignore 表';

CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL COMMENT '角色ID,sys_role.role_id',
    menu_id BIGINT NOT NULL COMMENT '菜单ID,sys_menu.menu_id',
    PRIMARY KEY (role_id, menu_id),
    KEY idx_rm_menu (menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色-菜单授权,N:N;纯关系表,整包替换物理删改;租户拦截器 ignore 表';

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
    login_type VARCHAR(20)  NOT NULL DEFAULT 'password' COMMENT '登录方式编码:password/sms,后续 feishu/wecom/dingtalk',
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

-- ---------- 通知:消息主体与收件行租户托管,出站投递为队列状态表 ----------

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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知出站投递记录,异步线程写入;租户拦截器 ignore 表';

-- ---------- 第三方身份绑定:一行=厂商身份x平台账号,登录查询键(source,open_id) ----------
-- 同一 (source,open_id) 允许多行(多租户撞号,登录走 pickKey 二段式);唯一键挡并发首绑的重复行
-- 不设软删列:无解绑功能,错绑物理删行

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

-- =========================================================
-- 内置种子:默认租户 + admin 账号 + 全局菜单目录 + 平台面授权
-- 新租户开通不靠迁移脚本:由平台管理功能在运行时按本模板复制,且不写 sys_menu、不复制目录
-- 目录内节点全部 is_system=1;现有页面均为站内菜单,target 走默认 1
-- 授权按 A 语义:租户面(scope=2)隐式全量,不落库;只显式授平台面(scope=1)节点
-- =========================================================

-- ---------- 默认租户与初始组织 ----------
INSERT INTO sys_tenant (tenant_id, tenant_code, name, status, create_time)
VALUES (1, 'default', '默认租户', 0, NOW());

INSERT INTO sys_org (org_id, tenant_id, parent_id, name, ancestors, sort, create_time)
VALUES (1, 1, 0, 'Snail', '0', 0, NOW());

-- ---------- 内置角色与超级管理员 ----------
INSERT INTO sys_role (role_id, tenant_id, role_name, role_code, data_scope, is_system, sort, create_time) VALUES
(1, 1, '超级管理员', 'admin',  1, 1, 1, NOW()),
(2, 1, '普通角色',   'common', 4, 1, 2, NOW());

INSERT INTO sys_user (user_id, tenant_id, username, nickname, password, org_id, status, create_time)
VALUES (1, 1, 'admin', '超级管理员', '${seedAdminPassword}', 1, 0, NOW());

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- ---------- 全局菜单目录 ----------
INSERT INTO sys_menu
(menu_id, parent_id, menu_name, menu_type, scope, path, component, perm_code, icon, sort, is_system, create_time)
VALUES
-- 租户面(scope=2)
(1,    0,   '系统管理',     1, 2, '/system',          NULL,                     NULL,                         'Setting',        1, 1, NOW()),
(100,  1,   '用户管理',     2, 2, '/system/user',     'system/user/index',      'system:user:list',           'User',           1, 1, NOW()),
(1001, 100, '新增用户',     3, 2, NULL,               NULL,                     'system:user:create',         NULL,             1, 1, NOW()),
(1002, 100, '修改用户',     3, 2, NULL,               NULL,                     'system:user:update',         NULL,             2, 1, NOW()),
(1003, 100, '删除用户',     3, 2, NULL,               NULL,                     'system:user:delete',         NULL,             3, 1, NOW()),
(1004, 100, '重置密码',     3, 2, NULL,               NULL,                     'system:user:reset-password', NULL,             4, 1, NOW()),
(1005, 100, '分配角色',     3, 2, NULL,               NULL,                     'system:user:assign-role',    NULL,             5, 1, NOW()),
(1006, 100, '强制下线',     3, 2, NULL,               NULL,                     'system:user:kick',           NULL,             6, 1, NOW()),
(110,  1,   '角色管理',     2, 2, '/system/role',     'system/role/index',      'system:role:list',           'UserFilled',     2, 1, NOW()),
(1101, 110, '新增角色',     3, 2, NULL,               NULL,                     'system:role:create',         NULL,             1, 1, NOW()),
(1102, 110, '修改角色',     3, 2, NULL,               NULL,                     'system:role:update',         NULL,             2, 1, NOW()),
(1103, 110, '删除角色',     3, 2, NULL,               NULL,                     'system:role:delete',         NULL,             3, 1, NOW()),
(1104, 110, '分配菜单',     3, 2, NULL,               NULL,                     'system:role:assign-menu',    NULL,             4, 1, NOW()),
(1105, 110, '平台授权',     3, 1, NULL,               NULL,                     'platform:role:assign-menu',  NULL,             5, 1, NOW()),
(120,  1,   '组织管理',     2, 2, '/system/org',      'system/org/index',       'system:org:list',            'OfficeBuilding', 3, 1, NOW()),
(1201, 120, '新增组织',     3, 2, NULL,               NULL,                     'system:org:create',          NULL,             1, 1, NOW()),
(1202, 120, '修改组织',     3, 2, NULL,               NULL,                     'system:org:update',          NULL,             2, 1, NOW()),
(1203, 120, '删除组织',     3, 2, NULL,               NULL,                     'system:org:delete',          NULL,             3, 1, NOW()),
(130,  1,   '字典管理',     2, 2, '/system/dict',     'system/dict/index',      'system:dict:list',           'Collection',     4, 1, NOW()),
(1301, 130, '新增字典',     3, 2, NULL,               NULL,                     'system:dict:create',         NULL,             1, 1, NOW()),
(1302, 130, '修改字典',     3, 2, NULL,               NULL,                     'system:dict:update',         NULL,             2, 1, NOW()),
(1303, 130, '删除字典',     3, 2, NULL,               NULL,                     'system:dict:delete',         NULL,             3, 1, NOW()),
(140,  1,   '登录日志',     2, 2, '/system/loginlog', 'system/loginlog/index',  'system:loginlog:list',       'Key',            5, 1, NOW()),
(1401, 140, '导出登录日志', 3, 2, NULL,               NULL,                     'system:loginlog:export',     NULL,             1, 1, NOW()),
(1402, 140, '跨租户查看',   3, 1, NULL,               NULL,                     'platform:loginlog:all',      NULL,             2, 1, NOW()),
(150,  1,   '操作日志',     2, 2, '/system/operlog',  'system/operlog/index',   'system:operlog:list',        'Document',       6, 1, NOW()),
(1501, 150, '导出操作日志', 3, 2, NULL,               NULL,                     'system:operlog:export',      NULL,             1, 1, NOW()),
(1502, 150, '跨租户查看',   3, 1, NULL,               NULL,                     'platform:operlog:all',       NULL,             2, 1, NOW()),
(160,  1,   '通知中心',     2, 2, '/system/notify',   'system/notify/index',    'system:notify:list',         'Bell',           7, 1, NOW()),
(170,  1,   '在线用户',     2, 2, '/system/online',   'system/online/index',    'system:online:list',         'User',           8, 1, NOW()),
(1701, 170, '强制下线',     3, 2, NULL,               NULL,                     'system:online:kick',         NULL,             1, 1, NOW()),
-- 平台面(scope=1)
(2,    0,   '平台管理',     1, 1, '/platform',        NULL,                     NULL,                         'Platform',       2, 1, NOW()),
(200,  2,   '租户管理',     2, 1, '/platform/tenant', 'platform/tenant/index',  'platform:tenant:list',       'School',         1, 1, NOW()),
(2001, 200, '新增租户',     3, 1, NULL,               NULL,                     'platform:tenant:create',     NULL,             1, 1, NOW()),
(2002, 200, '修改租户',     3, 1, NULL,               NULL,                     'platform:tenant:update',     NULL,             2, 1, NOW()),
(210,  2,   '菜单管理',     2, 1, '/platform/menu',   'platform/menu/index',    'platform:menu:list',         'Menu',           2, 1, NOW()),
(2101, 210, '新增菜单',     3, 1, NULL,               NULL,                     'platform:menu:create',       NULL,             1, 1, NOW()),
(2102, 210, '修改菜单',     3, 1, NULL,               NULL,                     'platform:menu:update',       NULL,             2, 1, NOW()),
(2103, 210, '删除菜单',     3, 1, NULL,               NULL,                     'platform:menu:delete',       NULL,             3, 1, NOW()),
(220,  2,   '公告管理',     2, 1, '/platform/notify', 'platform/notify/index',  'platform:notify:list',       'Message',        3, 1, NOW()),
(2201, 220, '发送公告',     3, 1, NULL,               NULL,                     'platform:notify:send',       NULL,             1, 1, NOW());

-- ---------- 默认租户 admin 的平台面授权 ----------
-- A 语义下租户面隐式全量,这里只落 scope=1 的 13 个节点(2/200/2001/2002/210/2101/2102/2103/1105/1402/1502/220/2201)
-- 谓词按 m.scope=1 一刀切,后建的域种子(V2/V3/V4)新增平台节点时无需改本段,各自文末补授即可
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.tenant_id = 1
  AND r.role_code = 'admin'
  AND r.deleted = 0
  AND m.scope = 1
  AND m.deleted = 0;

