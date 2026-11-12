# snail-framework-storage

对象存储的端口与适配器：统一放/取/删/存在性，后端支持 SFTP 与 S3 协议兼容（MinIO/云 OSS）。

纯技术构件，不认业务表、不解析键语义；`snail.storage.type` 为空时整体不装配，消费方自行降级。

- 所属工程：[`snail-platform-backend`](../../pom.xml)
- 基础包名：`com.snail.platform.framework.storage`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [启动装配与配置校验](#启动装配与配置校验)
  - [注册表与历史路由](#注册表与历史路由)
  - [SFTP 适配器](#sftp-适配器)
  - [S3 适配器](#s3-适配器)
- [详细设计：按类](#详细设计按类)
- [配置项](#配置项)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- `StoragePort` 统一契约：`put/get/delete/exists`；
- SFTP 适配器：JSch 会话、目录自建、主机指纹校验、超时、流随会话关闭；
- S3 协议兼容适配器：AWS SDK v2 同步客户端、路径式/虚拟主机寻址、显式 ContentLength、启动期自动建桶（可关）；
- `BucketProvisioner` 桶开通口：按名称确保桶存在（不存在即创建），仅 type=s3 时装配，供租户开通等业务装配点调用；
- `S3BucketNames`：业务标识到 S3 桶名的归一工具（点逐字保留、相邻点坍缩、下划线防御归一、业务 ID 兜底唯一、63 封顶）；
- 启动期装配与配置完整性校验：类型合法、所选后端必填项齐全、半截配置拒绝启动；
- `StoragePortRegistry`：当前写入端口与全部已配置后端的只读视图。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 文件元数据、配额、可见性、业务键生成 | `snail-function-storage` |
| 上传扩展名白名单与单文件大小校验 | `snail-function-storage`（读本模块的 `snail.storage.upload.*`） |
| 对象键语义（如 `{tenantId}/{yyyyMM}/{uuid}.ext`） | 业务侧生成，本模块原样使用 |
| 病毒扫描、图片压缩、分片上传、断点续传 | 当前不提供 |
| 对象签名 URL、生命周期策略 | 当前不提供 |

## 在平台中的位置

```
FileStorageService（function-storage）
   ├─ 上传 ──▶ StoragePortRegistry.active() ──▶ SftpStoragePort / S3StoragePort
   ├─ 下载 ──▶ StoragePortRegistry.of(sys_file.storage_type)
   └─ 删除 ──▶ 同上，按元数据记录的类型
                     │
                     ├─ SftpStoragePort ──▶ JSch/SFTP
                     └─ S3StoragePort   ──▶ AWS SDK v2（url-connection）
```

## 包结构

```
com.snail.platform.framework.storage
├── StoragePort.java              统一端口
├── StoragePortRegistry.java      活动端口 + 按类型路由
├── SnailStorageConfig.java       装配与启动期校验
├── SnailStorageProperties.java   snail.storage.* 配置
├── SftpStoragePort.java          SFTP 适配器
├── S3StoragePort.java            S3 协议兼容适配器
├── BucketProvisioner.java        桶开通口(type=s3 才装配)
├── S3BucketProvisioner.java      桶开通实现(headBucket→createBucket)
├── S3BucketNames.java            业务标识→桶名归一
└── S3Clients.java                S3Client 单点构造(端口与桶开通共用)

src/test/java/...
├── SftpStoragePortTest.java      SFTP 适配器单测（内嵌服务器）
├── SftpTestServer.java           内嵌 SFTP 夹具（随 test-jar 发布给 start 复用）
└── StoragePortRegistryTest.java  注册表路由单测
```

## 整体设计

### 启动装配与配置校验

`SnailStorageConfig` 在构造期与装配期做三件事：

1. **类型合法**：`snail.storage.type` 只允许空、`sftp`、`s3`；空表示整体关闭；
2. **出现即配全**：只要填了任一 SFTP 身份/连接键（host/username/password/private-key/known-hosts）
   或任一 S3 连接键（endpoint/bucket/access-key/secret-key），该后端就进入装配并做完整性校验；
3. **active 必须有端口**：`type` 非空但对应后端未配置，启动即失败。

SFTP 完整性：host、username、base-dir 必填；password 与 private-key 二选一；
`strict-host-key-checking=true` 时 known-hosts 必填；超时必须为正数。

S3 完整性：endpoint、region、bucket、access-key、secret-key 全部必填。

口径与 `JwtTokenService` 一致：**半配置跑到第一笔上传才炸是更大的事故**，所以启动期拒绝。

### 注册表与历史路由

- `active()`：当前写入端口；`type` 为空或端口缺席返回 `Optional.empty()`；
- `of(type)`：按元数据记录的类型取端口，未配置该类型返回空；
- **绝不静默回退当前后端**：切换后端后把旧文件读错、删错比明确报错更糟；
- 切换后端的正确姿势：把旧后端配置继续留着，历史文件按 `sys_file.storage_type` 继续路由到旧端口。

### SFTP 适配器

- 每次操作独立建连（session → channel → 操作 → 断开），不持共享连接，线程安全按最保守形态成立；
- 主机指纹默认严格校验（`StrictHostKeyChecking=yes`），known-hosts 路径由配置提供；
  仅开发/测试环境可显式关闭；
- 建连、开通道、会话读写都有超时，网络故障不会无限挂住请求线程；
- `put` 前逐级 `mkdirs`（stat 异常视为目录不存在，mkdir 撞"已存在"容忍），base-dir 允许不存在；
- 对象路径 = `baseDir + "/" + key`；`size/contentType` 在 SFTP 协议里无对应语义，忽略；
- `get` 返回 `SessionBoundInputStream`：流关闭时连同通道与会话一起断开；
- `delete` 对"文件不存在"（SFTP 码 2）视为已删，保持幂等；
- `exists` 走 `stat`，仅"文件不存在"返回 false，其他错误上抛。

### S3 适配器

- 客户端：AWS SDK v2 同步 `S3Client` + `UrlConnectionHttpClient`（依赖树最小）；
- 寻址：`path-style=true`（默认）走 `endpoint/bucket/key`，MinIO 等自建只吃这一种；
  `false` 走 `bucket.endpoint/key`，阿里 OSS/腾讯 COS 的兼容端点用虚拟主机形态；
- **启动期自动建桶**：`auto-create-bucket=true`（默认）时构造期 `headBucket` 探测，
  404 即 `createBucket`（不携带 LocationConstraint，region 默认 us-east-1），其余错误原样上抛；
  把"桶缺失"从第一笔上传的 404 提前到装配期暴露，与本模块"配置问题启动即失败"的口径一致；
  云 OSS 若运行身份无建桶权限，显式关闭；
- `put` 显式携带 `contentLength`（来自调用方元数据）与 `contentType`，同键覆盖；
- `get` 返回 SDK 的 `ResponseInputStream`，调用方负责关闭；键不存在由 SDK 抛异常；
- `delete`：S3 对不存在的键本就返回成功，天然幂等；
- `exists` 走 `headObject`，**仅 `NoSuchKeyException` 视为不存在**，其余 S3 异常原样上抛
  （部分兼容端点对 404 的映射可能不同，见"已知边界"）。

## 详细设计：按类

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| [`StoragePort`](src/main/java/com/snail/platform/framework/storage/StoragePort.java) | 统一端口 | `get` 流由调用方关闭；`delete` 幂等；存在性不是并发保证 |
| [`StoragePortRegistry`](src/main/java/com/snail/platform/framework/storage/StoragePortRegistry.java) | 端口路由 | 按类型取不到返回空，不回退 active |
| [`SnailStorageConfig`](src/main/java/com/snail/platform/framework/storage/SnailStorageConfig.java) | 装配与校验 | 非法类型/半截配置/active 缺失均拒绝启动 |
| [`SnailStorageProperties`](src/main/java/com/snail/platform/framework/storage/SnailStorageProperties.java) | 配置绑定 | 含 SFTP、S3、上传约束三段 |
| [`SftpStoragePort`](src/main/java/com/snail/platform/framework/storage/SftpStoragePort.java) | SFTP 实现 | 每操作独立建连；严格指纹默认开；流随会话关闭 |
| [`S3StoragePort`](src/main/java/com/snail/platform/framework/storage/S3StoragePort.java) | S3 兼容实现 | 默认路径式寻址；`put` 显式长度；`exists` 仅 NoSuchKey 为 false；启动期自动建桶 |
| [`BucketProvisioner`](src/main/java/com/snail/platform/framework/storage/BucketProvisioner.java) | 桶开通口 | 仅 type=s3 装配；失败原样上抛，成败语义由调用方拍板 |
| [`S3BucketProvisioner`](src/main/java/com/snail/platform/framework/storage/S3BucketProvisioner.java) | 桶开通实现 | headBucket→404 即 createBucket；幂等；探测/建桶逻辑与读写端口共用 |
| [`S3BucketNames`](src/main/java/com/snail/platform/framework/storage/S3BucketNames.java) | 桶名归一 | 点逐字保留;相邻点坍缩;下划线防御归一;业务 ID 兜底唯一;63 封顶 |

## 配置项

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `snail.storage.type` | 空 | 后端类型：空/sftp/s3；空 = 整体关闭 |
| `snail.storage.sftp.host` | 无 | 主机；出现即要求配全 |
| `snail.storage.sftp.port` | `22` | 端口 |
| `snail.storage.sftp.username` | 无 | 登录用户 |
| `snail.storage.sftp.password` | 无 | 与 private-key 二选一 |
| `snail.storage.sftp.private-key` | 无 | 私钥文件路径 |
| `snail.storage.sftp.base-dir` | 无 | 根目录，允许不存在，put 时逐级自建 |
| `snail.storage.sftp.known-hosts` | 无 | 严格指纹校验开启时必填 |
| `snail.storage.sftp.strict-host-key-checking` | `true` | 生产保持开启 |
| `snail.storage.sftp.connect-timeout-ms` | `10000` | 建连/开通道超时 |
| `snail.storage.sftp.session-timeout-ms` | `30000` | 会话读写超时 |
| `snail.storage.s3.endpoint` | 无 | 协议兼容端点 |
| `snail.storage.s3.region` | `us-east-1` | 区域标识 |
| `snail.storage.s3.bucket` | 无 | 桶名 |
| `snail.storage.s3.access-key` / `secret-key` | 无 | 凭证 |
| `snail.storage.s3.path-style` | `true` | true 路径式；false 虚拟主机 |
| `snail.storage.s3.auto-create-bucket` | `true` | 启动时桶不存在即自动创建；云 OSS 无建桶权限时显式关闭 |
| `snail.storage.upload.max-size-mb` | `50` | 单文件上限（由 function-storage 消费） |
| `snail.storage.upload.ext-whitelist` | 见 `application.yml` | 扩展名白名单（由 function-storage 消费） |

`application-dev.yml` 为本机 SFTP 提供开发默认连接，但 `type` 默认仍为空——本机启用需设
`SNAIL_STORAGE_TYPE=sftp`。prod 全部由 `SNAIL_STORAGE_TYPE` / `SNAIL_SFTP_*` / `SNAIL_S3_*` 环境变量提供。

## 使用方式

```java
// 上传（业务侧生成键，本模块原样使用）
StoragePort port = registry.active()
        .orElseThrow(() -> BizException.badRequest("文件存储未配置"));
port.put(objectKey, inputStream, size, contentType);

// 下载：按元数据记录的存储类型路由，旧后端配置保留即可读历史文件
try (InputStream in = registry.of(file.getStorageType())
        .orElseThrow(() -> BizException.badRequest("该文件的存储后端未配置"))
        .get(file.getObjectKey())) {
    // 写出响应
}

// 删除（幂等）
registry.of(file.getStorageType()).ifPresent(port -> port.delete(file.getObjectKey()));

// 桶开通(如租户开通时按标识建桶):bean 仅 type=s3 存在,消费方用 ObjectProvider 按缺席降级
public TenantService(ObjectProvider<BucketProvisioner> bucketProvisioner, ...) { ... }

String bucket = S3BucketNames.forTenant(tenantCode, tenantId);   // snail-<code>-<id>
bucketProvisioner.getIfAvailable().ensureBucket(bucket);
```

## 验证手段

- [`SftpStoragePortTest`](src/test/java/com/snail/platform/framework/storage/SftpStoragePortTest.java)：
  内嵌 SFTP 服务器（`SftpTestServer`），覆盖目录逐级自建、读流关闭会话、删除幂等、同键覆盖、
  严格指纹拒绝未知主机、S3 寻址默认值；
- [`StoragePortRegistryTest`](src/test/java/com/snail/platform/framework/storage/StoragePortRegistryTest.java)：
  active 与历史类型路由、未知类型不回退；
- `FileStorageFlowTest`（start）：经 `test-jar` 复用内嵌 SFTP 夹具，跑上传/下载/删除全链；
- 全量回归：在 `snail-platform-backend` 执行 `mvn test`。

## 已知边界与注意事项

- **`exists` 的 S3 映射**：实现只把 `NoSuchKeyException` 映射为 false，其余 S3 异常上抛；
  部分兼容端点对 404 的异常类型可能不同，未在本仓逐一验证，依赖该行为前先用目标端点确认；
- **自动建桶不带 LocationConstraint**：`createBucket` 裸桶名，region 默认 us-east-1 时 MinIO/云 OSS 均可接受；
  标准 AWS 分区（非 us-east-1）要求携带 LocationConstraint，届时预建桶或关闭 `auto-create-bucket`；
  另探测用 `headBucket`，个别兼容端点对桶不存在的 404 映射若非 404 状态码，探测错误会原样上抛（启动即失败，可据此排查）；
- **SFTP 不做连接池**：每操作独立建连，吞吐不足时再上连接池（路线图项）；
- **不重试、不做分片**：网络抖动与超大文件由消费方或后续迭代处理；
- **`put` 同键覆盖**：后端不做"新建才写入"语义，需要防覆盖由业务侧键生成保证（UUID）；
- **存在性不是并发保证**：业务侧存在性一律以元数据表为准，端口只服务物理层；
- **SFTP 的 `size/contentType` 被忽略**：SFTP 协议无预声明长度；S3 则显式用于 `Content-Length`/`Content-Type`；
- **删除不递归**：只删单个对象键，目录清理不在本模块；
- **上传约束配置在 framework、执行在 function**：`upload.*` 由 `FileStorageService` 读取并执行，
  改口径时两处要一起看。
