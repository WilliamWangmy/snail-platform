# Snail Platform

致力于在 AI 时代打造全能型开发者的综合性开源平台。

## 当前状态

后端线已完成三层的工程骨架与实现：framework 八个构件、function 各业务模块（system/auth/notify/storage/workflow）均已落地，分层护栏、装配入口与三份 profile 配置齐备，可直接 `package` 出可执行 jar 并启动。

- 版本：`0.0.1`
- 基础包名：`com.snail.platform`
- License：MIT

## 仓库结构

```
snail-platform/
├── snail-platform-backend/            # 后端（framework / function / start）
├── snail-platform-frontend-admin/     # 管理端前端
├── snail-platform-frontend-dashboard/ # 数据大屏前端
├── snail-platform-agent/              # AI / Agent 线（Python）
├── docs/                              # 设计与决策文档
└── README.md
```

## 后端架构

后端只有三个层次，聚合根 `snail-platform-backend` 的 `<modules>` 恰好登记这三个，管理端线不进 reactor。

| 构件 | 层次 | 职责 |
| --- | --- | --- |
| `snail-platform-framework` | 技术基建层（聚合） | 纯技术能力，不含业务逻辑 |
| &nbsp;&nbsp;├ `snail-framework-core` | 基建 | 统一响应/业务异常、`PageQuery`→`PageResult` 分页装配、租户与登录人上下文、traceId 过滤器；详见[模块说明](snail-platform-backend/snail-platform-framework/snail-framework-core/README.md) |
| &nbsp;&nbsp;├ `snail-framework-db` | 基建 | 连接与事务装配、租户行级过滤、数据范围拦截、分页上限、审计填充；方言 `DbType.MYSQL` 在此固定；详见[模块说明](snail-platform-backend/snail-platform-framework/snail-framework-db/README.md) |
| &nbsp;&nbsp;├ `snail-framework-redis` | 基建 | Lettuce 客户端装配、Redis key 约定、限流小工具；详见[模块说明](snail-platform-backend/snail-platform-framework/snail-framework-redis/README.md) |
| &nbsp;&nbsp;├ `snail-framework-security` | 基建 | JWT 签发与校验、认证过滤器与会话吊销、权限码判定入口；详见[模块说明](snail-platform-backend/snail-platform-framework/snail-framework-security/README.md) |
| &nbsp;&nbsp;├ `snail-framework-log` | 基建 | 操作日志注解与切面；不落库，经 `OperLogSink` 端口下沉给业务层；详见[模块说明](snail-platform-backend/snail-platform-framework/snail-framework-log/README.md) |
| &nbsp;&nbsp;├ `snail-framework-excel` | 基建 | fastexcel 导出单点（流式写入、表头与样式约定）；导入通道未实现；详见[模块说明](snail-platform-backend/snail-platform-framework/snail-framework-excel/README.md) |
| &nbsp;&nbsp;├ `snail-framework-storage` | 基建 | 文件存储端口与 SFTP/S3 双实现、按类型路由的活动注册表；详见[模块说明](snail-platform-backend/snail-platform-framework/snail-framework-storage/README.md) |
| &nbsp;&nbsp;└ `snail-framework-workflow` | 基建 | Flowable 流程引擎门面、BPMN 安全校验与键改写、流程变量准入、租户桥接；详见[模块说明](snail-platform-backend/snail-platform-framework/snail-framework-workflow/README.md) |
| `snail-platform-function` | 业务层（聚合） | 模块 = 上下文聚类，子域只到包级 |
| &nbsp;&nbsp;└ `snail-function-system` | 业务 | 系统管理：`tenant/user/org/role/menu/dict/log` + `api`，表分组 `sys_*`；详见[模块说明](snail-platform-backend/snail-platform-function/snail-function-system/README.md) |
| `snail-platform-start` | 装配层 | 唯一装配点与唯一启动类，配置、日志、Flyway 脚本与 ArchUnit 测试都住在这里 |

三条硬约束：

1. **framework 永远不认识 function**——由 `snail-platform-framework` 的 enforcer 规则 `bannedDependencies` 在 `validate` 阶段拦截。
2. **业务模块之间只经 `api` 包互调**，跨模块调用不得触达对方内部子域包。
3. **`spring-boot-maven-plugin` 只在 `snail-platform-start` 声明**，只有这里产出可执行 jar。

## 技术选型

