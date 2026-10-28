# snail-function-system

系统管理业务模块：租户、组织、用户、角色、菜单、字典、审计日志七个子域，表分组 `sys_*`。
它是整个平台的地基——认证模块拿它的用户与权限码，通知与工作流模块拿它的收件人，管理端前端拿它的菜单树。

- 所属工程：[`snail-platform-backend`](../../pom.xml)（Maven 三层：framework / function / start）
- 基础包名：`com.snail.platform.function.system`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [包结构](#包结构)
- [四条横切主线](#四条横切主线)
- [数据模型](#数据模型)
- [子域详细设计](#子域详细设计)
- [接口与权限码总表](#接口与权限码总表)
- [跨模块契约](#跨模块契约)
- [使用方法](#使用方法)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：租户生命周期（开通/停用/资料），组织树，用户档案与授权，角色与数据权限档位，全局菜单目录与角色授权，租户级字典，登录/操作日志的查询与导出，在线会话，个人中心。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 登录、验证码、令牌签发与登出 | `snail-function-auth`（它单向依赖本模块） |
| 令牌校验、SecurityContext、`@PreAuthorize` 判定 | `snail-framework-security` |
| 租户行级过滤 SQL、分页装配、审计字段填充 | `snail-framework-db` |
| 操作日志的采集与异步投递（切面） | `snail-framework-log`，本模块只实现落库端口 |
| 站内信、公告投递 | `snail-function-notify` |
| 文件与对象存储 | `snail-function-storage` + `snail-framework-storage` |
| 审批流引擎与流程单据 | `snail-function-workflow` + `snail-framework-workflow` |

本模块不提供 `@Configuration` 与启动类：装配语义全部集中在 `snail-platform-start`，模块自身只是一个被扫描进 Spring 容器的 jar。

## 包结构

```
com.snail.platform.function.system
├── tenant/   租户：全生命周期与开通模板复制（平台面）
├── org/      组织：一棵租户内树，ancestors 路径 + 子树缓存
├── user/     用户：档案、口令、令牌版本、在线会话、个人中心
├── role/     角色：权限包、data_scope 档位、角色-菜单授权、权限码解析
├── menu/     菜单：全局目录（平台面），租户只授不建
├── dict/     字典：类型 + 数据项，租户各自维护
├── log/      日志：登录日志与操作日志的查询面 + 落库端口实现
└── api/      跨模块出口：只有 UserBriefPort 一个契约
```

每个子域内部固定五层，层名即职责，没有第六层：

```
controller/  HTTP 契约：路径、权限码、@Operation、@OperLog。不含业务分支
service/     业务口径与守护规则。事务、缓存失效、跨表编排都在这层
entity/      表映射 PO，继承 framework-db 的 AuditedEntity / TenantAuditedEntity
mapper/      MyBatis-Plus BaseMapper 子接口，不写 XML
dto/         record 形态的出入参与查询对象（VO 出参、*Request 入参、*Query 条件）
```

两条机械护栏由 `snail-platform-start` 的 ArchUnit 测试在 `mvn package` 时执行，违反即构建失败：

1. **子域之间只准走登记在案的白名单边**，且 `dto` 契约任何方向都不互借。存量边：
   `user→role`（数据权限判定与授权读写）、`user→org`、`user→tenant`（归属与上限校验）、
   `role→user`（删角色引用计数）、`role→org`（档位三取子树，只准经 `OrgQueryService` 的只读方法）、
   `role→menu`（权限码解析与授权维护）、`org→role`（写侧可见性闸门）、`org→user`（删组织挂人检查）、
   `tenant→*`（开通复制种子）。新边必须先补白名单并说明理由——先改名单再改代码。
2. **一个 service 方法里出现两处以上 Mapper 写调用，该方法或所在类必须标 `@Transactional`**；
   分页对象只能经 `PageAssembler` 装配，业务层禁止直接 `new Page`。

## 四条横切主线

多租户、权限、缓存、留痕。看不懂这四条线就改不动这个模块，四条线各自的关键约束如下。

### 1. 多租户

共享库共享表 + `tenant_id` 判别列，`TenantLineInnerInterceptor` 默认给每条 SQL 注入租户条件，
`tenant_id` 由拦截器写入，业务代码既不手写也不读它（字段存在只为了结果映射）。

上下文住在 `TenantContextHolder`（ThreadLocal），三种合法的旁路各对应一类真实需求：

| 旁路 | 用途 | 本模块的调用点 |
| --- | --- | --- |
| `withoutTenant(supplier)` | 全表口径，不受当前租户圈定 | 登录名全表查重：`UserService` 建号、`TenantService.provision` 开通 |
| `asTenant(id, supplier)` | 以目标租户身份读写 | `TenantService` 开通模板复制、租户级会话失效；`UserBriefService.enabledUsersOfTenant` 跨租户取收件人 |
| `runAsEngineTenant(id, supplier)` | 流程引擎回调 | 本模块不调用；只准 workflow 的租户桥用，ArchUnit 单独钉住 |

**ignore 清单**（`application.yml`）里的表拦截器不加条件，租户边界必须由 Service 显式钉：
`sys_tenant`、`sys_user_role`、`sys_menu`、`sys_role_menu`、`sys_login_log`、`sys_oper_log`、
`sys_notify_outbound`、`sys_workflow_template`。前四张要么本身是平台级、要么是纯关系表（两端已被租户或全局目录圈定），
后两张日志表带 `tenant_id` 供筛选但平台管理员需要跨租户看。

上下文缺失时拦截器直接抛异常，不静默放行——"漏了租户条件"在这个平台是启动即失败的错误，不是慢一点的性能问题。

### 2. 权限

**权限码三段式** `<面>:<模块>:<动作>`，面随 `sys_menu.scope` 走：`1` 平台面用 `platform:`，`2` 租户面用 `system:`。
平台侧身份只认权限码，不认"租户 ID 等于几"。

**菜单即权限包**：`sys_role_menu` 授权一批菜单节点，节点上的 `perm_code` 折成权限码放进 SecurityContext。
解析实现在 [`UserPermissionService`](src/main/java/com/snail/platform/function/system/role/service/UserPermissionService.java)，
它同时是 framework-security 的 `UserAuthorityProvider` 端口实现。口径：

- **语义 A**：`role_code=admin` 且 `is_system=1` 的启用角色隐式持有全部 `scope=2` 权限码；
  平台面（`scope=1`）永远只认真实授权行——种子数据 `V4__seed_builtin_data.sql` 文末为默认租户 admin 显式补了这一段。
- 用户本人停用或已删 → 权限码整体清空，立即生效，不等令牌过期；停用角色整体剔除。
- 节点自身或任一祖先停用/被删/成环 → 退出解析（`MenuChains`）；目录与外链没有 `perm_code`，不产生权限码。

**数据权限四档**（`sys_role.data_scope`）与**两道闸**：

| 档位 | 含义 | 谓词来源 |
| --- | --- | --- |
| 1 | 全部 | `DataScopeService` 返回 `null`，不设限 |
| 2 | 本组织 | 用户 `org_id` 单点 |
| 3 | 本组织及以下 | `OrgQueryService.subtreeIds(orgId)`，走 `orgSubtree` 缓存 |
| 4 | 本人 | `create_by` 等值；没有任何可用角色时也退回到这一档 |

谓词的最终形状是 `(org_id IN (...) OR create_by = 本人)`，两个列名在 `DataScopeFilter` 上有默认值也可指定。

多角色取 **OR 并集**。消费方式是两条不同的闸，不要混：

- **开窗**（列表查询）：`DataScopeContextHolder.filter` 由 framework-db 拦截器注入 SQL 谓词；
- **定点**（单条操作）：`UserService.requireUserInScope` / `OrgService.requireOrgInScope` 先判目标在不在可见集内。

还有一条**纵向防提权**：分配角色或维护角色时，目标档位不得宽于操作者自身最宽档（`requireGrantableDataScope`）；
建号与调岗额外要求目标组织可授权。

**令牌吊销**用 `sys_user.token_version`：改密、重置、停用、删除、强制下线都会自增版本号，版本号进 JWT 声明，
下一次请求即 401。没有黑名单，吊销的代价是"下一次请求"而不是"此刻断开"。

### 3. 缓存

缓存名单点在 `snail-platform-start/src/main/resources/application.yml` 声明，业务侧不自建缓存：

```
cache-names: dictItem, menuTree, tenantConfig, orgSubtree, userPerms, userSession
caffeine.spec: maximumSize=2000,expireAfterWrite=10m
```

本模块的读写点与键口径：

| 缓存 | 键 | 生产处 | 失效点 |
| --- | --- | --- | --- |
| `dictItem` | `tenantId:dictType` | `DictService.items` | 任何字典写操作 `allEntries` 全清 |
| `menuTree` | 全量目录树单键 | `MenuService.tree` | 菜单增/改/删 `allEntries` 全清，并连带清 `userPerms` |
| `orgSubtree` | `tenantId:orgId` | `OrgQueryService.subtreeIds` | 组织新增/移动/删除 `allEntries` 全清 |
| `userPerms` | `userId` | `UserPermissionService.authoritiesOf` | 用户停用启用/删除/重新授权、角色状态或删除、菜单与授权变更 |
| `userSession` | `userId` | `UserSessionService` | 用户与组织资料、角色变更、租户资料与状态 |
| `tenantConfig` | — | 本模块未使用 | 声明位，供后续租户级配置消费 |

一个反复踩到的点：**`userSession` 以用户 ID 为键，租户或角色维度没有可点清的那一点**，
所以 `TenantService.update/changeStatus` 与 `RoleService` 的写方法只能 `allEntries=true` 全清。
角色变更选择"按角色反查用户不如全清便宜，且不会漏"。

另一个口径：**登录人快照的最小化就是吊销机制的一半**。`UserSessionService` 在用户已停用、租户不存在/停用/到期时
返回不含 `tokenVersion` 的最小快照，认证过滤器据此判会话无效——停用不必等令牌自然过期。

### 4. 留痕

操作日志的采集在框架层，落库在本模块，中间用端口解耦：

```
@OperLog(module, businessType)                 ← 注解打在 controller 写端点上
  → OperLogAspect（@Around，请求线程内组装完记录）
      耗时与操作时刻此刻定格，池线程只负责一次 insert
  → OperLogRecord → 单线程池 ThreadPoolExecutor(1, 1, ArrayBlockingQueue(2000), daemon, 满则丢弃并告警)
  → OperLogSink（framework-log 下沉的端口）
  → DbOperLogSink（本模块实现）
  → OperLogConverter（MapStruct，unmappedTargetPolicy=ERROR，字段漏映射编译期即失败）
  → sys_oper_log
```

- **脱敏**在切面侧：`SensitiveDataMasker` 按 key 名单把密码等字段的值替换成 `******` 再落库，
  所以日志详情页看到的 `param`/`result` 已是脱敏后的文本。
- **异步线程没有租户上下文**，`tenant_id`、`trace_id`、操作人、组织必须在请求线程取好随记录带入。
- 两张日志表**只增不删**，不继承 `AuditedEntity`——给日志配逻辑删除字段就是给篡改留门。
- 登录日志的写路径在 `snail-function-auth` 的登录链（`AuthService.writeLoginLog`），留痕写失败不阻断登录响应；
  本模块的 `LoginLogService` 是纯读侧。
- 查询侧显式钉租户边界：无 `platform:operlog:all` / `platform:loginlog:all` 者锁本租户（请求里的 `tenantId` 参数一概不认），
  持有者可按租户筛，不筛即跨租户全量。`tenant_id=0` 的行（登录链还没定位到用户就失败）只有平台侧看得见。

## 数据模型

11 张表，全部 DDL 在 [`V1__init_system_schema.sql`](../../snail-platform-start/src/main/resources/db/migration/V1__init_system_schema.sql)，
内置种子在 [`V4__seed_builtin_data.sql`](../../snail-platform-start/src/main/resources/db/migration/V4__seed_builtin_data.sql)。

| 表 | 实体基类 | 拦截器 | 唯一键 | 说明 |
| --- | --- | --- | --- | --- |
| `sys_tenant` | `AuditedEntity` | ignore | `uk_tenant_code(tenant_code)` | 平台级，全量只对平台管理员可见 |
| `sys_user` | `TenantAuditedEntity` | 生效 | `uk_user_username(username)` | 全表唯一；`token_version` 是吊销开关 |
| `sys_org` | `TenantAuditedEntity` | 生效 | — | `ancestors` 逗号路径（如 `0,1,5`），层级靠它推导不另存 |
| `sys_role` | `TenantAuditedEntity` | 生效 | `uk_role_code(tenant_id, role_code)` | 租户内唯一；`is_system=1` 内置角色管理端只读 |
| `sys_menu` | `AuditedEntity` | ignore | `uk_menu_perm_code(perm_code)` | 全局目录一份；目录与外链 `perm_code` 为 `NULL`（MySQL 唯一索引允许多个 NULL） |
| `sys_dict_type` | `TenantAuditedEntity` | 生效 | `uk_dict_type(tenant_id, dict_type)` | 租户各自维护，无平台内置与并集 |
| `sys_dict_data` | `TenantAuditedEntity` | 生效 | `uk_dict_data(tenant_id, dict_id, dict_value)` | 删类型时同事务逻辑删其数据项 |
| `sys_user_role` | 裸实体 | ignore | 主键 `(user_id, role_id)` | 纯关系表：关联即事实，不配审计字段、不带 `tenant_id` |
| `sys_role_menu` | 裸实体 | ignore | 主键 `(role_id, menu_id)` | 整包替换是物理删改 |
| `sys_login_log` | 裸实体 | ignore | 自增 `id` | 只增，无逻辑删除，时间 `DATETIME(3)` |
| `sys_oper_log` | 裸实体 | ignore | 自增 `id` | 只增，`param`/`result` 落库前必须脱敏 |

**唯一键口径（最容易出事的一条）**：唯一索引都不含 `deleted` 列，所以**被逻辑删除记录的键值终身占用、不可复用**。
删掉的用户的登录名、销户租户的 `tenant_code`、删掉的角色 `role_code`、删掉的菜单 `perm_code`、删掉的字典类型与数据项，
重新建同名都会被数据库拦下。写路径统一把 `DuplicateKeyException` 折成业务拒绝（如"登录名已存在"），不裸抛 500。

菜单三元组 `menu_type` / `target` / `scope` 建后不可改，`path` 与 `component` 的搭配口径全收在 `MenuService`：

| 字段 | 取值 |
| --- | --- |
| `menu_type` | 1 目录、2 菜单、3 按钮 |
| `target` | 1 站内（admin 菜单树）、2 数据大屏（dashboard 菜单树，admin 仅配置）、3 外链（新标签打开完整 URL） |
| `scope` | 1 平台、2 租户 |
| `visible` | 0 显示、1 隐藏（只影响导航渲染，按钮固定 0） |
| `status` | 0 正常、1 停用（停用后该节点及子树退出菜单树与权限解析） |

## 子域详细设计

### tenant — 租户

源码：[`tenant/`](src/main/java/com/snail/platform/function/system/tenant)

- **检索**：`sys_tenant` 在 ignore 清单，分页查询天然覆盖全平台，按 `tenantId` 倒序让新租户排最前；
  `name` 判空连 blank 一起收——管理页清空输入框传的是空串，放过它就拼出 `LIKE '%%'`。
- **开通（`provision`，一个事务内四步）**：
  1. 校验 `tenant_code` 全局不重复、`adminUsername` 全表不重复（`withoutTenant` 摘掉租户圈定才看得见别的租户占的名）、口令过 `PasswordPolicy`；
  2. `insert` 租户行拿到自增 `tenantId`（必须先 insert，后续每条 insert 的 `tenant_id` 由拦截器从这个上下文取）；
  3. `asTenant(newTenantId)` 窗口内复制模板：根组织（名沿用租户名，`parent_id=0` / `ancestors='0'`）、
     内置角色 `admin`（`data_scope=1`）与 `common`（`data_scope=4`）、管理员账号并绑定 `admin`；
  4. 返回新租户 ID。
- **不复制 `sys_role_menu`**：新租户开箱没有任何授权记录。新租户管理员靠语义 A 隐式持有全部租户面权限；
  平台面菜单本就不归租户管理员；`common` 能看什么由新租户管理员自己勾。
- **`actor` 口径**：`asTenant` 只切租户不切操作者，新租户根组织与首任管理员的 `create_by` 是执行开通的平台管理员——
  留痕指向开通人比指向"虚构的自创建"更有价值。
- **停用与到期**：`update` 只在"正常转停用"或"到期时间变更"时推 `token_version` 作废存量令牌；
  `changeStatus` 幂等短路（状态未变不写库），但方法上的 `@CacheEvict` 照常执行一次全清。重新启用不回填版本号，
  停用期间被作废的令牌不会复活，该租户用户必须重新登录。
- 销户（`deleted`）是平台侧运维动作，本模块不提供入口。

### org — 组织

源码：[`org/`](src/main/java/com/snail/platform/function/system/org)

一个子域两个服务，读写分开是有原因的：

- [`OrgQueryService`](src/main/java/com/snail/platform/function/system/org/service/OrgQueryService.java) 是**只读端口**：
  按 ID 取节点、子树 ID 清单（`orgSubtree` 缓存的唯一生产处）、后代行查询。
  它**刻意不开数据权限窗口**——`subtreeIds` 同时是档位三的数据源，若被权限谓词二次过滤，判定就会拿自己的结果当输入
  （自我循环），可见集还会随每次判定收缩。
- `OrgService` 管"能不能动"和"怎么动"：整棵树、新增子节点、改属性、移动、删除前置检查。
  写侧四个入口一律先过 `requireOrgInScope` 定点闸；新增按**父节点**判可见——看得见父节点才有资格往它下面挂。
- **`ancestors` 是硬约束**：新增与维护都在本服务内，移动时子树前缀整体替换 + 环校验，任何写入路径不许绕过；
  它同时是数据权限档位 3 与业务单据子树查询的地基。
- **删除前置检查**：有下级或挂着用户即拒。
- 一个由设计带来的收缩要知道：档位 2（本组织）只看得见自己那一个节点，它在下面新建的子女要等档位放宽才可见。

### user — 用户、在线与个人中心

源码：[`user/`](src/main/java/com/snail/platform/function/system/user)

- **`UserService`**：分页检索、新增（含建号即授权）、修改、重置口令、停用/启用、逻辑删除、角色全量覆盖分配。
  - `username` 全表唯一且建后不可改，查重走 `withoutTenant` 全表口径；撞库提示"登录名已存在"。
  - 新增受租户 `account_limit` 约束（锁 `sys_tenant` 行后计数，只拦新增不追认存量）。
  - 删除同事务物理清 `sys_user_role`——人不在了授权就该空。
  - **自我保护**：不允许停用或删除当前登录者自己，防管理员把自己锁在门外。
  - 七类定点操作（详情、改档、停用启用、删除、重置口令、强制下线、分配角色）必须过 `requireUserInScope`；
    建号与调岗还需目标组织可授权；分配的角色档位不得宽于操作者自身最宽档。
- **`UserSessionService`**：登录人富快照的唯一解析实现（人在 `sys_user`、组织名在 `sys_org`、租户名在 `sys_tenant`，
  角色取启用角色按 `sort`/`roleId` 升序），按用户 ID 整体缓存；同时是 framework-security 的 `LoginUserProvider` 实现。
- **`OnlineSessionService`**：framework-security 的 `SessionHeartbeatPort` 实现。每个认证请求一次心跳，
  `SETEX` 会话键（30 分钟滑动，首见时间跨心跳保留，否则算不出会话时长）+ Lua 原子 `SADD`/`EXPIRE` 租户索引；
  异常全吞、告警每 100 次限频一条——Redis 抖动不阻断业务请求。列表按索引取人、内存分页，索引里残留的过期成员当不在线过滤掉。
  **强制下线 = `token_version` 自增（真正的吊销）+ 会话键清理（列表即时消失）**，双保险。
- **`PasswordPolicy`**：口令强度的服务端单点口径——长度 8-64、至少命中两类字符、拒绝常见弱口令黑名单。
  新增用户、重置口令、自助改密、租户开通四条写路径共用。上限 64 的理由：BCrypt 只在 72 字节内可靠生效，
  更长口令会被静默截断，与其让用户以为设了长口令，不如在入口拒掉。DTO 的 `@Size` 只做同区间兜底。
- **`ProfileService`**：本人档案、自助改资料（只改白名单字段）、自助改密、下线自己。
  改密防爆破：原密码错按用户计数锁定（5 次 / 10 分钟，`SnailRateGuard` 原子计数）；
  改密成功 = 口令更新 + `token_version` 自增同语句 + `userSession` 失效，全部存量令牌下一次请求即 401。
  自助是本人操作，不走数据权限闸（自己的行恒可见）。
- **`UserBriefService`**：`UserBriefPort` 的唯一实现，见[跨模块契约](#跨模块契约)。

### role — 角色、授权与权限解析

源码：[`role/`](src/main/java/com/snail/platform/function/system/role)

四个服务各管一件事，边界写在类注释里：

- `RoleService`：角色 CRUD 与状态。`role_code` 建后不可改（接口契约里干脆没有它）；
  `is_system=1` 内置角色对管理端只读，改/停/删一律拒绝；被 `sys_user_role` 引用的角色不可删；
  逻辑删除后 `role_code` 终身占用。变更后 `userPerms` 与 `userSession` 整体失效。
- `DataScopeService`：把当前登录者的多角色 `data_scope` 折成一条 `DataScopeFilter`（多角色 OR 并集，停用角色不参与）。
  **只产谓词对象，不改 SQL**——注入点在 framework-db 拦截器。另外对外提供两个判定口：操作者最宽档、目标组织可授权性。
- `RoleMenuService`：角色授权树、整包授权、当前用户菜单树。
  当前用户菜单树住在这里而不是 menu 子域：它以"用户被授了哪些菜单"为事实源，菜单子域不得反向依赖角色。
  **分面合并**：持有 `platform:role:assign-menu` 才可提交平台面菜单；否则只替换租户面授权，平台面既有授权原样保留——
  租户管理员无法顺手抹掉平台授权。
- `UserPermissionService`：权限码解析（framework-security 的 `UserAuthorityProvider`），口径见[主线 2](#2-权限)。
- `MenuChains`：菜单生效链判定——节点自身与全部祖先都正常才算生效，父节点缺失、被逻辑删或成环一律失效。

### menu — 全局菜单目录

源码：[`menu/`](src/main/java/com/snail/platform/function/system/menu)

- 平台维护一份全局目录，租户只经 `sys_role_menu` 授权，**不持有副本**，所以 `sys_menu` 在 ignore 清单。
- `MenuService` 收全部搭配口径：三元组与 `scope` 建后不可改；内置节点（`is_system=1`）不可删、不可停用；有下级不可删。
- 写操作清 `menuTree` 与 `userPerms` 两个缓存——权限码可能随节点状态变化。
- `GET /system/menu/current` 是任何登录用户都可打的读端点，按 `target` 取各自主站的菜单树；
  它不缓存菜单树，授权变更立即反映到导航。

### dict — 字典

源码：[`dict/`](src/main/java/com/snail/platform/function/system/dict)

- 类型 + 数据项两级，租户各自维护，无平台内置、无并集、无默认种子。
- 守护口径：`dict_type` 建后不可改、租户内唯一（含已删记录）；`dict_value` 建后不可改、同类型内唯一（含已删记录）；
  停用类型或停用数据项都不进入下拉解析。删类型同事务逻辑删其数据项。
- 查询条件与可空列共用一把尺子：**空串一律折成 `null`**——前者据此不拼 `like`，后者据此把"清空"落库。
- `dictItem` 缓存键是 `tenantId:dictType`，任何字典写操作 `allEntries` 全清。
- `GET /system/dict/items/{dictType}` 只要求登录，是给任意业务表单取下拉项用的通用出口。

### log — 审计日志

源码：[`log/`](src/main/java/com/snail/platform/function/system/log)

纯读侧 + 一个写端口实现，管线细节见[主线 4](#4-留痕)。

- `DbOperLogSink`：framework-log 的 `OperLogSink` 实现，经 `OperLogConverter` 映射进 `sys_oper_log`。
- `OperLogService`：分页、详情取证、xlsx 导出。排序按自增 `id` 倒序——同一毫秒的多条日志翻页不会漂。
- `LoginLogService`：分页与导出。**没有详情端点**，因为一行登录日志本身就装得下全部事实。
- 导出是**先 count 再取**：超过 5 万行直接拒绝并引导缩小时间范围，而不是悄悄截断。
  导出用的谓词与列表页完全同源（`buildWrapper` 一处实现），列表看到什么就能导出什么。
  导出动作本身也挂 `@OperLog`——谁导了什么，日志里有痕迹。

## 接口与权限码总表

路径全部以 `/system` 开头（与管理端路由前缀同源，dev 环境由 Vite 代理转发，无额外 API 前缀）。
响应统一信封 `R{code, message, data}`，成功 `code=0`；分页载体 `PageResult{rows, total}`，页码从 1 起。
分页有两层上限：各 `*Query` 的 `@Max(200)` 是契约层校验，framework-db 的 `pagination.max-limit: 500` 是 SQL 层兜底。
业务失败经 `BizException.badRequest` 抛出，由 `GlobalExceptionHandler` 折成信封。

| 子域 | 方法 路径 | 权限码 | 说明 |
| --- | --- | --- | --- |
| 租户 | `GET /system/tenant/page` | `platform:tenant:list` | 全平台租户分页 |
| | `POST /system/tenant` | `platform:tenant:create` | 开通租户（模板复制） |
| | `PUT /system/tenant/{tenantId}` | `platform:tenant:update` | 修改资料（整份覆盖） |
| | `PUT /system/tenant/{tenantId}/status` | `platform:tenant:update` | 停用/启用 |
| 组织 | `GET /system/org/tree` | `system:org:list` | 当前可见集内的整棵树 |
| | `GET /system/org/{orgId}/subtree` | `system:org:list` | 子树 ID 清单（含自身） |
| | `POST /system/org` | `system:org:create` | 新增子节点 |
| | `PUT /system/org/{orgId}` | `system:org:update` | 改名/排序/停用启用 |
| | `PUT /system/org/{orgId}/move` | `system:org:update` | 移动（`ancestors` 整体重写 + 环校验） |
| | `DELETE /system/org/{orgId}` | `system:org:delete` | 有下级或挂用户即拒 |
| 用户 | `GET /system/user/page` | `system:user:list` | 按 data_scope 开窗 |
| | `GET /system/user/{userId}` | `system:user:list` | 详情含授权角色集 |
| | `POST /system/user` | `system:user:create` | 新增（含建号即授权，受 `account_limit`） |
| | `PUT /system/user/{userId}` | `system:user:update` | 修改 |
| | `PUT /system/user/{userId}/password` | `system:user:reset-password` | 重置口令 |
| | `PUT /system/user/{userId}/status` | `system:user:update` | 停用/启用（不许对自己） |
| | `POST /system/user/{userId}/force-logout` | `system:user:kick` | 强制下线 |
| | `DELETE /system/user/{userId}` | `system:user:delete` | 逻辑删（同事务清授权） |
| | `PUT /system/user/{userId}/roles` | `system:user:assign-role` | 分配角色（全量覆盖） |
| 角色 | `GET /system/role/page` | `system:role:list` | 分页 |
| | `POST /system/role` | `system:role:create` | 新增（档位不得宽于操作者） |
| | `PUT /system/role/{roleId}` | `system:role:update` | 修改 |
| | `PUT /system/role/{roleId}/status` | `system:role:update` | 停用/启用 |
| | `DELETE /system/role/{roleId}` | `system:role:delete` | 内置或被引用即拒 |
| | `GET /system/role/{roleId}/menus` | `system:role:list` | 授权树（含 checked） |
| | `PUT /system/role/{roleId}/menus` | `system:role:assign-menu` | 整包授权；平台面节点二次要求 `platform:role:assign-menu` |
| 菜单 | `GET /system/menu/tree` | `platform:menu:list` | 全量目录树（平台维护面） |
| | `POST /system/menu` | `platform:menu:create` | 新增节点 |
| | `PUT /system/menu/{menuId}` | `platform:menu:update` | 修改（三元组与权限码不可改） |
| | `DELETE /system/menu/{menuId}` | `platform:menu:delete` | 内置或有下级即拒 |
| | `GET /system/menu/current` | 任何登录用户 | 当前用户菜单树（`target=1` 站内 / `2` 大屏） |
| 字典 | `GET /system/dict/type/page` | `system:dict:list` | 类型分页 |
| | `POST /system/dict/type` | `system:dict:create` | 新增类型 |
| | `PUT /system/dict/type/{dictId}` | `system:dict:update` | 修改（`dict_type` 不可改） |
| | `DELETE /system/dict/type/{dictId}` | `system:dict:delete` | 级联逻辑删数据项 |
| | `GET /system/dict/data/page` | `system:dict:list` | 数据分页（须指定类型） |
| | `POST /system/dict/data` | `system:dict:create` | 新增数据 |
| | `PUT /system/dict/data/{dictDataId}` | `system:dict:update` | 修改（`dict_value` 不可改） |
| | `DELETE /system/dict/data/{dictDataId}` | `system:dict:delete` | 删除 |
| | `GET /system/dict/items/{dictType}` | 任何登录用户 | 启用项下拉 |
| 日志 | `GET /system/log/login/page` | `system:loginlog:list` | 分页；跨租户需 `platform:loginlog:all` |
| | `GET /system/log/login/export` | `system:loginlog:export` | xlsx，超 5 万行拒 |
| | `GET /system/log/oper/page` | `system:operlog:list` | 分页；跨租户需 `platform:operlog:all` |
| | `GET /system/log/oper/{id}` | `system:operlog:list` | 详情（`param`/`result` 已脱敏全文） |
| | `GET /system/log/oper/export` | `system:operlog:export` | xlsx，超 5 万行拒 |
| 在线 | `GET /system/online/page` | `system:online:list` | 本租户在线列表 |
| | `POST /system/online/{userId}/kick` | `system:online:kick` | 强制下线 |
| 个人中心 | `GET /system/profile` | 任何登录用户 | 本人档案 |
| | `PUT /system/profile` | 任何登录用户 | 改昵称/邮箱/手机号 |
| | `POST /system/profile/password` | 任何登录用户 | 改口令（成功后全设备重登） |
| | `POST /system/profile/force-logout` | 任何登录用户 | 下线自己的全部会话 |

内置权限码的种子清单在 `V4__seed_builtin_data.sql`；文件面与流程面的菜单权限码在 `V5`、`V6`，种子按域分文件、互不追加历史脚本。

## 跨模块契约

`api` 包是模块对外的唯一出口，目前只有一个契约：

```java
// com.snail.platform.function.system.api.UserBriefPort
List<UserBrief> briefs(Collection<Long> userIds);   // 当前租户口径；不存在/跨租户的 ID 直接缺席，不报错
List<UserBrief> enabledUsersOfTenant(Long tenantId); // 平台公告扇出；租户不存在/停用抛业务拒绝
List<Long> enabledUserIdsOfRole(Long roleId);        // 流程待办按候选角色扇出；无人即空集
```

`UserBrief` 是**发送时刻的快照原料**：`notify` / `workflow` 拿到后自行落快照列，不回查用户档案——
三年后翻出一条公告的收件人，显示的仍应是当时那个人叫什么。
`email`/`phone` 属出站通道寻址，只在平台内部流转，不进任何对外接口出参。

新增跨模块契约时：接口进 `api` 包，实现留在子域内，并在 `ArchitectureRulesTest` 的 `API_PACKAGE_ENFORCED` 上登记调用方模块。
存量例外（`auth→system` 整体豁免、`storage` 直读 `SysTenantMapper` 取存储配额）是登记在案的债务，不是可以效仿的先例。

## 使用方法

### 构建与启动

模块自身不产出可执行 jar，必须经 `snail-platform-start` 装配：

```bash
cd snail-platform-backend
mvn clean install                                    # 含 ArchUnit 纪律校验
mvn -pl snail-platform-start spring-boot:run          # dev profile，连本机 MySQL + Valkey/Redis
```

单模块快速编译（不跑测试，改动本模块后先过这一关）：

```bash
mvn -q -pl snail-platform-function/snail-function-system -am -DskipTests compile
```

首次启动 Flyway 会依次执行 `V1`（系统建表）→ `V2`（文件）→ `V3`（流程）→ `V4`（系统种子）→ `V5` → `V6`。
`dev` profile 下 admin 口令用开发占位密文（即 `admin123` 的 BCrypt）；`prod` 必须提供 `SNAIL_SEED_ADMIN_PASSWORD`
（**BCrypt 密文而非明文**，用与业务侧一致的 Hutool `BCrypt.hashpw(明文)` 离线生成），缺失则迁移失败、启动被拦。

### 调用一个端点

拿到令牌后直接带 Bearer 头。登录与验证码由 `snail-function-auth` 提供，同样挂在 `/system/auth` 前缀下
（`GET /system/auth/captcha` 拿 `captchaKey`，`POST /system/auth/login` 出参字段是 `token`）：

```bash
TOKEN=$(curl -s http://localhost:8080/system/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123","captchaKey":"...","captchaCode":"..."}' | jq -r .data.token)

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/system/user/page?pageNum=1&pageSize=20&status=0" | jq .
```

查询入参是 record 形态的 `*Query`，`implements` framework-core 的 `PageQuery`（只约定"有页码、有每页条数"，
默认值与校验留在各域契约里，装配由 `PageAssembler` 单点完成）；空串会被服务端折成 `null`，不会拼出 `LIKE '%%'`。

### 前端对应入口

| 后端子域 | API 模块 | 页面 |
| --- | --- | --- |
| 用户 / 组织 / 角色 / 字典 | `src/api/{user,org,role,dict}.ts` | `src/views/system/{user,role,org,dict}` |
| 菜单路由 | `src/api/menu.ts` | `src/views/platform/menu` |
| 租户 | `src/api/tenant.ts` | `src/views/platform/tenant` |
| 登录/操作日志 | `src/api/log.ts` | `src/views/system/{loginlog,operlog}` |
| 在线用户 | `src/api/online.ts` | `src/views/system/online` |
| 个人中心 | `src/api/profile.ts` | `src/views/profile` |

前端三条硬约定：所有请求走唯一 axios 出口 `src/api/http.ts`（`get/post/put/del/download`，不得自建实例或直接用 `fetch`）；
列表页复用 `usePageList` 组合式函数；按钮用 `v-permission="'system:user:create'"`，需要参与逻辑时用 `auth.has(code)`。
导出走 `download()`，它处理 blob 与"后端返回的是 JSON 错误信封而非文件"这条失败路径。

### 新增一个子域（checklist）

1. 建包 `system/<子域>/{controller,entity,mapper,dto,service}`，实体按租户归属选 `TenantAuditedEntity` 或 `AuditedEntity`。
2. 建表脚本：系统域的列与索引追加进 `V1`；若已在跑过的环境上线，另起增量脚本，不改历史脚本的正文（Flyway 校验和）。
3. 权限码按三段式命名，菜单与按钮节点写进 `V4` 种子（平台面节点记得补 `sys_role_menu`，或依赖文末的 `scope=1` 全选段自动覆盖）。
4. Service 层定口径：唯一键冲突折业务拒绝、写侧过定点闸、列表侧开窗、失效点标 `@CacheEvict`。
   两处以上 Mapper 写调用必须显式 `@Transactional`，否则 ArchUnit 拦下。
5. Controller 只放契约：`@PreAuthorize` 权限码、`@Operation` 摘要、写端点挂 `@OperLog(module, businessType)`。
6. 需要跨子域读别的子域？先往 `ArchitectureRulesTest.ALLOWED_SYSTEM_EDGES` 加边并写清理由，再改代码。
7. 前端补 `src/api/<子域>.ts` + `src/views/...`，菜单节点的 `component` 字段填的就是这个视图路径。

### 给业务模块加数据权限

业务表带 `tenant_id`、`org_id` 与 `create_by`（继承 `AuditedEntity` / `TenantAuditedEntity` 即自带后两列）即可复用本模块的判定：
列表查询经 `DataScopeContextHolder.filter` 让 framework-db 拦截器注入谓词；单条操作调 `DataScopeService` 的判定口自证可见性。
列名不是 `org_id`/`create_by` 时构造 `DataScopeFilter` 显式指定——列名走小写下划线白名单校验，ID 一律数值，没有注入面。
不要自己拼角色 SQL——档位口径只有一份，复制一份就会漂移一份。

## 验证手段

集成测试都住在 `snail-platform-start`（唯一装配点，能起完整上下文），与本模块相关的：

| 测试 | 覆盖的主线 |
| --- | --- |
| `system/SysManagementFlowTest` | 租户/组织/用户/角色 CRUD 主链路 |
| `system/DataScopeFlowTest`、`DataScopeGuardFlowTest` | 四档数据权限的开窗与定点闸 |
| `system/PermissionFlowTest`、`RoleMenuFlowTest`、`MenuManagementFlowTest` | 权限码解析、语义 A、分面授权 |
| `system/DictManagementFlowTest` | 字典两级守护与下拉解析 |
| `system/LoginUserFlowTest`、`OperLogFlowTest` | 登录人快照端口、操作日志落库 |
| `system/QueryContractFlowTest` | 查询契约（空串折 null、分页上限） |
| `system/SelfSecurityFlowTest` | 自我保护（不许停用自己/删自己） |
| `auth/LoginFlowTest`、`AuthSessionFlowTest` | 登录链与本模块端口的接缝 |
| `framework/SensitiveDataMaskerTest` | 日志脱敏 |
| `architecture/ArchitectureRulesTest`、`SourceDisciplineTest` | 分层与源文件纪律 |

```bash
mvn -q -pl snail-platform-function/snail-function-system -am -DskipTests compile   # 本模块编译
mvn -pl snail-platform-start test                                                  # 纪律 + 行为，一条命令全跑
cd ../snail-platform-frontend-admin && npx vue-tsc -b                               # 前端类型
```

`*FlowTest` 都带 `@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME")`：它们要本机 MySQL 与 Valkey，
并注入 `SNAIL_DB_*` 口令；**变量没设时整类直接跳过而不是失败**，所以"测试全绿"之前要先确认它们真的跑了。
ArchUnit 与源文件纪律不依赖外部服务，离线也能拦。

## 已知边界与注意事项

改这个模块之前先读这一节，都是源码里读出来的实质约束，不是待办清单。

1. **唯一键不随逻辑删除释放**。想复用旧登录名 / `tenant_code` / `role_code` / `perm_code` / 字典键，只能由 DBA 手工清行；
   业务侧一律表现为"已存在"，这是口径而非 bug。
2. **`userSession` 键粒度是用户**，所以租户维度与角色维度的变更只能 `allEntries` 全清。
   改一次租户资料就清一次全站会话快照缓存——低频操作，可接受，但别误以为它按租户点清。
3. **`@CacheEvict` 在幂等短路路径上照样执行**。`changeStatus` 状态未变直接 `return`，那次请求仍会全清 `userSession`。
4. **吊销是"下一次请求生效"**，不是长连接断开。若上线 WebSocket/SSE，需要另设推送口。
5. **`POST /system/profile/force-logout` 是本模块唯一没挂 `@OperLog` 的动作端点**，其余写端点全留痕。
6. **`sys_login_log.tenant_id = 0` 的确切语义**是"登录链还没定位到用户就失败了"（验证码先行、锁定拦截、账号不存在），
   不是"归属不明的失败"。`V1` 的列注释仍写着旧措辞，历史脚本不能就地改（Flyway 校验和），以本节为准。
   同一个陈旧指代也在 `TenantService.provision` 的注释里：它写"复制 V2 种子模板"，迁移基线重排后系统种子是 `V4`。
7. **`tenantConfig` 缓存在名单里声明但本模块无读写点**，是给后续租户级配置留的位；新增缓存名必须改
   `application.yml` 单点声明，不要在业务侧自建。
8. **分页与导出各有硬阈值**：`pageSize` 契约层 `@Max(200)`、SQL 层 `pagination.max-limit: 500`；
   日志导出行数上限 5 万（超限拒绝，引导缩小时间范围）。
   操作日志异步队列 `ArrayBlockingQueue(2000)`，满则丢弃并告警——留痕不是强一致承诺，别把它当账本。
9. **数据权限档位 2 会"看不见自己新建的下级"**：`requireOrgInScope` 按父节点判可见，新增成功的子女要等档位放宽才出现在树里。
   这是设计收缩，报告给用户比放宽判定更划算。
10. **语义 A 的双面性**：租户面隐式全量意味着"新增租户面菜单节点不用给谁补授权"；
    平台面只认授权行意味着**新增 `scope=1` 节点后必须给平台管理员补 `sys_role_menu`**，否则连平台管理员自己都点不到。
11. **跨模块边只有两笔登记在案的存量**：`auth→system`（整体豁免，登录链直取本模块的端口实现）与
    `storage→tenant`（直读 `SysTenantMapper` 取存储配额的单一只读边）。本模块对外只经 `api` 包给契约。
    想长出第三笔边需要走人工裁决，ArchUnit 不会自动放行——它防的就是"第二条边悄悄长出来"。

## License

[MIT](../../../LICENSE)
