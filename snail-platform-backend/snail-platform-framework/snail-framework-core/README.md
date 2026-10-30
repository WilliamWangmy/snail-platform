# snail-framework-core

平台的技术地基：统一响应信封、业务异常、分页契约、租户与登录人上下文、traceId、HTTP 异常翻译与缓存总闸。

所有 framework 构件与业务模块都依赖它；它不依赖任何业务模块，也不知道任何表、端点或业务文案。

- 所属工程：[`snail-platform-backend`](../../pom.xml)
- 基础包名：`com.snail.platform.framework.core`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [业务拒绝与 HTTP 状态](#业务拒绝与-http-状态)
  - [分页口径](#分页口径)
  - [请求级上下文](#请求级上下文)
  - [traceId](#traceid)
  - [异常翻译](#异常翻译)
  - [缓存总闸](#缓存总闸)
- [详细设计：按类](#详细设计按类)
- [配置项](#配置项)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- `R<T>` 响应信封：`code / message / data`，成功码恒为 `0`；
- `BizException` 业务异常及 400/401/403 快捷工厂；
- 分页契约：`PageQuery`（入参型抽象）与 `PageResult`（出参载体）；
- 登录人快照 `LoginUser` 与请求级持有 `LoginUserContextHolder`；
- 租户身份 `TenantContextHolder`，含三类受控旁路窗口；
- `TraceFilter`：每请求 traceId 的生成、沿用、MDC 与响应头；
- `GlobalExceptionHandler`：HTTP 边界异常到 `R` 的唯一翻译；
- `SnailCacheConfig`：Spring Cache 注解体系的总开关。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 分页对象装配、排序白名单 | `snail-framework-db` 的 `PageAssembler` |
| 租户条件注入 SQL | `snail-framework-db` 的租户拦截器 |
| 登录人快照的解析来源 | `snail-framework-security` + `LoginUserProvider` 实现 |
| 401/403 的过滤链输出 | `snail-framework-security` |
| 缓存实现、缓存名单与 TTL | `snail-platform-start` 的 `application.yml` |
| 操作留痕 | `snail-framework-log` |

## 在平台中的位置

```
Controller ──返回 R<T>──────────────┐
Service ──抛 BizException───────────┤
                                     ▼
                          GlobalExceptionHandler ──▶ R<T> JSON
                                     
SnailAuthFilter（security）
   ├─ TenantContextHolder.set(tenantId) ──▶ framework-db 租户拦截器
   ├─ LoginUserContextHolder.set(快照)  ──▶ framework-db 审计填充 / framework-log 操作人
   └─ LoginUser.roles                  ──▶ framework-workflow 候选角色判定

TraceFilter ──▶ MDC.traceId ──▶ logback %X{traceId} / OperLogRecord.traceId
```

依赖方向：`core ← db / redis / security / log / excel / storage / workflow ← function-* ← start`。
本模块只引 Spring Web/Validation/Cache、Caffeine、swagger-annotations 与 Hutool，不引安全、数据库、Flowable。

## 包结构

```
com.snail.platform.framework.core
├── config/
│   └── SnailCacheConfig.java        @EnableCaching 总闸
├── context/
│   ├── LoginUser.java               登录人请求级富快照
│   ├── LoginUserContextHolder.java  快照的 ThreadLocal 持有
│   └── TenantContextHolder.java     租户 ThreadLocal + 三类旁路窗口
├── exception/
│   └── BizException.java            业务异常（code + 可展示文案）
├── query/
│   └── PageQuery.java               分页入参型抽象
├── response/
│   ├── R.java                       统一响应信封
│   └── PageResult.java              分页结果载体
└── web/
    ├── GlobalExceptionHandler.java  异常 → R
    └── TraceFilter.java             traceId 过滤器
```

## 整体设计

### 业务拒绝与 HTTP 状态

平台对前端的契约是 **业务拒绝走 HTTP 200 + 非 0 code**，只有传输层与鉴权层的失败才落到真实状态码：

| 场景 | HTTP | code | 出口 |
| --- | --- | --- | --- |
| 业务规则拒绝、参数校验失败 | 200 | 400 | `BizException` / 校验异常 → `GlobalExceptionHandler` |
| 令牌无效/过期 | 401 | 401 | 安全链入口点（security 模块） |
| 权限码不满足 | 403 | 403 | `SecurityExceptionHandler` / 安全链入口点 |
| 路由不存在 | 404 | 404 | `NoResourceFoundException` |
| 请求方法不支持 | 405 | 405 | `HttpRequestMethodNotSupportedException` |
| 未预期异常 | 500 | 500 | `GlobalExceptionHandler.handleUnknown` |

这样前端拦截器可以用 HTTP 状态判断"接口是否挂上"，用 `code` 判断"业务是否被拒"。
改动这一口径会让全部既有断言与拦截器重写，且不带来安全收益。

### 分页口径

- **入参型**：`PageQuery` 只约定 `pageNum()`（从 1 起）与 `pageSize()`，具体默认值与上限由各域 `XxxQuery` 的紧凑构造器声明；
- **出参型**：`PageResult` 只承载 `rows` 与 `total`，页码与条数属于入参，不回显；
- **装配点**：`PageAssembler.from(PageQuery)`（db 模块）是 MyBatis-Plus `Page` 的唯一入口，后续深翻保护、上限调整只挂这一处；
- **上限**：各域 Query 校验 200；db 的分页拦截器另有 `snail.db.pagination.max-limit`（默认 500）作为兜底夹紧。

### 请求级上下文

两份 ThreadLocal 由认证过滤器在请求线程注入、请求收尾清理：

| 持有处 | 内容 | 消费方 |
| --- | --- | --- |
| `LoginUserContextHolder` | 登录人快照（用户/租户/组织/角色/待改密标记） | db 审计填充、log 操作人、业务展示与自助保护 |
| `TenantContextHolder` | 租户 ID + 旁路窗口标记 | db 租户拦截器、workflow 引擎租户、log 落库 |

`TenantContextHolder` 的旁路窗口是受控静音，不是通用开关：

| 方法 | 允许的路径 | 语义 |
| --- | --- | --- |
| `withoutTenant(action)` | 登录按全局唯一键定位用户；新增/改名按 username 全表查重；平台面跨租户读侧 | 关闭租户过滤 |
| `asTenant(tenantId, action)` | 租户开通播种、通知/公告按收件租户落库、跨租户人员摘要、租户级会话失效 | 临时切换为指定租户 |
| `runAsEngineTenant(tenantId, action)` | 仅 `WorkflowTenantBridge`，ArchUnit 锁定调用点 | 引擎异步线程反查实例租户后执行回调 |

三者都在 `finally` 恢复原状态；`runAsEngineTenant` 拒绝空租户 ID，宁失败不落错家。

### traceId

- 请求头 `X-Trace-Id` 带值且形如 `[A-Za-z0-9-]{8,64}` 才沿用，否则生成 UUID（`IdUtil.fastSimpleUUID`）；
- 写入 MDC 键 `traceId`（与 `logback-spring.xml` 的 `%X{traceId}` 对齐），并回写响应头；
- 请求结束必清 MDC，避免线程复用串号；
- 可沿用的形状校验挡住"把换行/控制字符写进日志行"的伪造日志攻击，失败只换 ID、不拒绝请求。

### 异常翻译

`GlobalExceptionHandler` 覆盖：

| 异常 | 结果 |
| --- | --- |
| `BizException` | code 原样透传，HTTP 200 |
| `MethodArgumentNotValidException` | 400，取第一条字段错误，文案带字段名 |
| `HandlerMethodValidationException` / `ConstraintViolationException` | 400，取第一条消息 |
| `HttpMessageNotReadableException` | 400，请求体格式不正确 |
| `MissingServletRequestParameterException` | 400，点名缺失参数 |
| `MethodArgumentTypeMismatchException` | 400，点名参数与类型问题 |
| `MaxUploadSizeExceededException` | 400，文件超限（multipart 阈值先于业务配额） |
| `HttpRequestMethodNotSupportedException` | 真实 405 |
| `NoResourceFoundException` | 真实 404 |
| 其余 `Exception` | 500，记 error 日志与堆栈，响应体不暴露内部细节 |

`AccessDeniedException` 由 security 模块的 `SecurityExceptionHandler` 处理（core 不依赖 Spring Security）。

### 缓存总闸

`SnailCacheConfig` 只做一件事：`@EnableCaching`。缓存类型、名单、TTL 全部在 `application.yml` 单点声明，
避免出现第二处配置来源。当前名单：`dictItem, menuTree, tenantConfig, orgSubtree, userPerms, userSession`，
规格 `maximumSize=2000,expireAfterWrite=10m`。

## 详细设计：按类

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| [`R`](src/main/java/com/snail/platform/framework/core/response/R.java) | 统一响应信封 | 成功码 `0`；`fail` 只给业务码与可展示文案 |
| [`PageResult`](src/main/java/com/snail/platform/framework/core/response/PageResult.java) | 分页结果 | 只含 `rows/total`，不含页码 |
| [`BizException`](src/main/java/com/snail/platform/framework/core/exception/BizException.java) | 业务异常 | 系统级故障不用本类；`badRequest/unauthorized/forbidden` 固定 400/401/403 |
| [`PageQuery`](src/main/java/com/snail/platform/framework/core/query/PageQuery.java) | 分页入参型抽象 | accessor 用包装类型，未传为 null，默认值在域内紧凑构造器落 |
| [`LoginUser`](src/main/java/com/snail/platform/framework/core/context/LoginUser.java) | 登录人富快照 | `roles` 恒非 null 且不可变；不参与判权；`tokenVersion` 是吊销比对凭据 |
| [`LoginUserContextHolder`](src/main/java/com/snail/platform/framework/core/context/LoginUserContextHolder.java) | 快照持有 | 请求收尾必须 `clear`；异步线程不自动继承 |
| [`TenantContextHolder`](src/main/java/com/snail/platform/framework/core/context/TenantContextHolder.java) | 租户持有与旁路 | 三类旁路各有白名单用途；恢复时区分"原本有值/无值" |
| [`TraceFilter`](src/main/java/com/snail/platform/framework/core/web/TraceFilter.java) | traceId | 最高优先级；MDC 键与日志 pattern 两处只能一起改 |
| [`GlobalExceptionHandler`](src/main/java/com/snail/platform/framework/core/web/GlobalExceptionHandler.java) | 异常翻译 | 业务拒绝 200；传输/鉴权失败才用真实状态码 |
| [`SnailCacheConfig`](src/main/java/com/snail/platform/framework/core/config/SnailCacheConfig.java) | 缓存开关 | 不建 `CacheManager`、不配缓存条目 |

## 配置项

本模块没有自己的 `@ConfigurationProperties`。相关配置由 `snail-platform-start` 声明：

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `spring.cache.type` | `caffeine` | 缓存实现 |
| `spring.cache.cache-names` | `dictItem, menuTree, tenantConfig, orgSubtree, userPerms, userSession` | 缓存名单点 |
| `spring.cache.caffeine.spec` | `maximumSize=2000,expireAfterWrite=10m` | 统一淘汰规格 |

## 使用方式

```java
// Controller 成功响应
return R.ok(userVO);
return R.ok();

// Service 业务拒绝（HTTP 200 + code 400）
throw BizException.badRequest("用户名已存在");

// 分页入参
public record UserQuery(Integer pageNum, Integer pageSize, String keyword) implements PageQuery {
    public UserQuery {
        pageNum = pageNum == null || pageNum < 1 ? 1 : pageNum;
        pageSize = pageSize == null ? 10 : Math.min(pageSize, 200);
    }
}

// 当前登录人（只在请求线程上有值）
Long userId = LoginUserContextHolder.userId();
LoginUser me = LoginUserContextHolder.get();
```

## 验证手段

- 本模块无独立单测；响应/异常/分页契约由 `snail-platform-start` 的流程测试间接覆盖
  （如 `QueryContractFlowTest`、`SysManagementFlowTest`）；
- 接口文档与端点清单由 `ApiDocsSmokeTest` 校验；
- 全量回归：在 `snail-platform-backend` 执行 `mvn test`。

## 已知边界与注意事项

- **没有事件信封**：平台当前不存在领域事件/消息信封构件，不要按旧描述去找；
- **业务拒绝不是 HTTP 错误**：业务失败是 200 + code，只有传输/鉴权层失败才是 4xx/5xx；
- **ThreadLocal 不跨线程**：虚拟线程、异步任务、日志落库线程都不会自动继承登录人与租户，
  必须先在请求线程捕获，再显式传参或在受控窗口内执行；
- **旁路窗口不是通用能力**：新增 `withoutTenant/asTenant` 调用点前先确认属于已登记的三类路径；
- **traceId 不自动传播到异步任务**：需要跨线程追踪时由调用方自行捕获 MDC 值再恢复；
- **`LoginUser` 不参与判权**：权限码每请求经 `UserAuthorityProvider` 解析，快照里的角色只用于展示与候选组匹配。
