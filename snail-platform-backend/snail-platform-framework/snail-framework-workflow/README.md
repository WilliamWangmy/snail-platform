# snail-framework-workflow

Flowable 流程引擎的接入层：引擎装配基线、唯一读写门面、租户桥、BPMN 安全校验与键改写、流程变量准入。

全仓只有本构件允许 `import org.flowable.*`（ArchUnit 锁定）；业务侧不认识 Flowable，只调
`WorkflowEngineGateway`。本模块不建 `ACT_*` Mapper、不提供 REST、不依赖 function、不声明 `@Transactional`。

- 所属工程：[`snail-platform-backend`](../../pom.xml)
- 基础包名：`com.snail.platform.framework.workflow`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [启动基线](#启动基线)
  - [网关的四条钉死项](#网关的四条钉死项)
  - [租户模型](#租户模型)
  - [身份注入](#身份注入)
  - [流程变量准入](#流程变量准入)
  - [BPMN 安全闸门](#bpmn-安全闸门)
  - [流程标识改写](#流程标识改写)
  - [异常映射](#异常映射)
- [能力清单](#能力清单)
- [详细设计：按类](#详细设计按类)
- [配置项](#配置项)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- 引擎装配：强制晚于 Flyway、禁止自动建表/升级、启动期配置基线断言；
- 唯一读写门面 `WorkflowEngineGateway`：部署、发起、办理、签收、转办、撤销、定义与实例查询、待办/已办/我发起、轨迹；
- 租户隔离：写侧显式带 `tenantId`，读侧显式加租户过滤，缺失即拒；
- 身份注入：引擎的 `Authentication` 从登录人快照写入，结束必清；
- 流程变量准入：只收标量，条数与字符串长度有上限；
- BPMN 发布前安全校验：元素/属性/表达式白名单，拒绝 RCE/SSRF/跨租户构造；
- 模板建模型时的流程标识改写；
- 引擎异常到平台 `BizException` 的统一映射；
- 引擎异步线程的租户桥。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 业务单据、模型元数据、审批意见留痕、通知 | `snail-function-workflow` |
| 用户档案、角色与候选人的有效性 | `snail-function-system`（网关只比对角色 ID） |
| 登录人上下文的产生 | `snail-framework-security` |
| 引擎建表 | Flyway（V4） |
| 事务边界 | 调用方 function service |
| 表单、DMN、CMMN、IDM | 不引入 |

## 在平台中的位置

```
WorkflowModelService / WorkflowInstanceService / WorkflowTaskService（function-workflow）
        │  只调网关，不认识 Flowable
        ▼
WorkflowEngineGateway ──▶ RepositoryService / RuntimeService / TaskService / HistoryService
        │
        ├─ WorkflowTenantIds        平台 Long ↔ 引擎 String 租户
        ├─ WorkflowVariablePolicy   变量准入
        ├─ BpmnSecurityValidator    发布前安全闸门
        └─ WorkflowEngineErrors     引擎异常 → BizException

Flowable 定时器/异步作业线程（生产回调入口，尚未接入调用方）
        └─ WorkflowTenantBridge ──▶ TenantContextHolder.runAsEngineTenant
```

## 包结构

```
com.snail.platform.framework.workflow
├── package-info.java                构件边界声明
├── bpmn/
│   ├── BpmnKeyRewriter.java         流程标识改写（模板建模型）
│   └── BpmnSecurityValidator.java   发布前安全白名单校验
├── config/
│   └── WorkflowEngineConfig.java    引擎装配顺序 + 启动基线校验
├── engine/
│   ├── WorkflowEngineGateway.java   唯一读写门面（@Component）
│   ├── WorkflowDefinition.java      定义只读视图
│   ├── WorkflowDeployment.java      部署结果
│   ├── WorkflowInstance.java        实例只读视图
│   ├── WorkflowTask.java            任务只读视图
│   └── WorkflowTraceStep.java       轨迹一步
├── support/
│   ├── WorkflowEngineErrors.java    引擎异常映射
│   ├── WorkflowTimes.java           引擎时间换算
│   └── WorkflowVariablePolicy.java  变量准入
└── tenant/
    ├── WorkflowTenantBridge.java    异步线程租户桥
    └── WorkflowTenantIds.java       租户字符串 ↔ 平台 ID
```

## 整体设计

### 启动基线

`WorkflowEngineConfig` 在启动期钉死两件事：

1. **引擎必须晚于 Flyway 建表**：`BeanFactoryPostProcessor` 找到所有 `ProcessEngineConfiguration`
   bean，给它们追加 `dependsOn=flywayInitializer`；容器里没有 Flyway 执行器时直接拒绝启动。
   原因：Boot 只给自家 JPA/JDBC 构件挂 Flyway 前置依赖，Flowable 不在其列——空库启动时引擎会赶在建表前
   校验版本行，抛 `FlowableWrongDbException`，看起来像迁移没跑，实际是装配顺序；
2. **配置基线不许漂**：启动期校验
   - `flowable.database-schema-update` 必须为 `false`（建表归 Flyway V4）；
   - `flowable.check-process-definitions` 必须显式 `false`（流程定义只能经网关按租户部署）；
   - `flowable.idm.enabled` 必须显式 `false`（用户与角色只认平台 `sys_*` 表，双身份源必然串号）。

任何一项漂移都拒绝启动——配置漂移是静默的，而流程引擎的静默漂移代价是生产库结构被改。

### 网关的四条钉死项

`WorkflowEngineGateway` 把四件事收敛在同一处：

| # | 事情 | 做法 |
| --- | --- | --- |
| 1 | 租户 | 每个动作先 `WorkflowTenantIds.requireEngineTenant()`；写侧带 `tenantId`，读侧加租户过滤，缺失/旁路窗口即拒 |
| 2 | 身份 | 引擎 `Authentication` 从 `LoginUserContextHolder` 注入，只在本层生效，`finally` 清空 |
| 3 | 变量 | 进出都过 `WorkflowVariablePolicy.sanitize` |
| 4 | 异常 | `WorkflowEngineErrors` 把 Flowable 异常翻成 `BizException`，类型不外泄 |

网关不声明事务：引擎与业务共用同一 `DataSource` 与事务管理器，网关写入自动加入调用方事务。

### 租户模型

- 平台租户是 `Long`，引擎 `TENANT_ID_` 是 `varchar`；换算只在 `WorkflowTenantIds` 一处；
- 引擎表由 Flowable 自己的 MyBatis 会话访问，**不经过平台 MyBatis-Plus 租户拦截器**，
  所以租户必须显式进出，不能指望拦截器兜底；
- `requireEngineTenant()`：无租户或正处于旁路窗口都拒绝——旁路窗口里写引擎表等于写一条没有归属的流程；
- `toPlatformTenant()`：空串/非数字都是脏数据，拒绝而不是回退默认值。

### 身份注入

- 发起时 `initiator` 由网关按登录人写入，**调用方自带同名变量直接 400**——
  否则等于允许把待办投给任意人；
- 发起、办理、签收、转办、撤销都在 `supplyAsUser/runAsUser` 中执行：设置引擎 `Authentication`，
  `finally` 必清。`Authentication` 是线程级变量，漏清理会让线程池里的下一个请求把流程记到别人名下；
- 引擎异步线程没有登录人：网关的写动作在 `requireLoginUserId()` 就拒绝；异步回调设计上走 `WorkflowTenantBridge`，
  操作人只能是调用方自带的 system 身份（当前仓内生产调用方尚未接入，流程测试已覆盖该桥）。

### 流程变量准入

- 只收标量：`String/Integer/Long/Double/BigDecimal/Boolean`；
- 变量名：字母开头，字母数字下划线，1–64 字符；
- 最多 16 个；字符串值最长 512 字符；
- 拒绝 null 值与对象/集合/日期——流程变量会跨线程、跨版本、跨序列化器读取，POJO/record 入流程会把
  引擎历史与某个类结构绑死（平台已踩过 Hutool 不序列化 record 的坑）；
- 需要传时间就传 ISO 字符串；
- 拒绝文案点名具体变量键，设计器/前端可直接定位。

### BPMN 安全闸门

BPMN 是可执行代码，发布权限等同代码部署权限。`BpmnSecurityValidator.validate(xml)` 在进引擎之前执行：

- XML ≤ 2MB、元素 ≤ 200；硬化解析器禁 DOCTYPE、外部实体、XInclude；
- 根元素必须是 BPMN `definitions`，且只允许一个 `process`；
- 结构：恰好一个 `startEvent`、至少一个 `endEvent`、至少一个 `userTask`；
- 可执行元素白名单：
  `definitions, process, startEvent, endEvent, userTask, exclusiveGateway, parallelGateway,
  sequenceFlow, conditionExpression, incoming, outgoing`；
- 属性白名单：结构属性 + Flowable 扩展只允许 `assignee/candidateGroups`；
- 表达式裁剪：`${...}` 内只允许标识符、空白与标量运算符，**刻意禁掉 `. ( ) [ ] , # @`**——
  这一条同时封死 `${bean.method()}`、`${execution.getVariable('x')}`、`${T(java.lang.Runtime)}`；
- 图形/声明命名空间放行，其余扩展命名空间元素直接拒；
- 返回 `process id`，发布方据此核对"模型 key 与定义 key 是否一致"。

元素与属性白名单是常量，后续开白（定时边界事件、子流程、会签）只改这一处，不改调用方。

### 流程标识改写

`BpmnKeyRewriter.rewrite(xml, newKey)` 用于"从平台模板建模型"：

- 平台模板全局一份，不预绑租户流程标识；而发布口径要求 `model_key` 与 `process id` 一致；
- 只改 `process id` 与"值等于旧标识的引用属性"（`bpmnElement`、`processRef`），不动业务内容；
- 标识未变时原样返回，避免序列化重排空白与属性顺序；
- 改名不是安全边界：产出仍需过 `BpmnSecurityValidator`。

### 异常映射

`WorkflowEngineErrors` 是引擎异常到业务码的唯一映射：

| 引擎异常 | 结果 |
| --- | --- |
| `BizException`（网关自己的闸门） | 原样直通 |
| `FlowableObjectNotFoundException` | 400，目标流程对象不存在或已被办理 |
| `FlowableIllegalArgumentException` | 400，流程参数不合法 |
| 其余 `FlowableException` | `IllegalStateException` → 全局 500，保留原始 cause 供日志定位 |

引擎英文文案不直接回给使用者；Flowable 类型不得越过本构件边界。

## 能力清单

| 能力 | 方法 | 关键口径 |
| --- | --- | --- |
| 部署定义 | `deploy(name, bpmnXml)` | 先安全校验；一次部署只允许产出一个定义 |
| 发起 | `start(definitionKey, businessKey, variables)` | 当前租户最新定义；注入 `initiator`；标题取 `businessTitle`，缺省用定义名 |
| 办理 | `complete(taskId, variables)` | 办理权在本层校验；未签收任务"办理即签收" |
| 签收 | `claim(taskId)` | 候选角色内才允许 |
| 转办 | `transfer(taskId, targetUserId)` | 目标用户是否同租户由业务 service 校验 |
| 撤销 | `withdraw(processInstanceId, reason)` | 已结束实例直接拒绝 |
| 定义查询 | `findDefinitions / requireDefinition` | 强制当前租户；跨租户视作不存在 |
| 挂起/激活 | `suspendDefinition / activateDefinition` | 用单参重载，不连带挂起在途实例 |
| 版本原文 | `findDefinitionXml(definitionId)` | 设计器回显；租户已钉死 |
| 在途判断 | `hasRunningInstancesOfKey / hasRunningInstance` | 删除模型、一单一条的判据 |
| 我发起的 | `findStartedByMe / countStartedByMe` | 走历史表，含已结束与已撤销 |
| 待办 | `findTodo / countTodo` | 办理人=我，或未签收且候选角色命中；分页与计数同条件 |
| 已办 | `findDone / countDone` | 历史任务、办理人=我、已结束 |
| 实例视图 | `requireInstance / findInstancesByIds / findLatestInstance` | 在途查运行时、其余落历史；批量避免 N+1 |
| 实例待办 | `findTasksOfInstance` | 不按人过滤，供通知找收件人 |
| 轨迹 | `findTrace` | 按进入时刻正序；意见与动作语义在业务留痕表 |
| 任务视图 | `requireVisibleTask / requireHandlableTask` | 后者含办理权校验 |

办理权规则：自己是办理人；或任务未签收且自己的启用角色与候选组有交集；他人已签收的任务一律 403。

## 详细设计：按类

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| [`WorkflowEngineConfig`](src/main/java/com/snail/platform/framework/workflow/config/WorkflowEngineConfig.java) | 装配顺序与启动基线 | `static` 后处理器；缺 Flyway 拒绝启动；三项配置漂移拒绝启动 |
| [`WorkflowEngineGateway`](src/main/java/com/snail/platform/framework/workflow/engine/WorkflowEngineGateway.java) | 唯一门面 | 租户/身份/变量/异常四件事钉死；不声明事务 |
| [`WorkflowTenantIds`](src/main/java/com/snail/platform/framework/workflow/tenant/WorkflowTenantIds.java) | 租户换算 | 无租户/旁路窗口拒绝；脏租户字符串拒绝 |
| [`WorkflowTenantBridge`](src/main/java/com/snail/platform/framework/workflow/tenant/WorkflowTenantBridge.java) | 异步线程租户桥 | 租户从实例反查，不接受自选；实例不存在按拒绝 |
| [`WorkflowVariablePolicy`](src/main/java/com/snail/platform/framework/workflow/support/WorkflowVariablePolicy.java) | 变量准入 | 只收标量；16 个/512 字符上限；拒绝点名键 |
| [`WorkflowEngineErrors`](src/main/java/com/snail/platform/framework/workflow/support/WorkflowEngineErrors.java) | 异常映射 | 业务闸门直通；引擎故障归 500 |
| [`WorkflowTimes`](src/main/java/com/snail/platform/framework/workflow/support/WorkflowTimes.java) | 时间换算 | 引擎 `Date` → `LocalDateTime`，只用这一处 |
| [`BpmnSecurityValidator`](src/main/java/com/snail/platform/framework/workflow/bpmn/BpmnSecurityValidator.java) | 发布安全闸门 | 元素/属性/表达式白名单；2MB/200 节点上限 |
| [`BpmnKeyRewriter`](src/main/java/com/snail/platform/framework/workflow/bpmn/BpmnKeyRewriter.java) | 流程标识改写 | 只改标识与引用属性；不是安全边界 |
| 视图记录 | `WorkflowDefinition / Deployment / Instance / Task / TraceStep` | 屏蔽 Flowable 类型；集合字段恒非 null 且不可变 |

## 配置项

| 配置键 | 值 | 说明 |
| --- | --- | --- |
| `flowable.database-schema-update` | `false` | 启动期断言，非 false 拒绝启动 |
| `flowable.check-process-definitions` | `false` | 启动期断言，禁止 classpath 自动部署 |
| `flowable.idm.enabled` | `false` | 启动期断言，禁用 Flowable 自带身份 |
| `flowable.async-executor-activate` | `true` | 定时器与异步作业执行器 |
| `flowable.async-history-executor-activate` | `false` | 关闭异步历史，少一条后台写链路 |
| `flowable.history-level` | `audit` | 不记变量明细，控制 `ACT_HI_DETAIL` 膨胀 |
| `flowable.jpa-enabled` | `false` | 不启用 JPA |
| `flowable.process.enable-safe-xml` | `true` | 引擎侧兜底；业务闸门在 `BpmnSecurityValidator` |
| `flowable.process.definition-cache-limit` | `128` | 定义缓存条数 |

引擎表由 Flyway `V4` 创建；引擎与业务共用同一数据源与事务管理器。

## 使用方式

```java
// 发布模型（function-workflow 的模型发布动作）
WorkflowDeployment deployment = gateway.deploy(model.getName(), model.getBpmnXml());
// 回写 deployment.processDefinitionId()/version 到模型表

// 发起
WorkflowInstance instance = gateway.start(
        model.getModelKey(),
        businessKey,
        Map.of("businessTitle", title, "amount", amount));

// 待办与办理
List<WorkflowTask> todo = gateway.findTodo(0, 20);
gateway.complete(taskId, Map.of("approved", true));

// 删除模型前：按 key 查所有版本的在途实例
if (gateway.hasRunningInstancesOfKey(modelKey)) {
    throw BizException.badRequest("流程仍有在途实例，不能删除");
}

// 引擎异步回调（定时器/作业线程）
tenantBridge.runForInstance(processInstanceId, () -> {
    // 在该实例所属租户下写业务表；操作人由回调自带（system）
    return null;
});
```

## 验证手段

- [`BpmnSecurityValidatorTest`](src/test/java/com/snail/platform/framework/workflow/bpmn/BpmnSecurityValidatorTest.java)：
  元素/属性/表达式白名单、结构闸门、体积/节点上限；
- [`WorkflowVariablePolicyTest`](src/test/java/com/snail/platform/framework/workflow/support/WorkflowVariablePolicyTest.java)：
  标量放行、对象/集合/日期拒绝、条数与长度上限；
- `WorkflowEngineSpikeTest`（start）：引擎装配与 Flyway 顺序的实跑验证；
- `WorkflowGatewayFlowTest` / `WorkflowModelFlowTest` / `WorkflowBusinessFlowTest` / `WorkflowTaskFlowTest`：
  部署、发起、办理、撤销、挂起、待办/已办、租户隔离与模型删除闸门；
- `ArchitectureRulesTest`：全仓只允许本构件 import Flowable；`runAsEngineTenant` 只允许 `workflow.tenant` 调用；
- 全量回归：在 `snail-platform-backend` 执行 `mvn test`（需要可连的 MySQL，Flyway 会建 `ACT_*` 表）。

## 已知边界与注意事项

- **没有 REST、没有 `ACT_*` Mapper**：引擎的一切读写只能经 `WorkflowEngineGateway`；
- **不声明事务**：网关写入加入调用方 service 的事务；业务侧必须保证"引擎动作 + 业务状态回写"在同一事务；
- **v1 元素集是刻意的能力裁剪**：定时边界事件、子流程、会签、服务任务/脚本任务/监听器都未开放；
  开白必须同时评审安全影响；
- **一次部署只产出一个定义**：模型的当前版本指针按单定义写回，多定义会让指针含糊；
- **候选组是角色 ID 字符串**：停用角色不参与匹配（登录人快照只收启用角色）；
- **历史任务不带候选组**：已办展示以留痕为准；
- **时间按系统时区呈现**：换算只在 `WorkflowTimes`；流程变量不放日期对象；
- **异步回调没有登录人**：租户由实例反查，操作人只能由调用方自带；
  `WorkflowTenantBridge` 已实现且被流程测试覆盖，但仓内尚无生产异步回调调用方，接入定时器/作业时从该桥进入；
- **`history-level=audit` 不记变量明细**：需要变量级审计时不能依赖 `ACT_HI_DETAIL`。
