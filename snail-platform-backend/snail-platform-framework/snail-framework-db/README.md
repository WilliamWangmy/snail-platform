# snail-framework-db

数据库横切能力的装配点：MyBatis-Plus 拦截器链、租户行级过滤、数据权限注入、分页装配与上限、审计字段自动填充。

它不含任何 Mapper、不硬编码任何表名；方言 `DbType.MYSQL` 只在这里固定一次。

- 所属工程：[`snail-platform-backend`](../../pom.xml)
- 基础包名：`com.snail.platform.framework.db`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [拦截器链与顺序](#拦截器链与顺序)
  - [租户过滤与旁路](#租户过滤与旁路)
  - [数据权限窗口](#数据权限窗口)
  - [分页装配与排序白名单](#分页装配与排序白名单)
  - [审计填充](#审计填充)
- [详细设计：按类](#详细设计按类)
- [配置项](#配置项)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- MyBatis-Plus 拦截器链的装配：租户过滤 → 数据权限 → 分页；
- 租户托管表的 `tenant_id` 自动注入与豁免清单；
- 数据权限谓词 `(org IN (...) OR create_by = ?)` 的 SQL 注入；
- `PageQuery` → MyBatis-Plus `Page` 的唯一装配入口与排序白名单；
- `create_by/create_time/update_by/update_time/deleted` 的自动填充与逻辑删除基类；
- 分页单页上限的兜底夹紧。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 各业务表、Mapper、SQL | `snail-function-*` 各模块 |
| 数据权限四档口径的判定 | `snail-function-system` 的 `DataScopeService` |
| 数据源、连接池、Flyway 位置 | `snail-platform-start` 的配置与迁移脚本 |
| 登录人/租户身份的产生 | `snail-framework-core` 的上下文 + `snail-framework-security` 的过滤器 |
| 业务表结构 | Flyway 脚本（`snail-platform-start/src/main/resources/db/migration`） |

## 在平台中的位置

```
Service ──PageAssembler.from(query)──▶ MP Page + 条件构造器
   │
   ├─ 普通查询 ──▶ MyBatis-Plus 拦截器链
   │                1. TenantLineInnerInterceptor   注入 tenant_id
   │                2. SnailDataScopeInnerInterceptor 注入权限谓词
   │                3. PaginationInnerInterceptor   改写 LIMIT / count
   │
   └─ 开窗查询 ──DataScopeContextHolder.filter(filter, ...)
                    只在窗口内的 SELECT 注入权限条件

INSERT/UPDATE ──SnailAuditMetaObjectHandler──▶ 审计四件套
                  操作人取 LoginUserContextHolder.userId()
```

## 包结构

```
com.snail.platform.framework.db
├── MybatisPlusConfig.java                 拦截器链与 MetaObjectHandler 装配
├── PageAssembler.java                     分页装配与排序白名单
├── SnailAuditMetaObjectHandler.java       审计四件套自动填充
├── SnailDbProperties.java                 snail.db.* 配置
├── SnailTenantLineHandler.java            租户注入与豁免判定
├── datascope/
│   ├── DataScopeContextHolder.java        数据权限窗口（ThreadLocal）
│   ├── DataScopeFilter.java               权限谓词值对象
│   └── SnailDataScopeInnerInterceptor.java 谓词注入 SQL
└── entity/
    ├── AuditedEntity.java                 审计 + 逻辑删除基类
    └── TenantAuditedEntity.java           租户业务表基类（+ tenant_id）
```

## 整体设计

### 拦截器链与顺序

顺序固定为 **租户 → 数据权限 → 分页**，不能交换：

- 租户条件必须先于数据权限，权限窗口只在业务列表查询上开，租户条件对所有租户托管表恒在；
- 数据权限必须先于分页，分页的 `count` 语句必须基于"已加权限谓词"的 SQL 计数，否则总数会大于可见行数；
- 方言 `DbType.MYSQL` 只在 `MybatisPlusConfig` 固定一次，业务侧不重复声明；
- `@MapperScan("com.snail.platform.**.mapper")` 收敛 Mapper 扫描范围。

### 租户过滤与旁路

- 判别列恒为 `tenant_id`；
- `getTenantId()` 只认 `TenantContextHolder`；上下文缺失直接抛 `IllegalStateException`——
  放行一条无租户条件的 SQL 等于跨租户越权，宁可崩；
- `ignoreTable` 两条路径：窗口标记 `TenantContextHolder.isBypassed()` 全表放行；否则比对配置清单（大小写不敏感）；
- 当前豁免清单（`application.yml`）：
  `sys_tenant, sys_user_role, sys_login_log, sys_oper_log, sys_menu, sys_role_menu, sys_notify_outbound, sys_workflow_template`；
- 旁路窗口只能经 `TenantContextHolder.withoutTenant(...)` 成对开闭，不允许业务代码自造。

### 数据权限窗口

设计要点是"够窄"：只有开窗线程内的 SELECT 被注入谓词，按 ID 取详情、删除前置检查、关联计数都不受影响。

- 窗口值为 `DataScopeFilter`，两臂 OR 并集：`orgIds`（组织范围）与 `creatorId`（本人单据），任一臂为 null 即不参与；
- `filter == null` 表示"全部档"，直接透传、不开窗；
- 列名默认 `org_id` / `create_by`，构造时按小写下划线白名单校验（`[a-z][a-z0-9_]{0,63}`），ID 为 BIGINT，无注入面；
- 拦截器只改写单表 `PlainSelect`：UNION、JOIN 等形态直接 fail-fast，不静默放过；
  确需豁免的 Mapper 用 `@InterceptorIgnore(dataPermission)` 显式声明；
- 空窗口（两臂皆空）按不开窗处理。

### 分页装配与排序白名单

```java
Page<T> page = PageAssembler.from(query);
PageAssembler.applySort(wrapper, query.orderBy(), SORTABLE);
```

- `from` 是唯一装配点，深翻保护、上限调整等后续机制只改这里；
- `applySort` 的 `orderBy` 形如 `field` 或 `field:asc|field:desc`，缺省方向 `desc`；
- 空白表示不加排序，保留 service 内既有固定排序；
- 字段必须命中域内提供的白名单 `Map<String, SFunction<T, ?>>`，方向只能 `asc/desc`，否则 400；
  原始字符串永不进 SQL。

### 审计填充

- `insert`：补 `createTime/updateTime`；有登录人时补 `createBy/updateBy`；
- `update`：补 `updateTime`；有登录人时补 `updateBy`；
- 操作人取 `LoginUserContextHolder.userId()`；系统级线程取不到时留空；
- **条件构造器写法的坑**：MP 的填充只认实体对象，`update(Wrapper)` 若传 `null` 实体就没有可填对象，
  `update_time/update_by` 会静默停在旧值。必须传空实体，如
  `userMapper.update(new SysUser(), wrapper)`；全仓不允许 `update(null, wrapper)`。

## 详细设计：按类

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| [`MybatisPlusConfig`](src/main/java/com/snail/platform/framework/db/MybatisPlusConfig.java) | 拦截器链与填充器装配 | 顺序租户→权限→分页；`DbType.MYSQL` 单点固定 |
| [`SnailTenantLineHandler`](src/main/java/com/snail/platform/framework/db/SnailTenantLineHandler.java) | 租户值、列名与豁免 | 无租户上下文即抛错；豁免按配置清单大小写不敏感匹配 |
| [`SnailDataScopeInnerInterceptor`](src/main/java/com/snail/platform/framework/db/datascope/SnailDataScopeInnerInterceptor.java) | 权限谓词注入 | 只改单表 `PlainSelect`；非单表/ JOIN fail-fast；`@InterceptorIgnore` 可显式豁免 |
| [`DataScopeFilter`](src/main/java/com/snail/platform/framework/db/datascope/DataScopeFilter.java) | 权限谓词值对象 | 列名构造即校验；`of` 用默认列名 |
| [`DataScopeContextHolder`](src/main/java/com/snail/platform/framework/db/datascope/DataScopeContextHolder.java) | 权限窗口 | `null` 透传不开窗；`finally` 恢复上一窗口 |
| [`PageAssembler`](src/main/java/com/snail/platform/framework/db/PageAssembler.java) | 分页装配与排序 | 排序字段只走白名单；非法方向/字段 400 |
| [`SnailAuditMetaObjectHandler`](src/main/java/com/snail/platform/framework/db/SnailAuditMetaObjectHandler.java) | 审计填充 | insert 填四列、update 填两列；wrapper 更新须传空实体 |
| [`SnailDbProperties`](src/main/java/com/snail/platform/framework/db/SnailDbProperties.java) | 配置绑定 | 租户豁免清单与分页上限，代码不硬编码表名 |
| [`AuditedEntity`](src/main/java/com/snail/platform/framework/db/entity/AuditedEntity.java) | 平台级表基类 | 四件套 + `@TableLogic deleted`；日志表不继承 |
| [`TenantAuditedEntity`](src/main/java/com/snail/platform/framework/db/entity/TenantAuditedEntity.java) | 租户业务表基类 | `tenant_id` 只供拦截器注入与回读，业务不手写 |

## 配置项

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `snail.db.tenant.ignore-tables` | 见 `application.yml` | 不参与租户过滤的表名清单（小写） |
| `snail.db.pagination.max-limit` | `500` | 单页最大行数，超出由分页拦截器夹紧 |
| `spring.datasource.*` / `spring.flyway.*` | 无（start 侧声明） | 连接与迁移；prod 全部来自环境变量 |
| `mybatis-plus.*` | 默认 | 未在本模块额外定制 |

## 使用方式

```java
// 分页
Page<SysUser> page = PageAssembler.from(query);
LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
        .like(StrUtil.isNotBlank(query.keyword()), SysUser::getUsername, query.keyword());
PageAssembler.applySort(wrapper, query.orderBy(), SORTABLE);
userMapper.selectPage(page, wrapper);
return PageResult.of(rows, page.getTotal());

// 数据权限窗口（业务 Service 的列表查询）
DataScopeFilter scope = dataScopeService.filterOfCurrentUser();
return DataScopeContextHolder.filter(scope, () -> userMapper.selectPage(page, wrapper));

// 条件构造器更新：必须传空实体，否则审计列填不上
userMapper.update(new SysUser(), new LambdaUpdateWrapper<SysUser>()
        .eq(SysUser::getId, id)
        .set(SysUser::getStatus, 1));

// 实体继承
public class SysUser extends TenantAuditedEntity { ... }   // 租户业务表
public class SysDictType extends AuditedEntity { ... }     // 平台级表
```

## 验证手段

- [`PageAssemblerTest`](../../snail-platform-start/src/test/java/com/snail/platform/framework/PageAssemblerTest.java)：分页装配与排序白名单；
- `DataScopeFlowTest` / `DataScopeGuardFlowTest`：权限窗口的可见范围与越权拒绝；
- `QueryContractFlowTest`：分页契约；
- `ArchitectureRulesTest`：ArchUnit 锁 `PageAssembler.from` 与事务包边界等规则；
- 全量回归：在 `snail-platform-backend` 执行 `mvn test`。

## 已知边界与注意事项

- **无上下文即失败**：认证前或异步线程直接查租户托管表会抛 `IllegalStateException`，
  这是隔离闸门而不是 bug；异步场景必须显式恢复租户上下文；
- **数据权限只覆盖单表 SELECT**：JOIN/UNION 查询在窗口内直接失败，需要豁免时在 Mapper 上显式标注；
- **权限窗口不自动关闭**：必须经 `DataScopeContextHolder.filter(...)` 执行，手写 `set/clear` 会漏；
- **审计填充依赖实体**：`update(null, wrapper)` 会静默丢失 `update_time/update_by`，全仓禁止；
- **逻辑删除不可绕过**：`@TableLogic` 会给查询自动加条件，物理删除语句只应出现在明确的清理脚本里；
- **本模块不建表**：所有表结构归 Flyway，拦截器与基类只消费既有列；
- **分页上限是兜底**：域内 Query 仍应自行校验（当前口径 200），`max-limit=500` 只防失控请求。
