# Snail Platform

致力于在 AI 时代打造全能型开发者的综合性开源平台。

## 当前状态

后端线已完成工程骨架：Maven 多模块结构、分层护栏、装配入口与三份 profile 配置均已落地，可直接 `package` 出可执行 jar 并启动。业务代码（framework 构件的实现类、`snail-function-system` 各子域）尚在填充中。

- 版本：`0.0.1`
- 基础包名：`com.snail.platform`
- License：MIT

## 仓库结构

```
snail-platform/
├── snail-platform-backend/     # 后端线（当前唯一在开发的线）
├── docs/                       # 设计与决策文档
└── README.md
```

管理端前端、Python/AI 线暂未建立工程目录，规划中不预先创建空壳。

## 后端架构

后端只有三个层次，聚合根 `snail-platform-backend` 的 `<modules>` 恰好登记这三个，管理端线不进 reactor。

| 构件 | 层次 | 职责 |
| --- | --- | --- |
| `snail-platform-framework` | 技术基建层（聚合） | 纯技术能力，不含业务逻辑 |
| &nbsp;&nbsp;├ `snail-framework-core` | 基建 | 响应、异常、分页、租户作用域、事件信封、traceId 键的唯一口径 |
| &nbsp;&nbsp;├ `snail-framework-db` | 基建 | 连接、租户行级过滤、分页上限、审计填充；方言 `DbType.MYSQL` 在此固定 |
| &nbsp;&nbsp;├ `snail-framework-redis` | 基建 | Lettuce 客户端装配 + 无状态服务所需的最小共享状态 |
| &nbsp;&nbsp;├ `snail-framework-security` | 基建 | 令牌签发与校验、口令散列、认证主体与权限码判定入口 |
| &nbsp;&nbsp;├ `snail-framework-log` | 基建 | traceId 过滤器与 MDC、操作日志注解与切面；不落库，经端口下沉给业务层 |
| &nbsp;&nbsp;└ `snail-framework-excel` | 基建 | fastexcel 流式导出、分批导入 |
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
| 安全 | Spring Security + OAuth2 Resource Server，JWT 实现单一收敛在 Nimbus JOSE+JWT 10.9.1 |
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

`dev` 提供指向本机的默认值，口令不同用变量覆盖；`prod` 不提供任何默认值，六个变量必填。

| 变量 | 说明 |
| --- | --- |
| `SNAIL_PROFILE` | 激活的 profile，默认 `dev` |
| `SNAIL_DB_URL` | JDBC 连接串 |
| `SNAIL_DB_USERNAME` / `SNAIL_DB_PASSWORD` | 数据库账密 |
| `SNAIL_REDIS_URL` | 缓存服务连接串 |
| `SNAIL_JWT_SECRET` | JWT 签名密钥，长度 ≥ 32；dev 有开发占位默认值，**prod 必填，缺失启动即失败** |
| `SNAIL_SEED_ADMIN_PASSWORD` | 内置 admin 种子口令的 **BCrypt 密文**（非明文，用 Hutool `BCrypt.hashpw` 离线生成）；**prod 必填**，V2 种子迁移用它写入 admin 初始口令，缺失迁移失败、启动被拦 |

本地覆盖请写在 `application-local.yml`，该文件已在 `.gitignore` 内。

## 配置要点

`snail-platform-start` 的三份配置文件承担全部装配语义：

- `application.yml`：应用名、虚拟线程开关、Flyway 位置、Caffeine 缓存名单点（`dictItem, menuTree, tenantConfig`）、springdoc 开关
- `application-dev.yml`：本机连接 + `com.snail.platform` 置 DEBUG
- `application-prod.yml`：连接串全部来自环境变量，且关闭文档端点
- `logback-spring.xml`：日志 pattern 含 `%X{traceId}`，与 `ApiResponse.traceId` 同源

## 路线图

1. 补齐 `snail-framework-*` 各构件的实现与自动配置
2. 落地 `snail-function-system` 的 `tenant/user/org/role/menu/dict/log` 子域及 `sys_*` 迁移脚本
3. 建立管理端前端线
4. 引入 AI / 数据侧工程线

## License

[MIT](LICENSE)
