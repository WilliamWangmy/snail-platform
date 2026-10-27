-- =========================================================
-- 流程中心建表(Flowable 集成):引擎表 + 平台侧三张业务表,一个文件建齐
-- 引擎表归 Flyway 全权:引擎侧 database-schema-update=false,本文件是 ACT_*/FLW_* 的唯一建表来源;
-- 官方脚本按 common -> engine -> history -> eventregistry 顺序原样拼接,字符集保持 utf8/utf8_bin
-- (不与官方升级脚本产生差异),升级只追加新迁移、不改本段
-- eventregistry 段不可省:process starter 传递带入事件注册引擎,装配时校验其版本行,缺表即启动失败
-- 平台业务表另用 utf8mb4 与系统表同风格;引擎不认识这三张表,平台也不 join ACT_*,
-- 两侧靠 deployment_id / process_definition_id / business_key 关联
-- 全局 vs 租户:sys_workflow_template 是平台维护的全局目录(同 sys_menu 口径),进 ignore 清单;
--               sys_workflow_model 归租户所有,uk 落在 (tenant_id, model_key),租户拦截器生效
-- 键值终身占用:model_key 建后不可改,逻辑删除后仍占唯一键,重建同名流程必须换 key
-- 留痕不可变:sys_workflow_task_record 只增,不配审计四件套与逻辑删除(给假删除字段就是给篡改留门)
-- =========================================================

-- ---------- 引擎官方 DDL(原样拼接;段内四行 ACT_GE_PROPERTY 版本行是官方建表脚本自带,不是平台种子) ----------

