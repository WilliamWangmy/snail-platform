# snail-function-auth

认证业务模块：图形验证码、登录校验链、凭证暴力计数、JWT 签发接线与 `/system/auth` 四个端点。
它只负责“怎么证明你是谁”，令牌校验、权限判定与用户数据分别归框架层和系统模块。

- 所属工程：[`snail-platform-backend`](../../pom.xml)（Maven 三层：framework / function / start）
- 基础包名：`com.snail.platform.function.auth`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [端点与认证边界](#端点与认证边界)
  - [一次登录的校验链](#一次登录的校验链)
  - [令牌与吊销模型](#令牌与吊销模型)
  - [验证码生命周期](#验证码生命周期)
  - [多租户旁路与日志归属](#多租户旁路与日志归属)
  - [失败分类与计数维度](#失败分类与计数维度)
- [详细设计：按类](#详细设计按类)
- [存储与键约定](#存储与键约定)
- [接口契约](#接口契约)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- 统一下发 `CaptchaVO`，它携带图形验证码键和 base64 图片；
- 执行完整登录校验链，并按失败类型决定是否计数；
- 维护两个独立的失败计数维度：验证码按 IP 计数，凭证按 IP + 登录名计数；
- 在登录成功后签发 `token_version` 仍有效的 JWT，并返回身份与租户快照；
- 登出时自增 `token_version`，清掉 `userSession` 缓存快照，使当前用户全部设备立即失效；
- `/me` 端点返回当前登录人、组织与租户名称、生效角色与权限码；
- 每次业务成功或拒绝都写一条 `sys_login_log`，包含 IP、浏览器、系统和原因。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| JWT 签名/验签、认证过滤器、`SecurityContext`、`@PreAuthorize` 判定、401/403 输出 | `snail-framework-security` |
| `sys_user` / `sys_tenant` 的业务规则与写入口、`userSession` / `userPerms` 端口实现 | `snail-function-system` |
| 租户行级过滤 SQL、审计字段填充、分页 | `snail-framework-db` |
| 登录日志与操作日志的查询、导出 | `snail-function-system` 的 `log` 子域 |
| 前端令牌存取、路由守卫、按钮判权 | `snail-platform-frontend-admin` |
| 刷新令牌、令牌黑名单、单设备下线 | 本期不提供，见[已知边界](#已知边界与注意事项) |

本模块不提供 `@Configuration` 与启动类：`JwtTokenService` 由 framework-security 装配，业务 Bean 由
`snail-platform-start` 扫描进容器。模块自身只是一个被依赖的 jar。

## 在平台中的位置

```
前端登录页 ──GET  /system/auth/captcha──▶ AuthController ──▶ CaptchaService ──▶ Valkey
           ──POST /system/auth/login────▶ AuthController ──▶ AuthService
                                                                ├─▶ LoginFailGuard  ──▶ SnailRateGuard ──▶ Valkey
                                                                ├─▶ CaptchaService  ──▶ Valkey
                                                                ├─▶ SysUserMapper / SysTenantMapper ──▶ MySQL
                                                                ├─▶ SysLoginLogMapper ──▶ sys_login_log
                                                                ├─▶ JwtTokenService ──▶ JWT(HS256)
                                                                └─▶ CacheEvictor ──▶ userSession 缓存

后续请求 ──Authorization: Bearer <token>──▶ SnailAuthFilter(framework-security)
                                              ├─▶ JwtTokenService.verify
                                              ├─▶ LoginUserProvider ──▶ UserSessionService(userSession 缓存)
                                              └─▶ UserAuthorityProvider ──▶ UserPermissionService(userPerms 缓存)
```

跨模块依赖只有一条：`snail-function-auth → snail-function-system`。它直接读 system 的 PO/Mapper，并使用
`CacheEvictor` 点清 `userSession`，不调用 system 的 service；这条边是 ArchUnit 登记在案的整体豁免，
新模块不能效仿。`pom.xml` 里也注明了收敛方向：业务互调变密时改走 system 的 `api` 包契约。

认证只负责“认证”。每次请求的租户注入、登录人快照与权限码解析都在 `SnailAuthFilter` 内完成，
本模块的 `/me` 只是把这些上下文取出来返回。

## 包结构

```
com.snail.platform.function.auth
├── controller/
│   └── AuthController.java      /system/auth 四端点；不含业务分支
├── service/
│   ├── AuthService.java         登录校验链、登出、登录日志
│   ├── CaptchaService.java      验证码生成、限流、一次性核销
│   ├── LoginFailGuard.java      凭证维度的计数与锁定判定
│   └── LoginRejects.java        登录失败的三类内部异常
└── dto/
    ├── CaptchaVO.java           验证码出参
    ├── LoginRequest.java        登录入参契约
    ├── LoginSuccessVO.java      登录成功出参
    └── LoginUserVO.java         当前登录人出参
```

## 整体设计

### 端点与认证边界

四个端点全部挂在 `/system/auth` 下：

| 方法 路径 | 准入 | 说明 |
| --- | --- | --- |
| `GET /system/auth/captcha` | 匿名 | 签发验证码，`image` 是可直接放进 `<img>` 的 PNG data-uri |
| `POST /system/auth/login` | 匿名 | 账号 + 口令 + 验证码，成功返回 JWT 与身份快照 |
| `POST /system/auth/logout` | 已登录 | 自增令牌版本，当前用户全部设备立即失效 |
| `GET /system/auth/me` | 已登录 | 当前登录人、组织/租户名称、生效角色与权限码 |

匿名端点有**两层**准入声明：`SnailSecurityConfig` 的路径白名单与 Controller 上的
`@PreAuthorize("permitAll()")`。前者让请求在没有令牌时也能到达端点，后者是方法级兜底；
其余端点一律 `isAuthenticated()`。白名单只收认证入口与接口文档路径，业务端点不允许加进来。

`/me` 同时是**过滤器链的自检点**：令牌合法时返回 `code=0`；令牌被吊销、用户停用或租户停用时，
`SnailAuthFilter` 保持匿名，最终由安全链入口点返回 HTTP 401。集成测试用 `/me` 断言会话是否仍然有效。

### 一次登录的校验链

`AuthService.login` 是唯一的登录实现，顺序固定：

1. **规范化与锁定检查**：登录名去首尾空白，取来源 IP；同一 IP + 登录名已达到凭证失败阈值时直接拒绝，
   不再消耗验证码，也不做密码比对。
2. **验证码核销**：校验失败按验证码维度计数；校验通过后该码即作废。
3. **定位用户**：按全表唯一的 `username` 查 `sys_user`。登录时尚无租户上下文，这一步走
   `TenantContextHolder.withoutTenant` 的窄旁路窗口，窗口只覆盖这一条查询。
4. **租户状态**：按 `user.tenant_id` 定点取 `sys_tenant`，校验是否存在、是否停用、是否到期。
5. **用户状态**：校验账号是否停用。
6. **口令比对**：BCrypt 比对；前置步骤都不需要哈希，停用账号在此之前就被拒。
7. **成功收尾**：清零本来源的凭证失败计数，签发 JWT，写一条成功登录日志，返回身份与租户快照。

任何一步的业务拒绝都会写登录日志；只有 `LoginRejects.Credential` 会累加凭证失败计数，
验证码失败由 `CaptchaService` 自行计数，租户/账号停用属于运营状态、重试无意义，不计数。

用户不存在与口令错误共用“账号或密码错误”文案，避免通过登录接口枚举账号。

### 令牌与吊销模型

`JwtTokenService`（framework-security）以 HS256 签发，载荷只有五项身份事实：

| claim | 含义 |
| --- | --- |
| `uid` | 用户 ID |
| `tid` | 租户 ID |
| `uname` | 登录名，日志快照直接取用 |
| `oid` | 组织 ID，操作日志按部门留痕用 |
| `ver` | 令牌版本，吊销口径 |

有效期由 `snail.security.jwt.expire-minutes` 决定，当前 `120` 分钟；密钥必须至少 32 字符，
否则启动失败。本期没有 refresh token，也没有 jti 黑名单。

**吊销链**：`sys_user.token_version` 是唯一开关。

```
登出 / 改密 / 重置口令 / 停用用户 / 强制下线
  → token_version + 1
  → 快照缓存 userSession 点清（登出在事务提交后点清）
  → SnailAuthFilter 下次请求比对令牌 ver 与快照 tokenVersion
  → 不一致即保持匿名 → 安全链返回 401
```

登出必须同时清 `userSession`：否则下次登录时过滤器读到旧版本快照，会把刚签发的新令牌判成已吊销。
键的租户段取自当前请求的租户上下文，拼法与 `UserSessionService` 的 `@Cacheable` 完全一致（`租户:用户`）。

### 验证码生命周期

```
生成 → 答案写 Valkey(2 分钟) → 登录时 GETDEL 核销 → 对错都不再可用
         │
         └─ 同一 IP 每分钟最多 20 张，超出直接拒绝
```

- 图片为 120x40 的线扰验证码，4 位字符、10 条干扰线；位数与前端登录页的 `len=4` 校验一致。
- 答案只存 Valkey，不落表、不写日志。
- 校验是**一次性**的：`GETDEL` 原子取回并删除，同一 `captchaKey` 无论答案对错都只可能被核销一次，
  并发重放也只有一个请求能拿到答案。
- 校验失败另有独立计数：同一 IP 十分钟内达到 10 次后不再比对答案，直接拒绝。
- 与账号锁定互不影响：验证码失败不会锁死账号，凭证失败也不会要求重新取码以外的额外动作。

### 多租户旁路与日志归属

`sys_user` 是租户过滤表，但登录发生在租户上下文建立之前。因此第 3 步用
`withoutTenant` 开一个只覆盖单条查询的旁路，按全表唯一的登录名定位用户。
`sys_tenant` 本身在拦截器 ignore 清单，第 4 步可以按 ID 直接读取，不需要再摘租户条件。

登录日志的 `tenant_id` 有三种来源：

- 已定位到用户：记用户所属租户；
- 用户定位前就失败：记 `0`，包括验证码失败、锁定拦截、账号不存在；
- 登录成功：记用户所属租户，并回填 `user_id`。

`tenant_id=0` 的行只有持跨租户码的平台侧能看到，语义是“登录链还没定位到用户”，不是“归属不明”。

### 失败分类与计数维度

三类失败共用同一条 `BizException` 通道，但计数处置不同：

| 分类 | 触发场景 | 计数 | 阈值与窗口 |
| --- | --- | --- | --- |
| `LoginRejects.Credential` | 用户不存在、口令错误 | 按 IP + 登录名累加 | 5 次 / 10 分钟 |
| `LoginRejects.Captcha` | 验证码缺失、过期、答案错误 | 由 `CaptchaService` 按 IP 自计 | 10 次 / 10 分钟 |
| `LoginRejects.Access` | 租户停用/到期、账号停用 | 不计数 | — |

分类只服务计数，不改变对外文案与错误码：三者都是 `400`，由 `GlobalExceptionHandler` 折成
`R{code, message, data}`。锁定命中走 `BizException.badRequest`，文案统一为“失败次数过多,请稍后再试”。

计数窗口都从**首次失败**起算，不是滑动窗口；锁定期间的重试不累加，避免每次重试都在延长窗口。
凭证登录成功或验证码校验通过即清零对应计数。

## 详细设计：按类

### `AuthController`

- 只有四个方法，对应上表四个端点；业务分支全部下沉到 service。
- `captcha` 取 Hutool `getClientIP` 的来源 IP 交给 `CaptchaService.generate`。
- `login` 用 `@Valid` 校验请求契约后交给 `AuthService.login`。
- `logout` 从 `LoginUserContextHolder` 取当前用户 ID；认证通过时快照必在。
- `me` 把请求级快照与 `UserAuthorityProvider.authoritiesOf(userId)` 的权限码组装成 `LoginUserVO`；
  快照为空的分支是防御性兜底，正常链路不可达。

### `AuthService`

- **`login`**：校验链主体。`user` 变量在 `try` 外声明，失败分支据此决定日志归属租户与是否计数。
  `LoginRejects.Credential` 在统一 `catch` 中累加一次失败；其余分类只写日志。
- **`logout`**：单条 SQL 自增 `token_version`，随后 `CacheEvictor.evict("userSession", 租户:用户)`。
  `@Transactional` 保证版本更新与缓存失效的顺序语义：缓存清在事务提交之后，避免提交前的并发读
  把旧版本快照回填进来。
- **`writeLoginLog`**：解析 `User-Agent` 填浏览器/系统，落库失败直接吞掉。登录留痕是旁路，
  数据库异常不能反过来改变登录结果；这次失败也不会被重新记录。
  用户 ID 只在成功与“已定位到用户”的失败中回填。

### `CaptchaService`

- **`generate`**：先查生成频次阈值，再记录一次生成，然后画图、把答案以 `captchaKey` 为键写入 Valkey，
  有效期 2 分钟。
- **`verify`**：先查失败阈值；参数缺失、答案不存在、答案不匹配三种情况都计一次失败并抛
  `LoginRejects.Captcha`；通过则清零失败计数。
- **`clientKey`**：来源为空时归一到 `unknown`，避免拼出键头为空的歧义键。
- 生成与校验使用两个独立的计数桶，互不影响。

### `LoginFailGuard`

- 只持有登录场景的键名与阈值，原子计数逻辑在 framework-redis 的 `SnailRateGuard`（Lua 打包 INCR + PEXPIRE）。
- 键是 `IP + 登录名` 的组合：只按登录名计数会让任何人都能用假用户名把真实账号刷进锁定；
  组合键让锁定只在同一来源成立，来源轮换由部署侧限流负责。
- `clearCredentialFails` 在登录成功后清零；其他来源的计数随窗口自然过期。
- 计数只存 Valkey：锁定是运行态，不是审计，不落表。

### `LoginRejects`

包级私有的失败分类容器，三个子类都继承 `BizException`、都使用错误码 400：

- `Credential`：账号或密码错误，进入 IP + 登录名计数桶；
- `Captcha`：验证码缺失/过期/错误，进入 IP 验证码计数桶；
- `Access`：租户或账号处于停用/到期状态，不计数。

分类只被 `AuthService` 与 `CaptchaService` 用于计数判断，对外表现与普通 `BizException` 一致。

### DTO 契约

`LoginRequest`（入参）：

| 字段 | 校验 | 说明 |
| --- | --- | --- |
| `username` | `@NotBlank`、最长 30 | 服务端会去首尾空白 |
| `password` | `@NotBlank`、最长 64 | 只做比对，不落日志 |
| `captchaKey` | `@NotBlank` | 验证码接口返回的键，原样回传 |
| `captchaCode` | `@NotBlank`、最长 8 | 比对时去空白、忽略大小写 |

`CaptchaVO`：`captchaKey` + `image`（PNG data-uri）。

`LoginSuccessVO`：`token`、`tokenType`（固定 `Bearer`）、`expiresIn`（秒）、`userId`、`username`、
`nickname`、`tenantId`、`tenantName`。字段是登录时刻的快照，后续变更以 `/me` 为准。

`LoginUserVO`：身份字段 + `orgId`/`orgName` + `roles`（角色摘要，按快照顺序）+ `permissions`
（权限码，自然序）。组织名可能为空（组织被删或未归属）。

## 存储与键约定

### Valkey / Redis

| 键 | 值 | TTL / 窗口 | 写入方 |
| --- | --- | --- | --- |
| `snail:auth:captcha:{captchaKey}` | 验证码答案 | 2 分钟，核销即删 | `CaptchaService.generate` |
| `snail:auth:captcha-issue:{ip}` | 生成次数 | 1 分钟窗口，阈值 20 | `CaptchaService.generate` |
| `snail:auth:captcha-fail:{ip}` | 校验失败次数 | 10 分钟窗口，阈值 10 | `CaptchaService.verify` |
| `snail:auth:fail:credential:{ip}:{username}` | 凭证失败次数 | 10 分钟窗口，阈值 5 | `LoginFailGuard` |

计数原子性由 `SnailRateGuard` 的 Lua 脚本保证：只有第一次 `INCR` 时挂过期时间，窗口从首次失败起算。

### 缓存

本模块**只清不建**缓存：

| 缓存 | 键 | 本模块动作 |
| --- | --- | --- |
| `userSession` | `tenantId:userId` | 登出时按当前租户 + 用户点清 |
| `userPerms` | `userId` | 只读：`/me` 经 `UserAuthorityProvider` 取权限码 |

两个缓存的生产处都在 `snail-function-system`，缓存名单与规格在
`snail-platform-start/src/main/resources/application.yml` 单点声明。

### 数据库

| 表 | 动作 | 说明 |
| --- | --- | --- |
| `sys_user` | 读、更新 | 按登录名旁路定位；登出自增 `token_version` |
| `sys_tenant` | 读 | 在租户拦截器 ignore 清单，按 ID 直接取 |
| `sys_login_log` | 写 | 只增；`status` 1 成功 / 0 失败；`tenant_id=0` 表示定位用户前失败 |

## 接口契约

### 响应信封

统一 `R{code, message, data}`：成功 `code=0`、`message="ok"`。业务拒绝返回 **HTTP 200 + 非 0 业务码**，
由 `GlobalExceptionHandler` 翻译；只有传输层与鉴权层的拒绝落到真实状态码：

- 未认证 / 令牌过期：HTTP 401 `{"code":401,"message":"未认证或登录已过期","data":null}`
- 已认证但无权限：HTTP 403 `{"code":403,"message":"无权限访问","data":null}`
- 路由或方法不匹配：HTTP 404 / 405

### 取验证码

```bash
curl -s http://localhost:8080/system/auth/captcha
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "captchaKey": "3f2c1a...",
    "image": "data:image/png;base64,iVBORw0KGgo..."
  }
}
```

### 登录

```bash
curl -s http://localhost:8080/system/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123","captchaKey":"<上一步的 key>","captchaCode":"<图中的 4 位>"}'
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "userId": 1,
    "username": "admin",
    "nickname": "管理员",
    "tenantId": 1,
    "tenantName": "默认租户"
  }
}
```

### 当前登录人

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/system/auth/me
```

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "userId": 1,
    "username": "admin",
    "nickname": "管理员",
    "tenantId": 1,
    "tenantName": "默认租户",
    "orgId": 1,
    "orgName": "默认租户",
    "roles": [{ "roleId": 1, "roleCode": "admin", "roleName": "管理员" }],
    "permissions": ["platform:tenant:list", "system:user:list", "system:user:create"]
  }
}
```

### 登出

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" http://localhost:8080/system/auth/logout
```

响应 `{"code":0,"message":"ok","data":null}`。该用户全部设备的存量令牌在下一次请求时失效。

### 业务错误文案

| 场景 | code | message |
| --- | --- | --- |
| 验证码生成过频 | 400 | 验证码获取过于频繁,请稍后再试 |
| 验证码键或字符为空 | 400 | 请输入验证码 |
| 验证码不存在/已核销 | 400 | 验证码已过期,请重新获取 |
| 验证码答案不匹配 | 400 | 验证码错误 |
| 验证码失败次数超限 | 400 | 验证码错误次数过多,请稍后再试 |
| 账号不存在或口令错误 | 400 | 账号或密码错误 |
| 租户不存在/停用 | 400 | 租户已停用 |
| 租户到期 | 400 | 租户已到期 |
| 用户停用 | 400 | 账号已停用 |
| 凭证失败次数超限 | 400 | 失败次数过多,请稍后再试 |
| 入参校验失败 | 400 | `<字段>: <校验文案>`，如 `username: 请输入登录名` |

## 使用方式

### 构建与启动

模块自身不产出可执行 jar，必须经 `snail-platform-start` 装配：

```bash
cd snail-platform-backend
mvn clean install                                     # 全量构建
mvn -pl snail-platform-start spring-boot:run           # dev profile，连本机 MySQL + Valkey/Redis
```

只编译本模块（改完先过这一关）：

```bash
mvn -q -pl snail-platform-function/snail-function-auth -am -DskipTests compile
```

关键配置：

| 配置 | 说明 |
| --- | --- |
| `snail.security.jwt.secret` | `SNAIL_JWT_SECRET`，至少 32 字符；dev profile 有开发占位，prod 缺失即启动失败 |
| `snail.security.jwt.expire-minutes` | 令牌有效期，当前 `120` |
| `snail.security.cors.allowed-origins` | 留空不下发 CORS 头；配置时逐名写全，不支持 `*` |

### 走一遍登录

1. `GET /system/auth/captcha` 拿 `captchaKey` 与图片；
2. 识别图中 4 位字符；
3. `POST /system/auth/login` 提交账号、口令、`captchaKey`、`captchaCode`；
4. 后续请求带 `Authorization: Bearer <token>`；
5. `POST /system/auth/logout` 吊销全部设备。

自动化测试无法识别图片，测试代码直接按 `snail:auth:captcha:{key}` 的键规把答案写进 Valkey，
再走真实登录接口——绕过的只是“看图”，校验链本身仍完整执行。

### 前端接法

管理端（`snail-platform-frontend-admin`）：

| 位置 | 作用 |
| --- | --- |
| [`src/api/auth.ts`](../../../snail-platform-frontend-admin/src/api/auth.ts) | 四个端点的类型与请求封装 |
| [`src/stores/auth.ts`](../../../snail-platform-frontend-admin/src/stores/auth.ts) | 令牌、登录人快照、菜单与 `has()` 判权 |
| [`src/views/login/useLogin.ts`](../../../snail-platform-frontend-admin/src/views/login/useLogin.ts) | 验证码生命周期、表单校验、登录跳转 |

三条约定：

- 登录失败后必须重新取验证码——请求一旦带上 `captchaKey` 进入校验，该码无论对错都已核销；
  前端 `refreshCaptcha` 已内置这一行为。
- 所有请求走唯一 axios 出口 `src/api/http.ts`，令牌由它统一放进 `Authorization` 头。
- 跳转回填只接受站内路径（以 `/` 开头且不以 `//` 开头），避免 `redirect` 参数被拼成外部跳转。

### 常见改动

**新增一种登录失败分类**：

1. 在 `LoginRejects` 增加子类并决定是否计数；
2. 若计数，明确键维度（IP / IP + 登录名 / 其他）与阈值；
3. 在 `AuthService` 的 `catch` 分支接入，不要改变对外错误码约定；
4. 补集成测试断言文案与计数行为。

**调整阈值**：

- 验证码生成/失败阈值与窗口在 `CaptchaService` 常量；
- 凭证锁定阈值与窗口在 `LoginFailGuard` 常量；
- 改阈值不影响已存在的计数键，旧窗口按原 TTL 自然过期。

**新增认证端点**：

- 默认必须认证；确需匿名时同时改 `SnailSecurityConfig` 白名单与端点上的 `@PreAuthorize`，
  并在 README 与安全评审里说明理由；
- 业务端点不得加入白名单。

## 验证手段

集成测试都住在 `snail-platform-start`（唯一装配点）：

| 测试 | 覆盖 |
| --- | --- |
| [`LoginFlowTest`](../../snail-platform-start/src/test/java/com/snail/platform/auth/LoginFlowTest.java) | 验证码签发取证、登录链、BCrypt、JWT 签发与 `/me` 回验 |
| [`AuthSessionFlowTest`](../../snail-platform-start/src/test/java/com/snail/platform/auth/AuthSessionFlowTest.java) | 租户停用/到期、用户登出/停用/删除后的令牌吊销与重登 |

```bash
cd snail-platform-backend
mvn -q -pl snail-platform-function/snail-function-auth -am -DskipTests compile   # 模块编译
mvn -pl snail-platform-start test                                                 # 全部集成测试
```

两个测试类都带 `@EnabledIfEnvironmentVariable(named = "SNAIL_DB_USERNAME", matches = ".+")`：
需要本机 MySQL 与 Valkey，并注入 `SNAIL_DB_*` 口令；变量未设置时整类跳过而不是失败，
所以“测试全绿”之前要先确认它们真的执行了。

## 已知边界与注意事项

1. **没有 refresh token 与令牌黑名单**。令牌到期即失效；吊销依赖 `token_version` 比对，
   生效时点是“下一次请求”，不是长连接断开。
2. **登出是全局下线**。`token_version` 自增后该用户所有设备同时失效，本期没有单设备会话概念。
3. **登录日志不是账本**。落库失败被吞掉，不另行记录；基础设施故障（Redis/DB 不可用）直接 500，
   不会写登录日志。类注释的“必落一条”限定为业务成功与业务拒绝。
4. **两个计数桶互相独立**。验证码失败锁 IP，凭证失败锁 IP + 登录名；同一个 NAT 出口下的用户
   共享验证码计数，十分钟内累计 10 次失败会一起被挡。
5. **锁定期间不累加计数**。凭证锁定分支与验证码锁定分支都在计数之前返回，锁定窗口固定从首次失败起算；
   重试不会延长锁定，但也意味着“持续攻击”与“偶尔重试”的窗口长度相同。
6. **检查与累加不是同一原子操作**。并发请求可能略微超过阈值（第 5、6 个并发失败都可能通过锁定检查）；
   计数本身原子，超出的量级受并发度限制。
7. **来源 IP 信任转发头**。`JakartaServletUtil.getClientIP` 会读 `X-Forwarded-For` 等头，
   部署侧必须由可信代理归一化来源，否则计数键可被伪造头绕过。
8. **停用账号的文案会暴露账号存在性**。“账号已停用”“租户已停用/到期”只在用户已定位后出现，
   拿到验证码的人可据此判断某个登录名是否存在；用户不存在与口令错误仍共用同一文案。
9. **来源为空的归一化不完全一致**。`LoginFailGuard` 只把 `null` 归一成 `unknown`，
   `CaptchaService` 把 `null` 与空白都归一成 `unknown`；实际入口取到空串时，两类计数会落在不同形态的键上。
   影响限于计数键的可读性与一致性，不构成绕过。
10. **`/me` 的空快照分支不可达**。认证通过的请求过滤器必已注入快照；该分支抛出的
    `BizException.unauthorized` 若真被执行，经 `GlobalExceptionHandler` 会得到 HTTP 200 + `code=401`，
    与安全链入口点返回的 HTTP 401 不同。当前链路下不会走到。
11. **验证码位数与前端耦合**。服务端固定 4 位，管理端登录页的 `len=4` 校验独立写在前端；
    调整位数必须同时改 `CaptchaService` 与 `useLogin.ts`。
12. **口令长度上限是 64 字符**。BCrypt 只在 72 字节内可靠生效，超长会被静默截断，
    因此 DTO 在入口限长；更严格的口令策略归 `snail-function-system` 的 `PasswordPolicy`。
13. **`sys_login_log.tenant_id=0` 不是“归属不明”**，而是“登录链还没定位到用户就失败了”
    （验证码失败、锁定拦截、账号不存在）。平台侧跨租户查询才看得到这批行。
14. **白名单是安全边界**。`/system/auth/captcha` 与 `/system/auth/login` 是仅有的两个匿名业务端点；
    新增匿名端点必须同时改代码、白名单与文档，并说明攻击面。

## License

[MIT](../../../LICENSE)
