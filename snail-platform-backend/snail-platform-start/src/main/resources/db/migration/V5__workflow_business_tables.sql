-- =========================================================
-- 流程中心业务表(Flowable 集成 P2)
-- 口径来源:docs/flowable-integration.md §4
-- 引擎表(ACT_*/FLW_*)归 V4,本文件只建平台侧的模型/模板/留痕三张业务表;
-- 引擎不认识这三张表,平台也不 join ACT_*,两侧靠 deployment_id/process_definition_id/business_key 关联
-- 全局 vs 租户:sys_workflow_template 是平台维护的全局目录(同 sys_menu 口径),进 ignore 清单;
--               sys_workflow_model 归租户所有,uk 落在 (tenant_id, model_key),租户拦截器生效
-- 键值终身占用:model_key 建后不可改,逻辑删除后仍占唯一键,重建同名流程必须换 key
-- 留痕不可变:sys_workflow_task_record 只增,不配审计四件套与逻辑删除(给假删除字段就是给篡改留门)
-- =========================================================

CREATE TABLE sys_workflow_template (
    template_id  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '模板ID',
    template_key VARCHAR(64)  NOT NULL COMMENT '模板标识,建后不可改,全局唯一含已删记录;租户侧按它指代模板',
    name         VARCHAR(100) NOT NULL COMMENT '模板名称,界面展示用',
    category     VARCHAR(50)  DEFAULT NULL COMMENT '分类,展示与筛选用,不做程序取值',
    remark       VARCHAR(500) DEFAULT NULL COMMENT '备注:适用场景说明',
    bpmn_xml     MEDIUMTEXT   NOT NULL COMMENT 'BPMN XML 原文,租户建模型时作为初始草稿拷出;上架前必须过 BPMN 白名单校验',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 上架(租户可选) 1 停用(不出现在模板列表)',
    create_by    BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by    BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time  DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删',
    PRIMARY KEY (template_id),
    UNIQUE KEY uk_wft_key (template_key),
    KEY idx_wft_status (status, template_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '流程模板:平台维护的全局目录一份,租户只读不可改;租户拦截器 ignore 表';

CREATE TABLE sys_workflow_model (
    model_id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '模型ID',
    tenant_id              BIGINT       NOT NULL DEFAULT 1 COMMENT '租户ID,拦截器自动填充',
    model_key              VARCHAR(64)  NOT NULL COMMENT '流程标识,即 BPMN 里的 process id;建后不可改,租户内唯一含已删记录',
    name                   VARCHAR(100) NOT NULL COMMENT '流程名称,界面展示用',
    category               VARCHAR(50)  DEFAULT NULL COMMENT '分类,展示与筛选用',
    remark                 VARCHAR(500) DEFAULT NULL COMMENT '备注',
    status                 TINYINT      NOT NULL DEFAULT 0 COMMENT '状态:0 草稿 1 已发布 2 停用;停用只停"发起",在途实例不受影响',
    draft_xml              MEDIUMTEXT   DEFAULT NULL COMMENT '设计器草稿原文,与已发布版本分离:改草稿不影响在途实例',
    template_id            BIGINT       DEFAULT NULL COMMENT '来源模板ID,仅作溯源展示,删模板不回写本列',
    deployment_id          VARCHAR(64)  DEFAULT NULL COMMENT '当前发布指针:最近一次部署的引擎 deployment id',
    process_definition_id  VARCHAR(64)  DEFAULT NULL COMMENT '当前发布指针:引擎流程定义 id,发起按它定位版本',
    deployed_version       INT          DEFAULT NULL COMMENT '当前发布指针:引擎侧版本号,同 key 递增',
    published_by           BIGINT       DEFAULT NULL COMMENT '最后一次发布人用户ID',
    publish_time           DATETIME     DEFAULT NULL COMMENT '最后一次发布时间',
    create_by              BIGINT       DEFAULT NULL COMMENT '创建人用户ID',
    create_time            DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by              BIGINT       DEFAULT NULL COMMENT '修改人用户ID',
    update_time            DATETIME     DEFAULT NULL COMMENT '修改时间',
    deleted                TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0 正常 1 已删;删模型只删平台侧记录,引擎历史与已发布定义保留',
    PRIMARY KEY (model_id),
    UNIQUE KEY uk_wm_key (tenant_id, model_key),
    KEY idx_wm_tenant_status (tenant_id, status, model_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '流程模型:归租户所有,草稿与发布指针同行共存;租户拦截器生效';

CREATE TABLE sys_workflow_task_record (
    record_id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '留痕ID',
    tenant_id           BIGINT        NOT NULL DEFAULT 1 COMMENT '租户ID,拦截器自动填充(留痕只在请求线程或事务提交回调里写,两种都有租户上下文)',
    process_instance_id VARCHAR(64)   NOT NULL COMMENT '流程实例ID,引擎侧坐标',
    task_id             VARCHAR(64)   DEFAULT NULL COMMENT '引擎任务ID;发起与撤销没有对应任务,此列为空',
    task_definition_key VARCHAR(64)   DEFAULT NULL COMMENT '节点标识(BPMN userTask id)',
    task_name           VARCHAR(100)  DEFAULT NULL COMMENT '节点名称快照,改名不回溯',
    business_type       VARCHAR(50)   DEFAULT NULL COMMENT '业务域标签,与 business_key 组成唯一关联键',
    business_key        VARCHAR(64)   DEFAULT NULL COMMENT '业务单号',
    action              VARCHAR(20)   NOT NULL COMMENT '动作:start 发起 claim 签收 approve 通过 reject 驳回 transfer 转办 withdraw 撤销',
    operator_id         BIGINT        DEFAULT NULL COMMENT '操作人用户ID',
    operator_username   VARCHAR(50)   DEFAULT NULL COMMENT '操作人登录名快照:此刻为准,后续改名不回溯',
    operator_nickname   VARCHAR(50)   DEFAULT NULL COMMENT '操作人显示名快照',
    operator_org_id     BIGINT        DEFAULT NULL COMMENT '操作人组织ID快照',
    operator_org_name   VARCHAR(100)  DEFAULT NULL COMMENT '操作人组织名称快照',
    target_user_id      BIGINT        DEFAULT NULL COMMENT '转办目标用户ID,仅 transfer 有值',
    target_user_nickname VARCHAR(50)  DEFAULT NULL COMMENT '转办目标显示名快照',
    comment             VARCHAR(500)  DEFAULT NULL COMMENT '审批意见/撤销原因',
    created_at          DATETIME(3)   NOT NULL COMMENT '操作时刻(毫秒),平台视角审批台账的时间轴',
    PRIMARY KEY (record_id),
    KEY idx_wtr_instance (tenant_id, process_instance_id, created_at),
    KEY idx_wtr_business (tenant_id, business_type, business_key),
    KEY idx_wtr_operator (tenant_id, operator_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审批留痕:平台视角的审批台账,只插入不更新不删除;不替代引擎 ACT_HI_*';