create table ACT_GE_PROPERTY (
    NAME_ varchar(64),
    VALUE_ varchar(300),
    REV_ integer,
    primary key (NAME_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_GE_BYTEARRAY (
    ID_ varchar(64),
    REV_ integer,
    NAME_ varchar(255),
    DEPLOYMENT_ID_ varchar(64),
    BYTES_ LONGBLOB,
    GENERATED_ TINYINT,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

insert into ACT_GE_PROPERTY
values ('common.schema.version', '8.0.0.0', 1);

insert into ACT_GE_PROPERTY
values ('next.dbid', '1', 1);


create table ACT_RU_ENTITYLINK (
    ID_ varchar(64),
    REV_ integer,
    CREATE_TIME_ datetime(3),
    LINK_TYPE_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    PARENT_ELEMENT_ID_ varchar(255),
    REF_SCOPE_ID_ varchar(255),
    REF_SCOPE_TYPE_ varchar(255),
    REF_SCOPE_DEFINITION_ID_ varchar(255),
    ROOT_SCOPE_ID_ varchar(255),
    ROOT_SCOPE_TYPE_ varchar(255),
    HIERARCHY_TYPE_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_ENT_LNK_SCOPE on ACT_RU_ENTITYLINK(SCOPE_ID_, SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_ENT_LNK_REF_SCOPE on ACT_RU_ENTITYLINK(REF_SCOPE_ID_, REF_SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_ENT_LNK_ROOT_SCOPE on ACT_RU_ENTITYLINK(ROOT_SCOPE_ID_, ROOT_SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_ENT_LNK_SCOPE_DEF on ACT_RU_ENTITYLINK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_, LINK_TYPE_);

create table ACT_HI_ENTITYLINK (
    ID_ varchar(64),
    LINK_TYPE_ varchar(255),
    CREATE_TIME_ datetime(3),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    PARENT_ELEMENT_ID_ varchar(255),
    REF_SCOPE_ID_ varchar(255),
    REF_SCOPE_TYPE_ varchar(255),
    REF_SCOPE_DEFINITION_ID_ varchar(255),
    ROOT_SCOPE_ID_ varchar(255),
    ROOT_SCOPE_TYPE_ varchar(255),
    HIERARCHY_TYPE_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_HI_ENT_LNK_SCOPE on ACT_HI_ENTITYLINK(SCOPE_ID_, SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_HI_ENT_LNK_REF_SCOPE on ACT_HI_ENTITYLINK(REF_SCOPE_ID_, REF_SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_HI_ENT_LNK_ROOT_SCOPE on ACT_HI_ENTITYLINK(ROOT_SCOPE_ID_, ROOT_SCOPE_TYPE_, LINK_TYPE_);
create index ACT_IDX_HI_ENT_LNK_SCOPE_DEF on ACT_HI_ENTITYLINK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_, LINK_TYPE_);


create table ACT_RU_IDENTITYLINK (
    ID_ varchar(64),
    REV_ integer,
    GROUP_ID_ varchar(255),
    TYPE_ varchar(255),
    USER_ID_ varchar(255),
    TASK_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_IDENT_LNK_USER on ACT_RU_IDENTITYLINK(USER_ID_);
create index ACT_IDX_IDENT_LNK_GROUP on ACT_RU_IDENTITYLINK(GROUP_ID_);
create index ACT_IDX_IDENT_LNK_SCOPE on ACT_RU_IDENTITYLINK(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_IDENT_LNK_SUB_SCOPE on ACT_RU_IDENTITYLINK(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_IDENT_LNK_SCOPE_DEF on ACT_RU_IDENTITYLINK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);

create table ACT_HI_IDENTITYLINK (
    ID_ varchar(64),
    GROUP_ID_ varchar(255),
    TYPE_ varchar(255),
    USER_ID_ varchar(255),
    TASK_ID_ varchar(64),
    CREATE_TIME_ datetime(3),
    PROC_INST_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_HI_IDENT_LNK_USER on ACT_HI_IDENTITYLINK(USER_ID_);
create index ACT_IDX_HI_IDENT_LNK_SCOPE on ACT_HI_IDENTITYLINK(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_IDENT_LNK_SUB_SCOPE on ACT_HI_IDENTITYLINK(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_IDENT_LNK_SCOPE_DEF on ACT_HI_IDENTITYLINK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);


create table ACT_RU_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    LOCK_EXP_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RU_TIMER_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    LOCK_EXP_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RU_SUSPENDED_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RU_DEADLETTER_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RU_HISTORY_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    LOCK_EXP_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    ADV_HANDLER_CFG_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    SCOPE_TYPE_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RU_EXTERNAL_JOB (
    ID_ varchar(64) NOT NULL,
    REV_ integer,
    CATEGORY_ varchar(255),
    TYPE_ varchar(255) NOT NULL,
    LOCK_EXP_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    EXCLUSIVE_ boolean,
    EXECUTION_ID_ varchar(64),
    PROCESS_INSTANCE_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    ELEMENT_ID_ varchar(255),
    ELEMENT_NAME_ varchar(255),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    CORRELATION_ID_ varchar(255),
    RETRIES_ integer,
    EXCEPTION_STACK_ID_ varchar(64),
    EXCEPTION_MSG_ varchar(4000),
    DUEDATE_ timestamp(3) NULL,
    REPEAT_ varchar(255),
    HANDLER_TYPE_ varchar(255),
    HANDLER_CFG_ varchar(4000),
    CUSTOM_VALUES_ID_ varchar(64),
    CREATE_TIME_ timestamp(3) NULL,
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_JOB_EXCEPTION_STACK_ID on ACT_RU_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_JOB_CUSTOM_VALUES_ID on ACT_RU_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_JOB_CORRELATION_ID on ACT_RU_JOB(CORRELATION_ID_);

create index ACT_IDX_TIMER_JOB_EXCEPTION_STACK_ID on ACT_RU_TIMER_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_TIMER_JOB_CUSTOM_VALUES_ID on ACT_RU_TIMER_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_TIMER_JOB_CORRELATION_ID on ACT_RU_TIMER_JOB(CORRELATION_ID_);
create index ACT_IDX_TIMER_JOB_DUEDATE on ACT_RU_TIMER_JOB(DUEDATE_); 

create index ACT_IDX_SUSPENDED_JOB_EXCEPTION_STACK_ID on ACT_RU_SUSPENDED_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_SUSPENDED_JOB_CUSTOM_VALUES_ID on ACT_RU_SUSPENDED_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_SUSPENDED_JOB_CORRELATION_ID on ACT_RU_SUSPENDED_JOB(CORRELATION_ID_);

create index ACT_IDX_DEADLETTER_JOB_EXCEPTION_STACK_ID on ACT_RU_DEADLETTER_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_DEADLETTER_JOB_CUSTOM_VALUES_ID on ACT_RU_DEADLETTER_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_DEADLETTER_JOB_CORRELATION_ID on ACT_RU_DEADLETTER_JOB(CORRELATION_ID_);

create index ACT_IDX_EXTERNAL_JOB_EXCEPTION_STACK_ID on ACT_RU_EXTERNAL_JOB(EXCEPTION_STACK_ID_);
create index ACT_IDX_EXTERNAL_JOB_CUSTOM_VALUES_ID on ACT_RU_EXTERNAL_JOB(CUSTOM_VALUES_ID_);
create index ACT_IDX_EXTERNAL_JOB_CORRELATION_ID on ACT_RU_EXTERNAL_JOB(CORRELATION_ID_);

alter table ACT_RU_JOB
    add constraint ACT_FK_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_JOB
    add constraint ACT_FK_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_TIMER_JOB
    add constraint ACT_FK_TIMER_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_TIMER_JOB
    add constraint ACT_FK_TIMER_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_SUSPENDED_JOB
    add constraint ACT_FK_SUSPENDED_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_SUSPENDED_JOB
    add constraint ACT_FK_SUSPENDED_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_DEADLETTER_JOB
    add constraint ACT_FK_DEADLETTER_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_DEADLETTER_JOB
    add constraint ACT_FK_DEADLETTER_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_EXTERNAL_JOB
    add constraint ACT_FK_EXTERNAL_JOB_EXCEPTION
    foreign key (EXCEPTION_STACK_ID_)
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RU_EXTERNAL_JOB
    add constraint ACT_FK_EXTERNAL_JOB_CUSTOM_VALUES
    foreign key (CUSTOM_VALUES_ID_)
    references ACT_GE_BYTEARRAY (ID_);

create index ACT_IDX_JOB_SCOPE on ACT_RU_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_JOB_SUB_SCOPE on ACT_RU_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_JOB_SCOPE_DEF on ACT_RU_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);

create index ACT_IDX_TJOB_SCOPE on ACT_RU_TIMER_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_TJOB_SUB_SCOPE on ACT_RU_TIMER_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_TJOB_SCOPE_DEF on ACT_RU_TIMER_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_); 

create index ACT_IDX_SJOB_SCOPE on ACT_RU_SUSPENDED_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_SJOB_SUB_SCOPE on ACT_RU_SUSPENDED_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_SJOB_SCOPE_DEF on ACT_RU_SUSPENDED_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);   

create index ACT_IDX_DJOB_SCOPE on ACT_RU_DEADLETTER_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_DJOB_SUB_SCOPE on ACT_RU_DEADLETTER_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_DJOB_SCOPE_DEF on ACT_RU_DEADLETTER_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_); 

create index ACT_IDX_EJOB_SCOPE on ACT_RU_EXTERNAL_JOB(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_EJOB_SUB_SCOPE on ACT_RU_EXTERNAL_JOB(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_EJOB_SCOPE_DEF on ACT_RU_EXTERNAL_JOB(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);

create table FLW_RU_BATCH (
    ID_ varchar(64) not null,
    REV_ integer,
    TYPE_ varchar(64) not null,
    SEARCH_KEY_ varchar(255),
    SEARCH_KEY2_ varchar(255),
    CREATE_TIME_ datetime(3) not null,
    COMPLETE_TIME_ datetime(3),
    STATUS_ varchar(255),
    BATCH_DOC_ID_ varchar(64),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table FLW_RU_BATCH_PART (
    ID_ varchar(64) not null,
    REV_ integer,
    BATCH_ID_ varchar(64),
    TYPE_ varchar(64) not null,
    SCOPE_ID_ varchar(64),
    SUB_SCOPE_ID_ varchar(64),
    SCOPE_TYPE_ varchar(64),
    SEARCH_KEY_ varchar(255),
    SEARCH_KEY2_ varchar(255),
    CREATE_TIME_ datetime(3) not null,
    COMPLETE_TIME_ datetime(3),
    STATUS_ varchar(255),
    RESULT_DOC_ID_ varchar(64),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index FLW_IDX_BATCH_PART on FLW_RU_BATCH_PART(BATCH_ID_);

alter table FLW_RU_BATCH_PART
    add constraint FLW_FK_BATCH_PART_PARENT
    foreign key (BATCH_ID_)
    references FLW_RU_BATCH (ID_);

create table ACT_RU_TASK (
    ID_ varchar(64),
    REV_ integer,
    EXECUTION_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    TASK_DEF_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    PROPAGATED_STAGE_INST_ID_ varchar(255),
    STATE_ varchar(255),
    NAME_ varchar(255),
    PARENT_TASK_ID_ varchar(64),
    DESCRIPTION_ varchar(4000),
    TASK_DEF_KEY_ varchar(255),
    OWNER_ varchar(255),
    ASSIGNEE_ varchar(255),
    DELEGATION_ varchar(64),
    PRIORITY_ integer,
    CREATE_TIME_ timestamp(3) NULL,
    IN_PROGRESS_TIME_ datetime(3),
    IN_PROGRESS_STARTED_BY_ varchar(255),
    CLAIM_TIME_ datetime(3),
    CLAIMED_BY_ varchar(255),
    SUSPENDED_TIME_ datetime(3),
    SUSPENDED_BY_ varchar(255),
    IN_PROGRESS_DUE_DATE_ datetime(3),
    DUE_DATE_ datetime(3),
    CATEGORY_ varchar(255),
    SUSPENSION_STATE_ integer,
    TENANT_ID_ varchar(255) default '',
    FORM_KEY_ varchar(255),
    IS_COUNT_ENABLED_ TINYINT,
    VAR_COUNT_ integer,
    ID_LINK_COUNT_ integer,
    SUB_TASK_COUNT_ integer,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_TASK_CREATE on ACT_RU_TASK(CREATE_TIME_);
create index ACT_IDX_TASK_SCOPE on ACT_RU_TASK(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_TASK_SUB_SCOPE on ACT_RU_TASK(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_TASK_SCOPE_DEF on ACT_RU_TASK(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);

create table ACT_HI_TASKINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_DEF_ID_ varchar(64),
    TASK_DEF_ID_ varchar(64),
    TASK_DEF_KEY_ varchar(255),
    PROC_INST_ID_ varchar(64),
    EXECUTION_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    PROPAGATED_STAGE_INST_ID_ varchar(255),
    STATE_ varchar(255),
    NAME_ varchar(255),
    PARENT_TASK_ID_ varchar(64),
    DESCRIPTION_ varchar(4000),
    OWNER_ varchar(255),
    ASSIGNEE_ varchar(255),
    START_TIME_ datetime(3) not null,
    IN_PROGRESS_TIME_ datetime(3),
    IN_PROGRESS_STARTED_BY_ varchar(255),
    CLAIM_TIME_ datetime(3),
    CLAIMED_BY_ varchar(255),
    SUSPENDED_TIME_ datetime(3),
    SUSPENDED_BY_ varchar(255),
    END_TIME_ datetime(3),
    COMPLETED_BY_ varchar(255),
    DURATION_ bigint,
    DELETE_REASON_ varchar(4000),
    PRIORITY_ integer,
    IN_PROGRESS_DUE_DATE_ datetime(3),
    DUE_DATE_ datetime(3),
    FORM_KEY_ varchar(255),
    CATEGORY_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    LAST_UPDATED_TIME_ datetime(3),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_HI_TSK_LOG (
    ID_ bigint auto_increment,
    TYPE_ varchar(64),
    TASK_ID_ varchar(64) not null,
    TIME_STAMP_ timestamp(3) not null,
    USER_ID_ varchar(255),
    DATA_ varchar(4000),
    EXECUTION_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SCOPE_DEFINITION_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_HI_TASK_SCOPE on ACT_HI_TASKINST(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_TASK_SUB_SCOPE on ACT_HI_TASKINST(SUB_SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_TASK_SCOPE_DEF on ACT_HI_TASKINST(SCOPE_DEFINITION_ID_, SCOPE_TYPE_);
create index ACT_IDX_ACT_HI_TSK_LOG_TASK on ACT_HI_TSK_LOG(TASK_ID_);


create table ACT_RU_VARIABLE (
    ID_ varchar(64) not null,
    REV_ integer,
    TYPE_ varchar(255) not null,
    NAME_ varchar(255) not null,
    EXECUTION_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    TASK_ID_ varchar(64),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    BYTEARRAY_ID_ varchar(64),
    DOUBLE_ double,
    LONG_ bigint,
    TEXT_ varchar(4000),
    TEXT2_ varchar(4000),
    META_INFO_ varchar(4000),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_RU_VAR_SCOPE_ID_TYPE on ACT_RU_VARIABLE(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_RU_VAR_SUB_ID_TYPE on ACT_RU_VARIABLE(SUB_SCOPE_ID_, SCOPE_TYPE_);

alter table ACT_RU_VARIABLE 
    add constraint ACT_FK_VAR_BYTEARRAY 
    foreign key (BYTEARRAY_ID_) 
    references ACT_GE_BYTEARRAY (ID_);

create table ACT_HI_VARINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_INST_ID_ varchar(64),
    EXECUTION_ID_ varchar(64),
    TASK_ID_ varchar(64),
    NAME_ varchar(255) not null,
    VAR_TYPE_ varchar(100),
    SCOPE_ID_ varchar(255),
    SUB_SCOPE_ID_ varchar(255),
    SCOPE_TYPE_ varchar(255),
    BYTEARRAY_ID_ varchar(64),
    DOUBLE_ double,
    LONG_ bigint,
    TEXT_ varchar(4000),
    TEXT2_ varchar(4000),
    META_INFO_ varchar(4000),
    CREATE_TIME_ datetime(3),
    LAST_UPDATED_TIME_ datetime(3),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_HI_PROCVAR_NAME_TYPE on ACT_HI_VARINST(NAME_, VAR_TYPE_);
create index ACT_IDX_HI_VAR_SCOPE_ID_TYPE on ACT_HI_VARINST(SCOPE_ID_, SCOPE_TYPE_);
create index ACT_IDX_HI_VAR_SUB_ID_TYPE on ACT_HI_VARINST(SUB_SCOPE_ID_, SCOPE_TYPE_);


create table ACT_RU_EVENT_SUBSCR (
    ID_ varchar(64) not null,
    REV_ integer,
    EVENT_TYPE_ varchar(255) not null,
    EVENT_NAME_ varchar(255),
    EXECUTION_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    ACTIVITY_ID_ varchar(64),
    CONFIGURATION_ varchar(255),
    CREATED_ timestamp(3) not null DEFAULT CURRENT_TIMESTAMP(3),
    PROC_DEF_ID_ varchar(64),
    SUB_SCOPE_ID_ varchar(64),
    SCOPE_ID_ varchar(64),
    SCOPE_DEFINITION_ID_ varchar(64),
    SCOPE_DEFINITION_KEY_ varchar(255),
    SCOPE_TYPE_ varchar(64),
    LOCK_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_EVENT_SUBSCR_CONFIG_ on ACT_RU_EVENT_SUBSCR(CONFIGURATION_);
create index ACT_IDX_EVENT_SUBSCR_EXEC_ID on ACT_RU_EVENT_SUBSCR(EXECUTION_ID_);
create index ACT_IDX_EVENT_SUBSCR_PROC_ID on ACT_RU_EVENT_SUBSCR(PROC_INST_ID_);
create index ACT_IDX_EVENT_SUBSCR_SCOPEREF_ on ACT_RU_EVENT_SUBSCR(SCOPE_ID_, SCOPE_TYPE_);

create table ACT_RE_DEPLOYMENT (
    ID_ varchar(64),
    NAME_ varchar(255),
    CATEGORY_ varchar(255),
    KEY_ varchar(255),
    TENANT_ID_ varchar(255) default '',
    DEPLOY_TIME_ timestamp(3) NULL,
    DERIVED_FROM_ varchar(64),
    DERIVED_FROM_ROOT_ varchar(64),
    PARENT_DEPLOYMENT_ID_ varchar(255),
    ENGINE_VERSION_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RE_MODEL (
    ID_ varchar(64) not null,
    REV_ integer,
    NAME_ varchar(255),
    KEY_ varchar(255),
    CATEGORY_ varchar(255),
    CREATE_TIME_ timestamp(3) null,
    LAST_UPDATE_TIME_ timestamp(3) null,
    VERSION_ integer,
    META_INFO_ varchar(4000),
    DEPLOYMENT_ID_ varchar(64),
    EDITOR_SOURCE_VALUE_ID_ varchar(64),
    EDITOR_SOURCE_EXTRA_VALUE_ID_ varchar(64),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RU_EXECUTION (
    ID_ varchar(64),
    REV_ integer,
    PROC_INST_ID_ varchar(64),
    BUSINESS_KEY_ varchar(255),
    PARENT_ID_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    SUPER_EXEC_ varchar(64),
    ROOT_PROC_INST_ID_ varchar(64),
    ACT_ID_ varchar(255),
    IS_ACTIVE_ TINYINT,
    IS_CONCURRENT_ TINYINT,
    IS_SCOPE_ TINYINT,
    IS_EVENT_SCOPE_ TINYINT,
    IS_MI_ROOT_ TINYINT,
    SUSPENSION_STATE_ integer,
    CACHED_ENT_STATE_ integer,
    TENANT_ID_ varchar(255) default '',
    NAME_ varchar(255),
    START_ACT_ID_ varchar(255),
    START_TIME_ datetime(3),
    START_USER_ID_ varchar(255),
    LOCK_TIME_ timestamp(3) NULL,
    LOCK_OWNER_ varchar(255),
    IS_COUNT_ENABLED_ TINYINT,
    EVT_SUBSCR_COUNT_ integer, 
    TASK_COUNT_ integer, 
    JOB_COUNT_ integer, 
    TIMER_JOB_COUNT_ integer,
    SUSP_JOB_COUNT_ integer,
    DEADLETTER_JOB_COUNT_ integer,
    EXTERNAL_WORKER_JOB_COUNT_ integer,
    VAR_COUNT_ integer, 
    ID_LINK_COUNT_ integer,
    CALLBACK_ID_ varchar(255),
    CALLBACK_TYPE_ varchar(255),
    REFERENCE_ID_ varchar(255),
    REFERENCE_TYPE_ varchar(255),
    PROPAGATED_STAGE_INST_ID_ varchar(255),
    BUSINESS_STATUS_ varchar(255),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RE_PROCDEF (
    ID_ varchar(64) not null,
    REV_ integer,
    CATEGORY_ varchar(255),
    NAME_ varchar(255),
    KEY_ varchar(255) not null,
    VERSION_ integer not null,
    DEPLOYMENT_ID_ varchar(64),
    RESOURCE_NAME_ varchar(4000),
    DGRM_RESOURCE_NAME_ varchar(4000),
    DESCRIPTION_ varchar(4000),
    HAS_START_FORM_KEY_ TINYINT,
    HAS_GRAPHICAL_NOTATION_ TINYINT,
    SUSPENSION_STATE_ integer,
    TENANT_ID_ varchar(255) default '',
    ENGINE_VERSION_ varchar(255),
    DERIVED_FROM_ varchar(64),
    DERIVED_FROM_ROOT_ varchar(64),
    DERIVED_VERSION_ integer not null default 0,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_EVT_LOG (
    LOG_NR_ bigint auto_increment,
    TYPE_ varchar(64),
    PROC_DEF_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    EXECUTION_ID_ varchar(64),
    TASK_ID_ varchar(64),
    TIME_STAMP_ timestamp(3) not null DEFAULT CURRENT_TIMESTAMP(3),
    USER_ID_ varchar(255),
    DATA_ LONGBLOB,
    LOCK_OWNER_ varchar(255),
    LOCK_TIME_ timestamp(3) null,
    IS_PROCESSED_ tinyint default 0,
    primary key (LOG_NR_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_PROCDEF_INFO (
	ID_ varchar(64) not null,
    PROC_DEF_ID_ varchar(64) not null,
    REV_ integer,
    INFO_JSON_ID_ varchar(64),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_RU_ACTINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_DEF_ID_ varchar(64) not null,
    PROC_INST_ID_ varchar(64) not null,
    EXECUTION_ID_ varchar(64) not null,
    ACT_ID_ varchar(255) not null,
    TASK_ID_ varchar(64),
    CALL_PROC_INST_ID_ varchar(64),
    ACT_NAME_ varchar(255),
    ACT_TYPE_ varchar(255) not null,
    ASSIGNEE_ varchar(255),
    COMPLETED_BY_ varchar(255),
    START_TIME_ datetime(3) not null,
    END_TIME_ datetime(3),
    DURATION_ bigint,
    TRANSACTION_ORDER_ integer,
    DELETE_REASON_ varchar(4000),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create index ACT_IDX_EXEC_BUSKEY on ACT_RU_EXECUTION(BUSINESS_KEY_);
create index ACT_IDC_EXEC_ROOT on ACT_RU_EXECUTION(ROOT_PROC_INST_ID_);
create index ACT_IDX_EXEC_REF_ID_ on ACT_RU_EXECUTION(REFERENCE_ID_);
create index ACT_IDX_VARIABLE_TASK_ID on ACT_RU_VARIABLE(TASK_ID_);
create index ACT_IDX_ATHRZ_PROCEDEF on ACT_RU_IDENTITYLINK(PROC_DEF_ID_);
create index ACT_IDX_INFO_PROCDEF on ACT_PROCDEF_INFO(PROC_DEF_ID_);

create index ACT_IDX_BYTEAR_DEPL on ACT_GE_BYTEARRAY(DEPLOYMENT_ID_);

create index ACT_IDX_RU_ACTI_START on ACT_RU_ACTINST(START_TIME_);
create index ACT_IDX_RU_ACTI_END on ACT_RU_ACTINST(END_TIME_);
create index ACT_IDX_RU_ACTI_PROC on ACT_RU_ACTINST(PROC_INST_ID_);
create index ACT_IDX_RU_ACTI_PROC_ACT on ACT_RU_ACTINST(PROC_INST_ID_, ACT_ID_);
create index ACT_IDX_RU_ACTI_EXEC on ACT_RU_ACTINST(EXECUTION_ID_);
create index ACT_IDX_RU_ACTI_EXEC_ACT on ACT_RU_ACTINST(EXECUTION_ID_, ACT_ID_);
create index ACT_IDX_RU_ACTI_TASK on ACT_RU_ACTINST(TASK_ID_);

alter table ACT_GE_BYTEARRAY
    add constraint ACT_FK_BYTEARR_DEPL 
    foreign key (DEPLOYMENT_ID_) 
    references ACT_RE_DEPLOYMENT (ID_);

alter table ACT_RE_PROCDEF
    add constraint ACT_UNIQ_PROCDEF
    unique (KEY_,VERSION_, DERIVED_VERSION_, TENANT_ID_);
    
alter table ACT_RU_EXECUTION
    add constraint ACT_FK_EXE_PROCINST 
    foreign key (PROC_INST_ID_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade on update cascade;

alter table ACT_RU_EXECUTION
    add constraint ACT_FK_EXE_PARENT 
    foreign key (PARENT_ID_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade;
    
alter table ACT_RU_EXECUTION
    add constraint ACT_FK_EXE_SUPER 
    foreign key (SUPER_EXEC_) 
    references ACT_RU_EXECUTION (ID_) on delete cascade;
    
alter table ACT_RU_EXECUTION
    add constraint ACT_FK_EXE_PROCDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
    
alter table ACT_RU_IDENTITYLINK
    add constraint ACT_FK_TSKASS_TASK 
    foreign key (TASK_ID_) 
    references ACT_RU_TASK (ID_);
    
alter table ACT_RU_IDENTITYLINK
    add constraint ACT_FK_ATHRZ_PROCEDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF(ID_);
    
alter table ACT_RU_IDENTITYLINK
    add constraint ACT_FK_IDL_PROCINST
    foreign key (PROC_INST_ID_) 
    references ACT_RU_EXECUTION (ID_);       
    
alter table ACT_RU_TASK
    add constraint ACT_FK_TASK_EXE
    foreign key (EXECUTION_ID_)
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_TASK
    add constraint ACT_FK_TASK_PROCINST
    foreign key (PROC_INST_ID_)
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_TASK
  	add constraint ACT_FK_TASK_PROCDEF
  	foreign key (PROC_DEF_ID_)
  	references ACT_RE_PROCDEF (ID_);
  
alter table ACT_RU_VARIABLE 
    add constraint ACT_FK_VAR_EXE 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);

alter table ACT_RU_VARIABLE
    add constraint ACT_FK_VAR_PROCINST
    foreign key (PROC_INST_ID_)
    references ACT_RU_EXECUTION(ID_);

alter table ACT_RU_JOB 
    add constraint ACT_FK_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_JOB 
    add constraint ACT_FK_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_JOB 
    add constraint ACT_FK_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);

alter table ACT_RU_TIMER_JOB 
    add constraint ACT_FK_TIMER_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_TIMER_JOB 
    add constraint ACT_FK_TIMER_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_TIMER_JOB 
    add constraint ACT_FK_TIMER_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
    
alter table ACT_RU_SUSPENDED_JOB 
    add constraint ACT_FK_SUSPENDED_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_SUSPENDED_JOB 
    add constraint ACT_FK_SUSPENDED_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_SUSPENDED_JOB 
    add constraint ACT_FK_SUSPENDED_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
    
alter table ACT_RU_DEADLETTER_JOB 
    add constraint ACT_FK_DEADLETTER_JOB_EXECUTION 
    foreign key (EXECUTION_ID_) 
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_DEADLETTER_JOB 
    add constraint ACT_FK_DEADLETTER_JOB_PROCESS_INSTANCE 
    foreign key (PROCESS_INSTANCE_ID_) 
    references ACT_RU_EXECUTION (ID_);
    
alter table ACT_RU_DEADLETTER_JOB 
    add constraint ACT_FK_DEADLETTER_JOB_PROC_DEF
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
    
alter table ACT_RU_EVENT_SUBSCR
    add constraint ACT_FK_EVENT_EXEC
    foreign key (EXECUTION_ID_)
    references ACT_RU_EXECUTION(ID_);
    
alter table ACT_RE_MODEL 
    add constraint ACT_FK_MODEL_SOURCE 
    foreign key (EDITOR_SOURCE_VALUE_ID_) 
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_RE_MODEL 
    add constraint ACT_FK_MODEL_SOURCE_EXTRA 
    foreign key (EDITOR_SOURCE_EXTRA_VALUE_ID_) 
    references ACT_GE_BYTEARRAY (ID_);
    
alter table ACT_RE_MODEL 
    add constraint ACT_FK_MODEL_DEPLOYMENT 
    foreign key (DEPLOYMENT_ID_) 
    references ACT_RE_DEPLOYMENT (ID_);        

alter table ACT_PROCDEF_INFO 
    add constraint ACT_FK_INFO_JSON_BA 
    foreign key (INFO_JSON_ID_) 
    references ACT_GE_BYTEARRAY (ID_);

alter table ACT_PROCDEF_INFO 
    add constraint ACT_FK_INFO_PROCDEF 
    foreign key (PROC_DEF_ID_) 
    references ACT_RE_PROCDEF (ID_);
    
alter table ACT_PROCDEF_INFO
    add constraint ACT_UNIQ_INFO_PROCDEF
    unique (PROC_DEF_ID_);

insert into ACT_GE_PROPERTY
values ('schema.version', '8.0.0.0', 1);

insert into ACT_GE_PROPERTY
values ('schema.history', 'create(8.0.0.0)', 1);

create table ACT_HI_PROCINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_INST_ID_ varchar(64) not null,
    BUSINESS_KEY_ varchar(255),
    PROC_DEF_ID_ varchar(64) not null,
    START_TIME_ datetime(3) not null,
    END_TIME_ datetime(3),
    DURATION_ bigint,
    START_USER_ID_ varchar(255),
    START_ACT_ID_ varchar(255),
    END_ACT_ID_ varchar(255),
    SUPER_PROCESS_INSTANCE_ID_ varchar(64),
    DELETE_REASON_ varchar(4000),
    TENANT_ID_ varchar(255) default '',
    NAME_ varchar(255),
    CALLBACK_ID_ varchar(255),
    CALLBACK_TYPE_ varchar(255),
    REFERENCE_ID_ varchar(255),
    REFERENCE_TYPE_ varchar(255),
    PROPAGATED_STAGE_INST_ID_ varchar(255),
    BUSINESS_STATUS_ varchar(255),
    END_USER_ID_ varchar(255),
    STATE_ varchar(255),
    primary key (ID_),
    unique (PROC_INST_ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_HI_ACTINST (
    ID_ varchar(64) not null,
    REV_ integer default 1,
    PROC_DEF_ID_ varchar(64) not null,
    PROC_INST_ID_ varchar(64) not null,
    EXECUTION_ID_ varchar(64) not null,
    ACT_ID_ varchar(255) not null,
    TASK_ID_ varchar(64),
    CALL_PROC_INST_ID_ varchar(64),
    ACT_NAME_ varchar(255),
    ACT_TYPE_ varchar(255) not null,
    ASSIGNEE_ varchar(255),
    COMPLETED_BY_ varchar(255),
    START_TIME_ datetime(3) not null,
    END_TIME_ datetime(3),
    TRANSACTION_ORDER_ integer,
    DURATION_ bigint,
    DELETE_REASON_ varchar(4000),
    TENANT_ID_ varchar(255) default '',
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_HI_DETAIL (
    ID_ varchar(64) not null,
    TYPE_ varchar(255) not null,
    PROC_INST_ID_ varchar(64),
    EXECUTION_ID_ varchar(64),
    TASK_ID_ varchar(64),
    ACT_INST_ID_ varchar(64),
    NAME_ varchar(255) not null,
    VAR_TYPE_ varchar(255),
    REV_ integer,
    TIME_ datetime(3) not null,
    BYTEARRAY_ID_ varchar(64),
    DOUBLE_ double,
    LONG_ bigint,
    TEXT_ varchar(4000),
    TEXT2_ varchar(4000),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_HI_COMMENT (
    ID_ varchar(64) not null,
    TYPE_ varchar(255),
    TIME_ datetime(3) not null,
    USER_ID_ varchar(255),
    TASK_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    ACTION_ varchar(255),
    MESSAGE_ varchar(4000),
    FULL_MSG_ LONGBLOB,
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;

create table ACT_HI_ATTACHMENT (
    ID_ varchar(64) not null,
    REV_ integer,
    USER_ID_ varchar(255),
    NAME_ varchar(255),
    DESCRIPTION_ varchar(4000),
    TYPE_ varchar(255),
    TASK_ID_ varchar(64),
    PROC_INST_ID_ varchar(64),
    URL_ varchar(4000),
    CONTENT_ID_ varchar(64),
    TIME_ datetime(3),
    primary key (ID_)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE utf8_bin;


create index ACT_IDX_HI_PRO_INST_END on ACT_HI_PROCINST(END_TIME_);
create index ACT_IDX_HI_PRO_I_BUSKEY on ACT_HI_PROCINST(BUSINESS_KEY_);
create index ACT_IDX_HI_PRO_SUPER_PROCINST on ACT_HI_PROCINST(SUPER_PROCESS_INSTANCE_ID_);
create index ACT_IDX_HI_ACT_INST_START on ACT_HI_ACTINST(START_TIME_);
create index ACT_IDX_HI_ACT_INST_END on ACT_HI_ACTINST(END_TIME_);
create index ACT_IDX_HI_DETAIL_PROC_INST on ACT_HI_DETAIL(PROC_INST_ID_);
create index ACT_IDX_HI_DETAIL_ACT_INST on ACT_HI_DETAIL(ACT_INST_ID_);
create index ACT_IDX_HI_DETAIL_TIME on ACT_HI_DETAIL(TIME_);
create index ACT_IDX_HI_DETAIL_NAME on ACT_HI_DETAIL(NAME_);
create index ACT_IDX_HI_DETAIL_TASK_ID on ACT_HI_DETAIL(TASK_ID_);
create index ACT_IDX_HI_PROCVAR_PROC_INST on ACT_HI_VARINST(PROC_INST_ID_);
create index ACT_IDX_HI_PROCVAR_TASK_ID on ACT_HI_VARINST(TASK_ID_);
create index ACT_IDX_HI_PROCVAR_EXE on ACT_HI_VARINST(EXECUTION_ID_);
create index ACT_IDX_HI_ACT_INST_PROCINST on ACT_HI_ACTINST(PROC_INST_ID_, ACT_ID_);
create index ACT_IDX_HI_ACT_INST_EXEC on ACT_HI_ACTINST(EXECUTION_ID_, ACT_ID_);
create index ACT_IDX_HI_IDENT_LNK_TASK on ACT_HI_IDENTITYLINK(TASK_ID_);
create index ACT_IDX_HI_IDENT_LNK_PROCINST on ACT_HI_IDENTITYLINK(PROC_INST_ID_);
create index ACT_IDX_HI_TASK_INST_PROCINST on ACT_HI_TASKINST(PROC_INST_ID_);


-- ---------- event registry (process starter 传递依赖，装配期做版本校验) ----------
CREATE TABLE FLW_EVENT_DEPLOYMENT (ID_ VARCHAR(255) NOT NULL, NAME_ VARCHAR(255) NULL, CATEGORY_ VARCHAR(255) NULL, DEPLOY_TIME_ datetime(3) NULL, TENANT_ID_ VARCHAR(255) NULL, PARENT_DEPLOYMENT_ID_ VARCHAR(255) NULL, CONSTRAINT PK_FLW_EVENT_DEPLOYMENT PRIMARY KEY (ID_));

CREATE TABLE FLW_EVENT_RESOURCE (ID_ VARCHAR(255) NOT NULL, NAME_ VARCHAR(255) NULL, DEPLOYMENT_ID_ VARCHAR(255) NULL, RESOURCE_BYTES_ LONGBLOB NULL, CONSTRAINT PK_FLW_EVENT_RESOURCE PRIMARY KEY (ID_));

CREATE TABLE FLW_EVENT_DEFINITION (ID_ VARCHAR(255) NOT NULL, NAME_ VARCHAR(255) NULL, VERSION_ INT NULL, KEY_ VARCHAR(255) NULL, CATEGORY_ VARCHAR(255) NULL, DEPLOYMENT_ID_ VARCHAR(255) NULL, TENANT_ID_ VARCHAR(255) NULL, RESOURCE_NAME_ VARCHAR(255) NULL, DESCRIPTION_ VARCHAR(255) NULL, CONSTRAINT PK_FLW_EVENT_DEFINITION PRIMARY KEY (ID_));

ALTER TABLE FLW_EVENT_RESOURCE
    ADD CONSTRAINT FLW_FK_EVENT_RSRC_DPL FOREIGN KEY (DEPLOYMENT_ID_) REFERENCES FLW_EVENT_DEPLOYMENT (ID_);

CREATE INDEX FLW_IDX_EVENT_RSRC_DPL ON FLW_EVENT_RESOURCE (DEPLOYMENT_ID_);

CREATE UNIQUE INDEX ACT_IDX_EVENT_DEF_UNIQ ON FLW_EVENT_DEFINITION(KEY_, VERSION_, TENANT_ID_);

CREATE TABLE FLW_CHANNEL_DEFINITION (ID_ VARCHAR(255) NOT NULL, NAME_ VARCHAR(255) NULL, VERSION_ INT NULL, KEY_ VARCHAR(255) NULL, CATEGORY_ VARCHAR(255) NULL, TYPE_ VARCHAR(255) NULL, IMPLEMENTATION_ VARCHAR(255) NULL, DEPLOYMENT_ID_ VARCHAR(255) NULL, CREATE_TIME_ datetime(3) NULL, TENANT_ID_ VARCHAR(255) NULL, RESOURCE_NAME_ VARCHAR(255) NULL, DESCRIPTION_ VARCHAR(255) NULL, CONSTRAINT PK_FLW_CHANNEL_DEFINITION PRIMARY KEY (ID_));

CREATE UNIQUE INDEX ACT_IDX_CHANNEL_DEF_UNIQ ON FLW_CHANNEL_DEFINITION(KEY_, VERSION_, TENANT_ID_);

insert into ACT_GE_PROPERTY
values ('eventregistry.schema.version', '8.0.0.0', 1);

-- ---------- 平台侧流程业务表:模板目录 / 租户模型 / 审批留痕 ----------


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