| 关注点 | 选型 |
| --- | --- |
| 应用框架 | Spring Boot 4.1.1 |
| 语言级别 / 构建运行 JDK | 17 编译 / ≥21 运行（产物 class major version = 61，虚拟线程在 ≥21 时生效） |
| ORM | MyBatis-Plus 3.5.17（`spring-boot4-starter` 线 + 独立 `jsqlparser` 构件） |
| 数据库 / 迁移 | MySQL + Flyway，脚本位于 `snail-platform-start/src/main/resources/db/migration` |
| 缓存 | Caffeine（经 Spring Cache 抽象）+ Redis 协议兼容服务（Valkey，Lettuce 客户端） |
| 安全 | Spring Security + Nimbus JOSE+JWT 10.9.1（HS256 签发与校验） |
| 接口文档 | springdoc-openapi 3.1.1 |
| 表格文件 | fastexcel 1.3.0 |
| 对象映射 | MapStruct 1.6.3（编译期生成，PO 不出 outbound） |
| 边界测试 | ArchUnit 1.5.1（`test` 作用域，仅 `snail-platform-start`） |

**不直接依赖 Jackson 2**：Boot 4 使用 `tools.jackson`，`com.fasterxml.jackson*` 只允许出现在传递依赖上，由根 POM 的 enforcer 规则守住。

## 构建护栏

根 POM 在 `validate` 阶段执行 `enforce-platform-baseline`：

- JDK ≥ 21、Maven ≥ 3.9
- 禁止直接声明 `com.fasterxml.jackson*`
- `requireUpperBoundDeps` 拦截版本降级

编译器注解处理器顺序固定为 `lombok → lombok-mapstruct-binding → mapstruct`，子模块不写 `<version>`，版本一律由根 POM 的 `dependencyManagement` 单点提供。

## 快速开始

### 前置条件

- JDK 21（或更高）、Maven 3.9+
- 一个可连的 MySQL 实例
- 一个 Redis 协议兼容服务（Valkey / Redis）

### 构建

```bash
cd snail-platform-backend
mvn clean install
```

### 运行

开发环境（默认 profile 为 `dev`，指向 `127.0.0.1`）：

```bash
mvn -pl snail-platform-start spring-boot:run
```

或运行打包产物：

```bash
java -jar snail-platform-start/target/snail-platform.jar      # 加 --spring.profiles.active=prod 切生产
```

### 环境变量

`dev` 提供指向本机的默认值，口令不同用变量覆盖；`prod` 不提供任何默认值，七个变量必填。

| 变量 | 说明 |
| --- | --- |
| `SNAIL_PROFILE` | 激活的 profile，默认 `dev` |
| `SNAIL_DB_URL` | JDBC 连接串 |
| `SNAIL_DB_USERNAME` / `SNAIL_DB_PASSWORD` | 数据库账密 |
| `SNAIL_REDIS_URL` | 缓存服务连接串 |
| `SNAIL_JWT_SECRET` | JWT 签名密钥，长度 ≥ 32；dev 有开发占位默认值，**prod 必填，缺失启动即失败** |
| `SNAIL_SEED_ADMIN_PASSWORD` | 内置 admin 种子口令的 **BCrypt 密文**（非明文，用 Hutool `BCrypt.hashpw` 离线生成）；V4 种子迁移用它写入 admin 初始口令。dev 有默认值（`admin123` 的密文，首次登录强制改密兜底），设置后覆盖；**prod 必填**，缺失启动即失败；不生成随机口令、不打印到任何日志 |

初始口令交付口径（P2-3）：新建用户/重置密码/开通租户的口令一律由管理员在表单自输或点"生成"，随 HTTPS 请求体提交，响应只回 ID、不再回传口令——"服务端生成口令加密回传"链路已整体退役，前端无需任何密钥环境变量。

本地覆盖请写在 `application-local.yml`，该文件已在 `.gitignore` 内。

## 配置要点

`snail-platform-start` 的三份配置文件承担全部装配语义：

- `application.yml`：应用名、虚拟线程开关、Flyway 位置、Caffeine 缓存名单点（`dictItem, menuTree, tenantConfig, orgSubtree, userPerms, userSession`）、springdoc 开关
- `application-dev.yml`：本机连接 + `com.snail.platform` 置 DEBUG
- `application-prod.yml`：连接串全部来自环境变量，且关闭文档端点
- `logback-spring.xml`：日志 pattern 含 `%X{traceId}`，与 `ApiResponse.traceId` 同源

## 路线图

1. 管理端前端（`snail-platform-frontend-admin`）与数据大屏（`snail-platform-frontend-dashboard`）持续迭代
2. AI / Agent 线（`snail-platform-agent`）建设
3. 既有后端模块按业务反馈迭代

## License

[MIT](LICENSE)
