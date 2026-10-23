-- =========================================================
-- 菜单/权限码 + 字典建表
-- sys_menu 为全局目录:平台维护一份,租户只经 sys_role_menu 授权,不持有副本
-- target:1 站内(admin 菜单树) 2 数据大屏(dashboard 菜单树,admin 只配置) 3 外链(admin 菜单树,新标签)
-- 权限码三段式 <面>:<模块>:<动作>;面随 scope 走:1 平台用 platform:,2 租户用 system:
-- 字典租户各自维护:无平台内置、无并集、无默认种子
-- 唯一键口径同 V1:被逻辑删除记录的键值不释放、不可复用
-- 目录与外链 perm_code 为 NULL;MySQL 唯一索引允许多个 NULL
-- 拦截器:sys_menu / sys_role_menu 无 tenant_id,须进 ignore 清单;字典两表保持拦截
-- =========================================================

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

CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL COMMENT '角色ID,sys_role.role_id',
    menu_id BIGINT NOT NULL COMMENT '菜单ID,sys_menu.menu_id',
    PRIMARY KEY (role_id, menu_id),
    KEY idx_rm_menu (menu_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色-菜单授权,N:N;纯关系表,整包替换物理删改;租户拦截器 ignore 表';

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
