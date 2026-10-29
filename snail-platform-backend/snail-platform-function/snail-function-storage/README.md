# snail-function-storage

文件存储业务模块：`sys_file` 元数据、租户存储配额、上传校验链、按存储类型路由的下载与删除、
本租户与平台面两套分页检索。

它只管"这个文件该不该收、记在哪、谁能读、怎么取回来"；对象字节怎么落到 SFTP 目录或 S3 桶，
归 `snail-framework-storage` 的 `StoragePort`。

- 所属工程：[`snail-platform-backend`](../../pom.xml)（Maven 三层：framework / function / start）
- 基础包名：`com.snail.platform.function.storage`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [端点与准入边界](#端点与准入边界)
  - [上传校验链](#上传校验链)
  - [两段式配额](#两段式配额)
  - [按 storage_type 路由](#按-storage_type-路由)
  - [下载的双层闸](#下载的双层闸)
  - [删除的两段与残留](#删除的两段与残留)
  - [下载流与响应头](#下载流与响应头)
  - [多租户口径](#多租户口径)
- [详细设计：按类](#详细设计按类)
- [配置项](#配置项)
- [存储与索引](#存储与索引)
- [接口契约](#接口契约)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- 上传校验链：文件名清洗（去路径段、替控制字符、截断保扩展名）→ 扩展名白名单（deny-by-default）
  → 单文件大小上限 → 租户配额预检；
- 对象键的服务端生成：`{tenantId}/{yyyyMM}/{uuid}.{ext}`，用户输入永不进下载路径；
- 元数据落库与配额权威判定：锁租户行后重算占用再插入；
- 对象写成功但元数据入库失败时的补偿删除；
- 文件级可见性判定（`FileAccessPolicy` 端口与默认实现）；
- 下载、删除按元数据记录的 `storage_type` 取端口，未配置该后端时明确拒绝；
- 本租户分页与平台面跨租户检索；
- 上传人显示名在上传时刻快照落库，管理页不回查用户档案。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| SFTP/S3 协议细节、连接与超时、目录自建、寻址方式 | `snail-framework-storage` 的 `StoragePort` 实现 |
| 后端配置合法性与"半截配置拒绝启动" | `SnailStorageConfig`（装配期校验） |
| 谁有哪些权限码、`system:file:*` 从哪来 | `snail-function-system` 的 `UserPermissionService` |
| `@PreAuthorize` 判定、401/403 输出 | `snail-framework-security` |
| 租户行级过滤、逻辑删除条件、审计列、分页装配 | `snail-framework-db` |
| 租户配额列 `storage_limit_mb` 的维护 | `snail-function-system` 的 `tenant` 子域（本模块只读） |
| 病毒扫描、图片压缩、分片上传、断点续传 | 本期不提供，见[已知边界](#已知边界与注意事项) |

本模块不提供 `@Configuration` 与启动类：Service/Component 由 `snail-platform-start` 扫描装配，
存储后端的装配与配置校验全在 framework 侧。

## 在平台中的位置

```
文件管理页 ──POST /system/file/upload──▶ FileController ─▶ FileStorageService
平台文件页 ──GET  /platform/file/page ─▶ FilePlatformController ─┐
                                                                ├─▶ FileAccessPolicy（文件级可见性）
                                                                ├─▶ FileQuotaService ─┬─▶ SysTenantMapper ─▶ sys_tenant（只读，登记例外）
                                                                │                     └─▶ SysFileMapper  ─▶ sys_file SUM/INSERT
                                                                └─▶ StoragePortRegistry
                                                                       ├─ active()   当前写入端口（snail.storage.type）
                                                                       └─ of(type)   历史对象按元数据行内类型取端口
                                                                              ├─▶ SftpStoragePort ─▶ JSch/SFTP
                                                                              └─▶ S3StoragePort   ─▶ AWS SDK v2（MinIO/OSS 兼容端点）
```

两条跨模块边：

- `storage → system`：**唯一的直读**——`FileQuotaService` 直接注入 `SysTenantMapper` 读配额列并 `FOR UPDATE` 锁行。
  这条边与 `auth` 的同款豁免登记在
  [`ArchitectureRulesTest`](../../snail-platform-start/src/test/java/com/snail/platform/architecture/ArchitectureRulesTest.java)
  的规则注释里（"storage 暂不列入 `API_PACKAGE_ENFORCED`：单一只读边，变密时收敛 `system.api` 包"）；
  `pom.xml` 的注释同步登记。**新模块不要效仿这条豁免**——`notify` 与 `workflow` 已经是走 `api` 包的样板。
- `storage → framework-storage`：只经 `StoragePort` / `StoragePortRegistry` / `SnailStorageProperties`，
  不感知任何后端协议。

对象内容不在数据库里：`sys_file` 存元数据与对象键，内容经端口取。

## 包结构

```
com.snail.platform.function.storage
├── controller/
│   ├── FileController.java         /system/file 上传、分页、下载、删除
│   └── FilePlatformController.java /platform/file/page 跨租户检索
├── service/
│   ├── FileStorageService.java     校验链、键生成、补偿、路由、分页的全链编排
│   ├── FileQuotaService.java       配额两段式判定与锁行入库
│   ├── FileAccessPolicy.java       文件级可见性端口
│   └── DefaultFileAccessPolicy.java 默认可见性口径
├── entity/
│   └── SysFile.java                sys_file 元数据 PO
├── mapper/
│   └── SysFileMapper.java          BaseMapper，无自定义 SQL
└── dto/
    ├── FileQuery.java              分页入参（文件名模糊 + 业务域精确）
    └── FileVO.java                 出参投影（不含对象键）
```

## 整体设计

### 端点与准入边界

| 方法 路径 | 准入 | 留痕 | 说明 |
| --- | --- | --- | --- |
| `POST /system/file/upload` | `system:file:upload` | `@OperLog` IMPORT | `multipart/form-data`，`file` 必填，`bizType`/`bizId` 可空 |
| `GET /system/file/page` | `system:file:list` | — | 本租户分页，租户拦截器圈定 |
| `GET /system/file/{fileId}/raw` | `system:file:download` | — | 流式下载；还要再过文件级可见性 |
| `DELETE /system/file/{fileId}` | `system:file:delete` | `@OperLog` DELETE | 逻辑删 + 尽力物理删 |
| `GET /platform/file/page` | `platform:file:list` | — | 跨租户检索，读侧豁免窄窗口 |

四道闸各管一件事，顺序固定且互不替代：

1. **认证闸**（安全链）：停用用户自增 `token_version`，旧令牌在这里就 401，走不到权限码；
2. **权限码闸**（`@PreAuthorize`）：回答"这个角色能不能用文件库/能不能下载"；
3. **租户闸**（`selectById` + 拦截器）：他租户或已删的行一律读不到，按"文件不存在"拒绝——存在性即租户隐私；
4. **文件级可见性闸**（`FileAccessPolicy`）：回答"这一条能不能读"，能列不等于能读。

平台面**只有检索、没有下载端点**：平台管理员能看到各租户的文件元数据（含真实 `tenantId`），
取不到内容。这是有意收窄的读侧豁免，见[已知边界](#已知边界与注意事项)。

### 上传校验链

`FileStorageService.upload` 是唯一写入路径，顺序固定：

1. **写入端口在场检查**：`requireActivePort()`。`snail.storage.type` 为空即整体关闭，一律
   `400 文件存储未配置`，不做静默降级；
2. **非空**：`file == null || file.isEmpty()` → `上传文件为空`；
3. **文件名清洗**：取最后一个 `/` 或 `\` 之后的尾段（浏览器可能原样传全路径），
   非法字符表 `[\\/:*?"<>|]` 与控制字符逐个替成 `_`，`trim` 后为空即拒绝，
   超过 255 从**尾部**截断——从头截会把扩展名削掉；
4. **扩展名白名单**：小写无点后比对配置里的白名单；白名单为空或无扩展名一律拒绝（deny-by-default），
   报错点名实际扩展名；
5. **单文件大小**：`file.getSize() > maxSizeMb * 1MB` 拒绝。注意容器层还有一道
   `spring.servlet.multipart.max-file-size`，两处口径必须一起改；
6. **配额预检**：`quotaService.assertCapacity(size)`，不加锁，只挡明显超额；
7. **对象键生成**：`{tenantId}/{yyyyMM}/{uuid}.{ext}`，服务端拼，原文件名只进元数据；
8. **写对象**：`port.put(key, in, size, contentType)`，失败包成 `IllegalStateException`（存储侧故障，不是参数问题）；
9. **元数据入库**：填清洗后的名字、扩展名、声明类型、字节数、键、`storage_type = activeType()`、
   `bizType`（`trimToNull`）、`bizId`、上传人 ID 与显示名快照，再走 `insertWithinQuota`；
10. **补偿**：第 9 步抛任何 `RuntimeException`（配额竞态、库故障）都尽力删掉刚写入的对象键，
    然后把原异常**原样抛出**——补偿失败只 warn，绝不掩盖真实失败原因。

`contentType` 落库的是客户端声明值，**只记录不信任**：下载响应类型由服务端按扩展名映射，不看这一列。

### 两段式配额

配额判定的难点是"先查再写"天然有竞态。这里拆成两段：

| 段 | 方法 | 锁 | 目的 |
| --- | --- | --- | --- |
| 预检 | `assertCapacity(size)` | 无 | 快速拒绝明显超额，省一次对象 IO |
| 权威判定 | `insertWithinQuota(row)` | `SELECT ... FOR UPDATE` 锁租户行 | 同租户并发上传在此串行，串行后再 SUM 再 INSERT |

口径与边界：

- **额度来源**：`sys_tenant.storage_limit_mb`，`NULL` 表示不限；`tenant` 行读不到也按不限处理
  （无配额主体：平台侧或单元测试没有租户上下文时走这条路）。
- **占用来源**：`COALESCE(SUM(size), 0)`，租户条件与 `deleted = 0` 由 MP 拦截器自动注入，
  所以逻辑删的行立即从占用里消失。
- **比较式**：`已用 + 本次 > 额度 * 1MB` 即拒；额度按整数 MB，判定按字节，
  所以测试里把第二笔刻意放大到剩余额度之上，避免取整漂移成偶发结果。
- **事务范围**：只包住"锁行 → SUM → 插入"，对象 IO 在事务外——不能把网络写挂进数据库锁窗口。
  隔离级别显式 `READ_COMMITTED`：拿锁后必须能看到前一笔已提交的 `size`。
- **传播语义**：`REQUIRED`，调用方已有事务时并入同一事务；当前 `upload` 本身没有事务，
  今天恒为自开一个短事务，见[已知边界](#已知边界与注意事项)。

### 按 storage_type 路由

`StoragePortRegistry` 有两个视图：`active()`（当前写入后端）与 `of(type)`（按类型取已配置的任意后端）。
写入时把 `activeType()` 记进每一行元数据，读取与删除时用 `of(file.getStorageType())`。

这条规则的实际意义是**换后端不必迁移动象**：

- 切成 S3 后，历史 SFTP 文件仍按行内 `sftp` 读出来——前提是旧后端的配置继续留在 yml 里；
- 取不到对应端口时抛 `400 文件所属存储后端未配置: minio`，**绝不回退当前后端**：
  把旧文件读成新后端上的同名对象、或者删错对象，比明确报错更糟；
- `remove` 同样按行内类型路由，后端未配置时只 warn，对象留在原处等 GC。

framework 侧还有一条装配约束：`snail.storage.type` 写了 `s3` 却没配 S3，或任一后端配了半截参数，
**启动即失败**（对齐 JWT 密钥的校验姿势），不给"跑到第一笔上传才炸"的机会。

### 下载的双层闸

```
GET /system/file/{id}/raw
  └─ @PreAuthorize system:file:download     ← 权限码：能不能用下载这个动作
       └─ requireFile(id)                   ← 租户闸：他租户/已删 = 文件不存在
            └─ FileAccessPolicy.canRead     ← 文件闸：这一条能不能读 → 否则 403 无权访问该文件
                 └─ requirePortOf(file)     ← 路由闸：后端未配置 → 400 点名类型
                      └─ port.get(key)      ← 内容流，由 Controller 的写回 lambda 负责关闭
```

默认口径 `DefaultFileAccessPolicy` 三条放行理由，命中任一即可读：

- `file.uploadBy == 当前用户`——上传者本人；
- 角色标识含 `admin`——租户内置管理员；
- 持有 `system:file:delete`——文件管理者（种子没给独立的"读全部"码，删除码即最强文件管理权）。

业务模块要更宽的可见性（例如"合同附件对单据参与人全可见"），
**提供一个 `@Primary` 的 `FileAccessPolicy` 实现覆盖**即可，不必改存储主流程，也不该放宽默认实现。

### 删除的两段与残留

`remove` 做两件事，顺序与失败处理都不同：

1. `fileMapper.deleteById`——逻辑删（`deleted = 1`），立即生效：列表消失、配额释放、下载按不存在拒绝；
2. 按行内 `storage_type` 取端口物理删对象，失败或后端未配置**只告警不回滚**。

于是有两类残留，都不影响业务可见性，只影响盘上空间：物理删失败的、以及后端已下线读不到的对象。
代码与类注释都把它们交给"将来的 GC 任务"，**该任务目前不存在**——配额按元数据算，
盘上占用不会随删除回落，见[已知边界](#已知边界与注意事项)。

### 下载流与响应头

`FileController.raw` 返回 `ResponseEntity<StreamingResponseBody>`：

- **异步写回**：请求线程只负责权限判定与取流，字节拷贝在异步线程，长下载不占住容器请求线程
  （集成测试断言 `asyncStarted`）；
- **流的生命周期**：`try (InputStream content = download.content())` 包住 `transferTo`，
  这很关键——SFTP 端口的流挂在本次会话上，关流才断连；
- **下载名**：`attachment; filename*=UTF-8''<percent-encoded>`（RFC 5987），
  与前端 `http.ts` 的双正则解析（先 `filename*=UTF-8''`，回退 `filename="..."`）对齐；
- **响应类型**：按扩展名查映射表（17 项），命中不了就 `application/octet-stream`。
  用扩展名而不是落库的 `content_type`，是为了不让客户端声明的 `text/html` 被浏览器当页面渲染。

### 多租户口径

| 数据/动作 | 处理 | 说明 |
| --- | --- | --- |
| `sys_file` 读写 | 拦截器自动注入 `tenant_id` 与 `deleted = 0` | `TenantAuditedEntity` |
| 跨租户下载/查详情 | 按"文件不存在"拒绝 | `selectById` 读不到即拒绝，不区分无权与没有 |
| 平台面分页 | `TenantContextHolder.withoutTenant(...)` 窄窗口 | `TenantContextHolder` 登记的第三类路径（平台管理动作的读侧豁免），明确点名"平台文件列表" |
| 对象键前缀 | 写入时取 `TenantContextHolder.get()` | 只是布局，隔离靠元数据表与接口层 |
| 配额行 | `sys_tenant` 主键读 + `FOR UPDATE` | 当前租户自己的行，不跨租户 |

## 详细设计：按类

### `FileStorageService`

编排者，不含 SQL 细节也不含协议细节。公开面：

| 方法 | 事务 | 要点 |
| --- | --- | --- |
| `upload(MultipartFile, bizType, bizId)` | 无（配额段自开短事务） | 十步校验链 + 补偿 |
| `download(Long)` | 读 | 返回 `StorageDownload(file, content)`，内容由调用方关闭 |
| `remove(Long)` | 逻辑删 + 尽力物理删 | 物理删失败不回滚 |
| `page(FileQuery)` | 读 | 本租户 |
| `platformPage(FileQuery)` | 读 | `withoutTenant` 包 `doPage` |

私有方法：`doPage`（两个分页面共用谓词）、`requireActivePort`、`requirePortOf`、`requireFile`、
`deleteObjectQuietly`、`cleanFileName`、`extOf`、`storageKey`、`trimToNull`。

常量口径值得单独留意：`FILE_NAME_MAX_LENGTH = 255` 与 `sys_file.file_name` 列宽一致；
`ILLEGAL_NAME_CHARS` 是 Windows/Unix 路径分隔符 + 保留字符 + 控制字符的并集；
两个文案常量 `文件存储未配置` 与 `文件所属存储后端未配置: ` 是对外承诺的拒绝语。

构造时对配置做一次归一：白名单统一转小写、去首点、去空串，`maxSizeMb` 换算成字节一次算好——
每笔上传都重新换算没有意义。

### `FileQuotaService`

配额与入库的唯一实现处。`assertCapacity` 无锁快拒，`insertWithinQuota` 锁行重算后插入；
`usedBytes()` 用 `selectObjs` 取 `COALESCE(SUM(size),0)` 的首个单元格，非数值一律当 0。
它对 `sys_tenant` 只做两件事：读配额列、锁那一行——不写租户数据。

### `FileAccessPolicy` / `DefaultFileAccessPolicy`

端口只有一个方法 `canRead(SysFile, LoginUser)`，返回 false 一律按 403 拒绝；
端口不返回 404，存在性归租户拦截器。默认实现读登录人快照里的角色标识与 `AuthorityChecker` 的权限码，
不查库。

### `FileController`

上传/分页/下载/删除四个端点加两个私有静态助手：`contentDisposition`（RFC 5987 编码）与
`contentTypeOf`（扩展名 → 响应类型，兜底 octet-stream）。
`EXT_CONTENT_TYPES` 是硬编码映射表，与默认白名单同规模，新增扩展名时两处都要看。

### `FilePlatformController`

只有分页，没有写侧与下载侧。类注释写明它是"读侧豁免"的落点。

### `SysFile` / `SysFileMapper` / DTO

PO 字段与列一一对应；`SysFileMapper` 是空 `BaseMapper`，查询全走 lambda 与 `selectObjs`。
`FileVO` 十二字段，**不含 `storageKey`**——内部布局对前端无消费场景，也不该外泄；
`FileQuery` 与其余分页面同口径（`pageNum` 缺省 1、`pageSize` 缺省 20、上限 200）。

## 配置项

全部住在 `snail.storage` 段（framework 的 `SnailStorageProperties`），共享配置
[`application.yml`](../../snail-platform-start/src/main/resources/application.yml) 只给环境变量占位，
开发默认值在 `application-dev.yml`。

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `snail.storage.type` | 空（关闭） | 当前写入后端，合法值 `sftp` / `s3`；空即不装配写入端口 |
| `snail.storage.sftp.host/port/username` | `22` | 主机、端口、登录用户 |
| `snail.storage.sftp.password` / `private-key` | — | 二选一，都没有即拒绝启动 |
| `snail.storage.sftp.base-dir` | — | 根目录，允许不存在，`put` 时逐级自建；对象路径 = `baseDir + "/" + key` |
| `snail.storage.sftp.known-hosts` | — | 严格指纹校验开启时必填 |
| `snail.storage.sftp.strict-host-key-checking` | `true` | 只有自签指纹的开发/测试环境显式关掉 |
| `snail.storage.sftp.connect-timeout-ms` / `session-timeout-ms` | `10000` / `30000` | 必须为正数，否则拒绝启动 |
| `snail.storage.s3.endpoint/region/bucket/access-key/secret-key` | region `us-east-1` | `type=s3` 时全部必填 |
| `snail.storage.s3.path-style` | `true` | 自建 MinIO 只吃路径式；阿里 OSS/腾讯 COS 兼容端点改 `false` |
| `snail.storage.upload.max-size-mb` | `50` | 单文件上限，需与 `spring.servlet.multipart.max-file-size` 同步 |
| `snail.storage.upload.ext-whitelist` | 17 项 | deny-by-default；比对前统一小写、去首点；空清单即全拒 |

启动校验的拒绝语点名到具体键：`snail.storage.type 只允许空/sftp/s3,当前值: x,拒绝启动`、
`snail.storage.type=s3 但该后端配置缺失,拒绝启动`、`snail.storage.sftp.host 缺失,拒绝启动`、
`snail.storage.sftp 缺 password 或 private-key,拒绝启动`、
`snail.storage.sftp.known-hosts 缺失:严格指纹校验开启时必填,拒绝启动`。

**切换后端时把旧后端配置继续留着**：历史文件靠行内 `storage_type` 找端口，配置一删就读不出来。

## 存储与索引

建表在 [`V2__init_file_schema.sql`](../../snail-platform-start/src/main/resources/db/migration/V2__init_file_schema.sql)，
菜单与权限码种子在 [`V5__seed_file_data.sql`](../../snail-platform-start/src/main/resources/db/migration/V5__seed_file_data.sql)。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `file_id` | BIGINT PK AUTO | 文件 ID |
| `tenant_id` | BIGINT NOT NULL | 拦截器自动填充 |
| `file_name` | VARCHAR(255) | 清洗后的原始名 |
| `ext` | VARCHAR(20) | 小写无点扩展名 |
| `content_type` | VARCHAR(100) | 客户端声明值，不信任仅记录 |
| `size` | BIGINT NOT NULL DEFAULT 0 | 字节数，配额 SUM 的对象 |
| `storage_key` | VARCHAR(500) | 对象键 `{tenantId}/{yyyyMM}/{uuid}.{ext}` |
| `storage_type` | VARCHAR(20) | 写入时的后端类型，路由依据 |
| `biz_type` / `biz_id` | VARCHAR(50) / BIGINT | 业务挂载标签与单据 ID |
| `upload_by` / `upload_by_name` | BIGINT / VARCHAR(30) | 上传人 ID 与显示名快照 |
| 审计四件套 + `deleted` | — | 逻辑删除即"移出文件库" |

索引三条：`idx_file_biz (tenant_id, biz_type, biz_id)`（业务侧反查附件）、
`idx_file_uploader (tenant_id, upload_by)`（按人检索）、`idx_file_name (tenant_id, file_name)`（前缀过滤）。

菜单/权限码：`180 文件管理`（`system:file:list`）与按钮 `1801 delete`、`1802 upload`、`1803 download`；
平台面 `230 平台文件`（`platform:file:list`）。租户内置 `admin` 按语义 A 隐式持有全部 `scope=2` 码，
不落 `sys_role_menu`；平台面 `scope=1` 必须显式授权，V5 文末自备一条幂等补授。

**没有缓存**：元数据、配额 SUM 都直查库；`userPerms` 缓存归 system，本模块不点清。

## 接口契约

### 响应信封

除下载外均为 `R{code,message,data}`，分页在 `data` 里带 `PageResult{rows,total}`；
业务拒绝 HTTP 200 + 非 0 `code`（400/401/403），认证与授权层面才由安全链给 HTTP 401/403。
下载端点成功时是字节流，失败时仍是 JSON 信封——前端按 `blob.type` 判断再解析。

### 上传

```
POST /system/file/upload
Content-Type: multipart/form-data
  file    = <binary>            (必填，part 名固定为 file)
  bizType = contract            (可选，表单字段或查询参数)
  bizId   = 1001                (可选)
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "fileId": 55,
    "tenantId": 1,
    "fileName": "合同扫描件.txt",
    "ext": "txt",
    "contentType": "text/plain",
    "size": 1024,
    "storageType": "sftp",
    "bizType": "contract",
    "bizId": 1001,
    "uploadBy": 1,
    "uploadByName": "超级管理员",
    "createTime": "2026-10-29 10:00:00"
  }
}
```

### 分页

`GET /system/file/page?pageNum=1&pageSize=20&fileName=合同&bizType=contract`
与 `GET /platform/file/page?...`（平台面结果带真实 `tenantId`）。

### 下载

`GET /system/file/{fileId}/raw`

```
HTTP/1.1 200
Content-Disposition: attachment; filename*=UTF-8''%E5%90%88%E5%90%8C.txt
Content-Type: text/plain
<字节流>
```

拒绝时返回 JSON 信封：`文件不存在`（跨租户/已删/无此 ID）、`无权访问该文件`（`code=403`，文件级可见性）、
`文件所属存储后端未配置: minio`（`code=400`）；无 `system:file:download` 时 HTTP 403。

### 错误文案

| 文案 | code | 触发条件 |
| --- | --- | --- |
| `文件存储未配置` | 400 | `snail.storage.type` 为空，任何上传都被拒 |
| `上传文件为空` | 400 | 收到的字节为 0；`file` part 缺失由容器层拦下，走不到这句 |
| `上传文件名不能为空` | 400 | 原名为空，或清洗后全被替掉 |
| `文件类型不在白名单: exe` | 400 | 扩展名不在白名单；无扩展名时写作 `文件类型不在白名单: 无扩展名` |
| `文件超过单文件上限 50MB` | 400 | 字节数超过 `max-size-mb` |
| `租户存储配额不足` | 400 | 无锁预检或锁行重算任一环节判定超额 |
| `文件不存在` | 400 | 元数据读不到：不存在 / 已逻辑删 / 属他租户 |
| `无权访问该文件` | 403 | 过了权限码但 `FileAccessPolicy` 不放行 |
| `文件所属存储后端未配置: {type}` | 400 | 行内 `storage_type` 没有对应端口，绝不回退当前后端 |
| `文件写入存储失败: {原因}` | 500 | 端口写对象抛异常，属系统级故障 |
| `每页至多 200 条` 等 | — | `FileQuery` 校验注解的 `message` 原文 |

## 使用方式

### 构建与测试

```bash
# 编译
mvn -o -pl snail-platform-function/snail-function-storage -am -DskipTests compile

# 纯单测（不需要数据库与存储后端）
mvn -o -pl snail-platform-function/snail-function-storage test

# 端到端回归（需本机 MySQL/Valkey，见[验证手段](#验证手段)）
mvn -o -pl snail-platform-start -am -DSNAIL_DB_USERNAME=root -Dtest=FileStorageFlowTest test
```

### 手工验证（curl，SFTP 后端）

```bash
TOKEN=<登录返回的 token>

curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@./合同.txt" -F "bizType=contract" \
  "http://localhost:8080/system/file/upload"

curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/system/file/page?pageSize=5"

# -OJ 让 curl 按 Content-Disposition 起名；拒绝时拿到的是 JSON
curl -s -OJ -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/system/file/55/raw"

curl -s -X DELETE -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/system/file/55"

# 投递痕迹核对：元数据在，对象应当已消失
SELECT file_id, storage_key, storage_type, deleted FROM sys_file WHERE file_id = 55;
```

### 前端接法

| 页面 | 文件 | 说明 |
| --- | --- | --- |
| API 封装 | `snail-platform-frontend-admin/src/api/file.ts` | `pageFiles` / `pagePlatformFiles` / `uploadFile` / `deleteFile` / `downloadFile` |
| 文件管理 | `src/views/system/file/index.vue` | 上传按钮 `v-permission="'system:file:upload'"`，行内下载/删除按 `system:file:download`/`delete` 出现 |
| 平台文件 | `src/views/platform/file/index.vue` | 只读列表，含租户 ID 列，无下载与删除动作 |

上传走唯一 axios 实例的 `FormData`（实例未设全局 JSON 头，axios 会自带 multipart boundary）；
下载复用 `http.ts` 的 `download()`：`responseType: 'blob'`，先判 `blob.type` 是否 JSON 信封，
再按 RFC 5987 解析下载名，解析不出就退回行数据里的 `fileName`。

### 业务侧挂载附件

`bizType` 是调用方自定义的业务域标签（`contract` / `avatar` / …），`bizId` 是具体单据 ID，
两者都进 `sys_file` 并可按 `idx_file_biz` 反查。存储模块**不校验 `bizId` 是否真的存在**，
挂载关系的可信度由业务侧负责；要"只有单据参与人能读附件"，就为这个 `bizType` 提供
`@Primary` 的 `FileAccessPolicy`。

### 接入一个新的存储后端

改动都在 framework 侧，本模块只多一行元数据：

1. `SnailStorageProperties` 加一组配置类，`SnailStorageConfig` 加"是否出现"的判定与必填校验
   （半截配置必须拒绝启动）；
2. 实现 `StoragePort` 四个方法：`put` 的 `size` 要与流真实字节数一致（部分后端要预声明长度），
   `get` 返回的流由调用方关闭，`delete` 对不存在的键保持幂等不报错；
3. 把新类型名加进 `VALID_TYPES`，并在注册表里登记实例——切换后端后**保留旧后端配置**；
4. 本模块无需改动：写入时记的就是 `activeType()`，历史对象按行内类型路由；
5. 验证：`StoragePortRegistryTest` 补路由用例，`FileStorageFlowTest` 的
   `downloadRejectsUnconfiguredStoredBackend` 口径仍然要红不起来。

## 验证手段

两层测试，分工明确：

**纯单测** [`FileStorageServiceTest`](src/test/java/com/snail/platform/function/storage/service/FileStorageServiceTest.java)
（Mockito 替身 mapper/quota/policy + 内存 `FakePort`，不连库不连存储）钉住三条最容易被改坏的行为：

| 用例 | 钉住的承诺 |
| --- | --- |
| `downloadUsesStoredTypeInsteadOfActiveBackend` | 历史对象按行内 `storage_type` 取端口，当前后端 s3 不得被触碰 |
| `downloadRejectsStoredTypeWithoutConfiguredBackend` | 类型未配置 → `code=400` 且点名缺失类型，不回退当前后端 |
| `uploadDeletesWrittenObjectWhenMetadataInsertFails` | 入库失败时补偿删除同一个键，原异常 `db down` 原样抛出 |

**端到端** [`FileStorageFlowTest`](../../snail-platform-start/src/test/java/com/snail/platform/storage/FileStorageFlowTest.java)
用内嵌 SFTP 起一个真实端口（随机端口、口令认证、虚拟根落在 `./target/sftp-home` 不污染工作区），
需要本机 MySQL 与 Valkey，靠 `SNAIL_DB_USERNAME` 开关，未设置时整类跳过：

| 用例 | 覆盖的口径 |
| --- | --- |
| `fileFlowEndToEnd` | 上传 → 本租户列表 → 平台检索 → 下载字节一致 + RFC 5987 头 → 白名单拒绝 `.exe` → 删除 → 再下载按"文件不存在" |
| `quotaRejectsUploadBeyondTenantLimit` | 额度够第一笔不够第二笔即拒（`code=400`），并证明成功笔确实计入 SUM |
| `foreignTenantFileIsInvisibleOnTenantSide` | 外来租户行：下载按不存在、本租户列表不见、平台面可见且带真实 `tenantId` |
| `uploadCleansPathSegmentsAndControlChars` | `C:\Users\..\..\␇报告"<>|.txt` 清成 `_报告____.txt`；300 字长名截到 255 且保住 `.txt` |
| `downloadNeedsPermissionAndFileAccessPolicy` | 无码 403、有码非上传者 403 `无权访问`、上传者本人可读、租户管理员可读、停用用户旧令牌 401 |
| `downloadRejectsUnconfiguredStoredBackend` | 行内类型 `minio-legacy` 未配置 → 400 点名，不读当前后端 |
| `concurrentUploadsCannotExceedQuota` | 额度只够一笔时两笔并发必须一成一败，失败笔为配额拒绝，占用恰好 +1 份 |

端口层还有 framework 侧的 `StoragePortRegistryTest` 与 `SftpStoragePortTest`（同一套内嵌 SFTP 夹具）。
架构层由 `ArchitectureRulesTest` 守着那条直读 `sys_tenant` 的豁免边——多一条就越界。

## 已知边界与注意事项

以下都是当前实现的真实边界，动它们之前请先确认取舍：

1. **物理删失败/后端下线的对象没有回收者**。类注释把残留交给"将来的 GC 任务"，该任务尚未实现；
   配额按 `sys_file` 的 `SUM(size)` 算，逻辑删即从占用里消失，**盘上空间不会随删除回落**，
   实际用量能悄悄超过租户额度。
2. **逻辑删不等于可恢复**：`deleted = 1` 的行保留着指向已删对象的元数据，恢复元数据也取不回内容；
   真正的回收窗口只有一次物理删的机会，且失败不重试。
3. **`upload` 没有 `@Transactional`**：对象写入与元数据插入不在同一原子单元里，靠补偿兜住异常路径。
   进程在"对象已写、事务未提交"之间被杀，留下没有元数据的孤儿对象，同样等 GC（而 GC 没有）。
   `FileQuotaService` 注释里"调用方已有事务时加入同一事务"是 REQUIRED 的通用语义，当前调用链上并不成立。
4. **`isTenantAdmin` 只看 `roleCode == "admin"`，不比 `is_system`**：
   而权限码侧的内置超管判定（`UserPermissionService.isSuperAdmin`）要求码对**且** `is_system = 1`。
   一个 `roleCode` 恰为 `admin` 的非内置角色会因此获得"读全租户文件"的能力，隐式权限码却不会给它；
   两处口径要一起看，改一处就得对齐另一处。
5. **`LoginUser` 快照参与了判权**：framework-core 的 `LoginUser` 口径写明"本快照不参与判权，
   判权仍由权限码每请求解析"，而默认可见性策略读了 `user.roles()`。这是与框架约定相反的一处依赖。
6. **"可读全部文件"复用删除码**：种子只有 `list/upload/download/delete` 四把码，
   能删即能读是隐含推定。要区分"能删不能读"必须新增权限码，而不是改这条判定。
7. **两个长度无校验的列**：`content_type` 列宽 100、`biz_type` 列宽 50，代码直接写客户端声明值与入参，
   超长输入会由数据库拒绝，表现为系统级错误而不是友好文案。
8. **大小闸有两道**：`snail.storage.upload.max-size-mb` 与 `spring.servlet.multipart.max-file-size`
   都写 50MB。超过容器上限时请求在进 Controller 前就被拒，拿不到"文件超过单文件上限 50MB"这句业务文案；
   只改一处会让业务闸形同虚设。
9. **平台面只能看元数据、取不到内容**：`/platform/file` 只有分页端点，
   平台管理员无法取证文件内容（这是有意的收窄，不是遗漏）。
10. **无租户上下文时对象键会写成 `null/{yyyyMM}/{uuid}.{ext}`**：`assertCapacity` 把"无租户主体"当合法场景放行，
    `storageKey` 却直接把 `TenantContextHolder.get()` 拼进键里——今天所有上传端点都在登录上下文中，
    但这条内部直调路径没有防护。
11. **`bizId` 不与业务单据核对**：附件可以挂任意 `bizType`/`bizId`，绑定关系不做存在性校验，
    跨模块引用完整性由调用方自己保证。
12. **SFTP 端口每次操作独立建连、不池化**：下载大文件时一条会话会活到流读完，
    吞吐受限是已知取舍（连接池在路线图里，没进首版）。
13. **没有内容摘要**：`sys_file` 无 `sha256` 列，上传前后一致性、去重、完整性校验都无从谈起（MVP 明确不做）。
14. **扩展名 → 响应类型映射硬编码在 Controller**，与配置的白名单是两份清单：
    放开新扩展名而不补映射，下载时只会得到 `application/octet-stream`。
15. **测试数据有残留**：端到端用例有意保留上传的文件与元数据行（与阶段③流程测试同口径），
    只有改配额与直插外来租户行的用例做恢复/清理。

## License

[MIT](../../../LICENSE)
