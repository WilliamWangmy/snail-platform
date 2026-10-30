# snail-framework-log

操作日志的采集层：`@OperLog` 注解、环绕切面、敏感字段脱敏与落库端口。

它不落库：留痕记录经 `OperLogSink` 端口下沉给 `snail-function-system` 的 log 子域；traceId 由
`snail-framework-core` 的 `TraceFilter` 提供，本模块只消费 MDC。

- 所属工程：[`snail-platform-backend`](../../pom.xml)
- 基础包名：`com.snail.platform.framework.log`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [采集口径](#采集口径)
  - [请求线程取齐、异步线程落库](#请求线程取齐异步线程落库)
  - [record 归一化](#record-归一化)
  - [脱敏](#脱敏)
- [详细设计：按类](#详细设计按类)
- [配置项](#配置项)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- `@OperLog(module, businessType)` 打标：模块名与业务类型由打标方声明；
- 切面自动采集：租户、操作人（登录名/组织）、traceId、请求方法/URI/IP、脱敏入参、脱敏出参、
  耗时、操作时刻、成功/失败与失败原因；
- 单线程有界队列异步落库，业务永不为审计阻塞或失败；
- `record` DTO 的 JSON 归一化（Hutool 默认按 JavaBean getter 取属性，record 会序列化成 `{}`）；
- 敏感字段按名单脱敏。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 日志的持久化、查询、导出 | `snail-function-system` 的 log 子域（`DbOperLogSink` / `OperLogService`） |
| traceId 的生成与 MDC 写入 | `snail-framework-core` 的 `TraceFilter` |
| 登录日志 | `snail-function-auth`（独立的 `sys_login_log` 链路） |
| 审计列的自动填充 | `snail-framework-db` |

## 在平台中的位置

```
Controller 写操作 ──@OperLog──▶ OperLogAspect
                                   │ 请求线程：
                                   ├─ TenantContextHolder / LoginUserContextHolder / MDC
                                   ├─ request 面（方法/URI/IP）
                                   ├─ 入参 normalize + mask
                                   └─ proceed() → 结果/异常定格
                                   │ 入队（单线程、有界 2000）
                                   ▼
                             snail-oper-log 线程
                                   └─ ObjectProvider<OperLogSink>.save(record)
                                          └─ DbOperLogSink ──▶ sys_oper_log
```

## 包结构

```
com.snail.platform.framework.log
├── BusinessType.java           业务类型枚举（落库码）
├── OperLog.java                打标注解
├── OperLogAspect.java          环绕采集 + 异步落库
├── OperLogRecord.java          框架侧留痕模型（16 字段）
├── OperLogSink.java            落库端口
└── SensitiveDataMasker.java    JSON 键级脱敏
```

## 整体设计

### 采集口径

- 写操作必标；查询类接口不标（读留痕靠访问日志，不进审计表）；
- 业务类型落库码：`INSERT=1、UPDATE=2、DELETE=3、EXPORT=4、IMPORT=5、OTHER=9`；
- `status`：`1` 成功、`0` 失败（与 `sys_oper_log` 一致，**和业务表 0=正常相反**）；
- `error_msg` 超 2000 字符截断；`url` 超长截断；
- 二进制响应体（导出流）不序列化，只记 `[二进制响应]`；
- 入参跳过 Servlet 容器对象、`BindingResult`、`MultipartFile`——它们不是"入参事实"。

### 请求线程取齐、异步线程落库

- 租户、登录人、traceId、请求面、入参都在 `proceed()` 之前采完；
- 结果或异常在 `proceed()` 之后定格，整条 `OperLogRecord` 组装完才入队；
- 落库池：`ThreadPoolExecutor(1, 1)` + `ArrayBlockingQueue(2000)` + 守护线程 `snail-oper-log`；
  队列满时告警并丢弃一条（宁丢勿堵），落库异常只记应用日志；
- `OperLogSink` 每次现查 `ObjectProvider`：实现缺席（纯框架冒烟上下文）时退化丢弃并告警，
  不在构造期绑死一个可能不存在的 Bean；
- 业务异常原样外抛，切面不吞。

### record 归一化

平台 DTO 全线是 record；Hutool 5.8 的 JSON 按 JavaBean getter 取属性，record 的访问器不带 `get` 前缀，
直接 `JSONUtil.toJsonStr` 会得到 `{}`。切面对入参/出参做递归归一化：

- `record` → 组件名/值有序 Map；
- `Iterable` → List；`Map` → String 键 Map；数组 → `[数组:类型]`；
- 标量原样返回；
- 单个组件读取失败记 `[不可读]`，不让整条日志失败。

### 脱敏

`SensitiveDataMasker` 对序列化后的 JSON 做键级替换：

- 名单（整键精确、不区分大小写、不做子串）：
  `password, newPassword, oldPassword, rawPassword, adminPassword, phone, mobile, contactPhone,
  idCard, idNumber, bankCard, captchaCode`；
- 命中键的字符串值替换为 `******`；
- 直接扫原文而非反解析对象；值只吃连续非引号字符，含转义引号的异常形态整体替换，不产生半脱敏；
- 新增敏感字段只改 `SENSITIVE_KEYS` 一处。

## 详细设计：按类

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| [`OperLog`](src/main/java/com/snail/platform/framework/log/OperLog.java) | 打标注解 | 只标写操作；模块名用于列表筛选 |
| [`BusinessType`](src/main/java/com/snail/platform/framework/log/BusinessType.java) | 业务类型 | 码值与 `sys_oper_log.business_type` 注释一致 |
| [`OperLogAspect`](src/main/java/com/snail/platform/framework/log/OperLogAspect.java) | 采集与异步落库 | 请求线程取齐；单线程有界队列；队列满丢弃并告警 |
| [`OperLogRecord`](src/main/java/com/snail/platform/framework/log/OperLogRecord.java) | 留痕模型 | 字段与表对齐但不是 PO；快照值必须请求线程取好 |
| [`OperLogSink`](src/main/java/com/snail/platform/framework/log/OperLogSink.java) | 落库端口 | 实现异常由切面捕获，不外抛 |
| [`SensitiveDataMasker`](src/main/java/com/snail/platform/framework/log/SensitiveDataMasker.java) | 脱敏 | 名单单点；整键精确匹配 |

## 配置项

本模块无自有配置。运行时依赖：

| 依赖 | 来源 |
| --- | --- |
| traceId | `TraceFilter`（core）写入的 MDC 键 `traceId` |
| 落库实现 | `snail-function-system` 的 `DbOperLogSink` |
| 表结构 | Flyway `sys_oper_log` |

## 使用方式

```java
@OperLog(module = "用户管理", businessType = BusinessType.INSERT)
@PreAuthorize("hasAuthority('system:user:create')")
@PostMapping
public R<Long> create(@Valid @RequestBody UserCreateRequest request) { ... }

// 导出自身是操作，必须留痕
@OperLog(module = "操作日志", businessType = BusinessType.EXPORT)
@GetMapping("/export")
public void export(OperLogQuery query, HttpServletResponse response) throws IOException { ... }
```

业务侧不需要、也不允许手工拼装 `OperLogRecord`。

## 验证手段

- [`SensitiveDataMaskerTest`](../../snail-platform-start/src/test/java/com/snail/platform/framework/SensitiveDataMaskerTest.java)：
  名单命中、大小写、非子串、转义值等脱敏口径；
- `OperLogFlowTest`：写操作留痕落库、异步可见性、导出留痕与导出文件；
- 全量回归：在 `snail-platform-backend` 执行 `mvn test`。

## 已知边界与注意事项

- **不落库、不查询**：本模块只有采集与端口；`sys_oper_log` 的写入、分页与导出都在 function 侧；
- **可能丢日志**：队列上限 2000，满时丢弃并告警；审计要求极高的场景需要另行扩容或改同步落库；
- **异步线程无上下文**：`OperLogRecord` 的所有快照必须在请求线程取好，异步线程只做一次 insert；
- **Hutool 不序列化 record**：新增日志入参/出参时不要绕过 `normalize` 直接 JSON 化；
- **脱敏是名单而非穷举**：新增敏感字段必须同步 `SENSITIVE_KEYS`，否则明文会进 `param/result`；
- **status 语义与业务表相反**：`1=成功、0=失败`，查询与导出时别记串；
- **日志表不继承 `AuditedEntity`**：只增不改不删，给它逻辑删除字段就是给篡改留门。
