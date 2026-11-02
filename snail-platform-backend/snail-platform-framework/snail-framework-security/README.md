# snail-framework-security

认证与授权基础设施：JWT 签发与校验、认证过滤器、会话吊销、强制改密兜底、口令回传加密、权限码判定入口。

它不查账户表、不判断"账户是否存在"这类业务事实；人员/组织/角色/权限码全部经端口由业务侧实现。

- 所属工程：[`snail-platform-backend`](../../pom.xml)
- 基础包名：`com.snail.platform.framework.security`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [安全链与白名单](#安全链与白名单)
  - [认证流程](#认证流程)
  - [令牌与吊销](#令牌与吊销)
  - [强制改密闸](#强制改密闸)
  - [口令回传加密](#口令回传加密)
  - [权限码判定](#权限码判定)
- [详细设计：按类](#详细设计按类)
- [配置项](#配置项)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- HS256 JWT 的唯一签发与校验，载荷只放 `uid/tid/uname/oid/ver` 五项身份事实；
- Bearer 令牌 → `SecurityContext` + 租户上下文 + 登录人快照的认证过滤器；
- 令牌吊销：令牌 `ver` 声明与登录人快照 `tokenVersion` 比对；
- 强制改密兜底闸：待改密会话只放行改密/登出/本人信息三条；
- 一次性口令回传的 AES-256-GCM 加密（服务端加密、管理端解密）；
- 401/403 的 JSON 输出与 `@PreAuthorize` 拒绝翻译；
- 无状态安全链、方法级鉴权开关、可选 CORS 白名单；
- 三个业务端口：权限码、登录人快照、在线心跳。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 登录/验证码/失败锁定 | `snail-function-auth` |
| 用户、角色、菜单、权限码的来源 | `snail-function-system`（`UserPermissionService` / `UserSessionService`） |
| 在线会话存储与踢人 | `snail-function-system` 的 `OnlineSessionService` |
| 口令散列与校验 | 业务侧（Hutool BCrypt），本模块只有传输加密 |
| 租户行级过滤、审计填充 | `snail-framework-db` |
| 业务接口权限码声明 | 各 Controller 上的 `@PreAuthorize` |

## 在平台中的位置

```
请求 ──▶ TraceFilter（core）
      ──▶ Spring Security 过滤链
            ├─ SnailAuthFilter              Bearer → SecurityContext / 租户 / 登录人快照
            ├─ ForcePasswordChangeFilter    待改密会话只能打三条白名单
            └─ 授权规则 anyRequest().authenticated()
                  └─ @PreAuthorize（方法级权限码）

端口实现（function 侧）：
  UserSessionService     implements LoginUserProvider
  UserPermissionService  implements UserAuthorityProvider
  OnlineSessionService   implements SessionHeartbeatPort
```

## 包结构

```
com.snail.platform.framework.security
├── SnailSecurityProperties.java          snail.security.* 配置
├── authority/
│   ├── AuthorityChecker.java             非 Controller 层的权限码判定
│   └── UserAuthorityProvider.java        权限码端口
├── config/
│   └── SnailSecurityConfig.java          安全链、CORS、JWT 与口令加密器装配
├── crypto/
├── heartbeat/
│   └── SessionHeartbeatPort.java         在线心跳端口
├── login/
│   └── LoginUserProvider.java            登录人快照端口
├── token/
│   ├── JwtTokenService.java              JWT 签发与校验
│   └── SnailPrincipal.java               身份五元组
└── web/
    ├── ForcePasswordChangeFilter.java    强制改密兜底闸
    ├── SecurityExceptionHandler.java     AccessDeniedException → 403
    └── SnailAuthFilter.java              认证过滤器
```

## 整体设计

### 安全链与白名单

- 会话策略 `STATELESS`，禁用 CSRF（令牌走 `Authorization` 头）；
- 白名单只收认证入口与接口文档："`/system/auth/captcha`、`/system/auth/login`、`/v3/api-docs/**`、
  `/swagger-ui/**`、`/swagger-ui.html`"，其余 `anyRequest().authenticated()`；业务端点不允许加进来；
- `@EnableMethodSecurity` 打开方法级鉴权，权限码由 `@PreAuthorize` 判定；
- CORS 默认关闭：`snail.security.cors.allowed-origins` 留空即不下发 CORS 头；配置后来源必须逐名写全（不给 `*`），
  `allowCredentials` 保持默认 `false`（令牌不是 Cookie）；
- 401/403 由入口点直接写与 `R` 同构的最小 JSON，不引序列化器。

### 认证流程

`SnailAuthFilter` 每个请求执行：

1. 读 `Authorization: Bearer <token>`；
2. `JwtTokenService.verify` 校验签名与有效期；失败只保持匿名（是否拒绝交给授权规则）；
3. **先注入租户** `TenantContextHolder.set(tenantId)`——权限解析要读租户托管表，必须先于 Provider 调用；
4. 经 `LoginUserProvider` 取登录人快照（实现侧缓存）；端口缺省时降级为令牌身份最小快照；
5. 会话有效性判定（见下节），不通过则整体视为匿名；
6. 写入 `LoginUserContextHolder`、`SecurityContext`（`authenticated` + 权限码）；
7. 经 `SessionHeartbeatPort` 记录在线心跳（辅助观测，失败不影响认证）；
8. `finally` 必清三份上下文；
9. 异步派发不跳过过滤（`shouldNotFilterAsyncDispatch=false`），流式响应会重建同一份认证上下文。

两个过滤器**不注册为 Spring Bean**：否则 Servlet 容器会把它们再挂一遍全局 filter 链，导致双跑与上下文覆盖。

### 令牌与吊销

- 载荷：`uid` 用户、`tid` 租户、`uname` 登录名、`oid` 组织、`ver` 令牌版本；`sub` 为用户 ID；
- 有效期 `snail.security.jwt.expire-minutes`（默认 120 分钟）；`expiresInSeconds()` 供登录响应回带；
- 老令牌没有 `ver` 时按 `0` 计，与建列默认值同口径；`oid` 允许缺失（null）；
- 吊销不靠黑名单，而靠 `token_version`：
  - 改密、重置、停用、强制下线时业务侧自增 `sys_user.token_version`；
  - 过滤器将令牌 `ver` 与快照 `tokenVersion` 比对，不一致即视同已吊销，保持匿名交授权规则出 401；
  - 快照 `tokenVersion == null`（用户已删/停用、租户停用/到期）→ 直接判无效，不降级为"仅认证"；
  - `LoginUserProvider` 端口缺席（framework 单独使用）时不比对版本，降级放行。

### 强制改密闸

- 判定输入是 `SnailAuthFilter` 刚装好的登录人快照：`passwordChangeRequired=true`；
- 该标记由 `sys_user.password_modify_time IS NULL` 派生（业务实现负责）；
- 放行集是 `(方法, 路径)` 精确匹配，不收模式：

| 放行请求 | 用途 |
| --- | --- |
| `POST /system/profile/password/force-change` | 执行强制改密 |
| `POST /system/auth/logout` | 退出登录 |
| `GET /system/auth/me` | 恢复会话态所需的只读本人信息 |

- 其余请求回 403 `{"code":403,"message":"请先完成强制改密"}`；
- 匿名请求不进本闸，仍由安全链入口点出 401；
- 口径改动须与前端路由守卫同步评审。

### 口令回传加密

- 算法：AES-256-GCM，每条随机 12 字节 IV、128 位认证标签；
- 报文：`Base64(iv ‖ 密文 ‖ tag)`，直接挂在 `password` 键出参（键名保持原样供审计脱敏）；
- 方向：服务端加密、管理端解密展示；响应体与操作日志里都不出现口令明文；
- 前端实现：`snail-platform-frontend-admin/src/utils/passwordTransfer.ts`，两侧报文格式与密钥必须同改；
- 边界：密钥随前端包分发，挡的是链路明文暴露，不是对持有前端包者的机密性保证。

### 权限码判定

- HTTP 入口用 `@PreAuthorize("hasAuthority('system:user:list')")`；
- 进入 Service 后的数据面判定用 `AuthorityChecker.has("platform:...")`；
- 权限码每请求经 `UserAuthorityProvider` 解析（JWT 只带身份四元组，不带权限）；
- 过滤器恒加 `ROLE_authenticated`，权限码去重后原样作为 `GrantedAuthority`；端口缺省时只剩 `authenticated`。

## 详细设计：按类

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| [`SnailSecurityProperties`](src/main/java/com/snail/platform/framework/security/SnailSecurityProperties.java) | 配置绑定 | 密钥不落代码；CORS 默认空 |
| [`SnailSecurityConfig`](src/main/java/com/snail/platform/framework/security/config/SnailSecurityConfig.java) | 安全链装配 | 白名单只收认证与文档；过滤器不注册为 Bean；401/403 同构 JSON |
| [`JwtTokenService`](src/main/java/com/snail/platform/framework/security/token/JwtTokenService.java) | 签发与校验 | HS256；密钥 <32 字符拒绝启动；校验失败统一 401 文案防探测 |
| [`SnailPrincipal`](src/main/java/com/snail/platform/framework/security/token/SnailPrincipal.java) | 身份五元组 | `ver` 缺失按 0；`orgId` 可空 |
| [`SnailAuthFilter`](src/main/java/com/snail/platform/framework/security/web/SnailAuthFilter.java) | 认证主链 | 先租户后 Provider；快照无版本即无效；`finally` 清三份上下文 |
| [`ForcePasswordChangeFilter`](src/main/java/com/snail/platform/framework/security/web/ForcePasswordChangeFilter.java) | 强制改密兜底 | 精确 `(方法,路径)` 放行集；不豁免异步派发 |
| [`SecurityExceptionHandler`](src/main/java/com/snail/platform/framework/security/web/SecurityExceptionHandler.java) | 方法级鉴权拒绝 | 403 + `R`；抢在全局 500 兜底之前 |
| [`UserAuthorityProvider`](src/main/java/com/snail/platform/framework/security/authority/UserAuthorityProvider.java) | 权限码端口 | 调用时租户已就绪；无权限返回空集合 |
| [`AuthorityChecker`](src/main/java/com/snail/platform/framework/security/authority/AuthorityChecker.java) | Service 层判权 | 未认证或无码一律 false |
| [`LoginUserProvider`](src/main/java/com/snail/platform/framework/security/login/LoginUserProvider.java) | 登录人快照端口 | 必须非 null；实现必须缓存；`minimal` 与过滤器共用兜底口径 |
| [`SessionHeartbeatPort`](src/main/java/com/snail/platform/framework/security/heartbeat/SessionHeartbeatPort.java) | 在线心跳端口 | 实现自行容错，不得阻断认证 |

## 配置项

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `snail.security.jwt.secret` | 无（dev 有占位） | HS256 密钥，≥32 字符；prod 必填 `SNAIL_JWT_SECRET` |
| `snail.security.jwt.expire-minutes` | `120` | 令牌有效期（分钟） |
| `snail.security.cors.allowed-origins` | 空 | 跨域来源白名单；留空不启用 CORS |

前端构建对应 `VITE_PASSWORD_TRANSFER_KEY`，必须与后端同值；不一致表现为"口令解密失败"。

## 使用方式

```java
// 登录成功后签发（业务 auth 模块）
String token = jwtTokenService.issue(new SnailPrincipal(userId, tenantId, username, orgId, tokenVersion));

// 接口准入
@PreAuthorize("hasAuthority('system:user:create')")
@PostMapping
public R<Long> create(@Valid @RequestBody UserCreateRequest request) { ... }

// Service 数据面判权
if (!AuthorityChecker.has("platform:log:list")) {
    throw BizException.forbidden("无权限查看跨租户日志");
}

// 建号/重置后一次性口令下发（业务 Controller）
return R.ok(Map.of("password", passwordTransferCipher.encrypt(rawPassword)));
```

## 验证手段

- `LoginFlowTest` / `AuthSessionFlowTest`：登录、令牌校验、会话失效；
- `LoginUserFlowTest`：登录人快照与租户隔离；
- `PasswordForceChangeFlowTest`：强制改密闸的 403 与三条放行；
- `SelfSecurityFlowTest`：自助改密、强制下线、令牌失效；
- `PermissionFlowTest`：权限码判定；
- 全量回归：在 `snail-platform-backend` 执行 `mvn test`。

## 已知边界与注意事项

- **无 refresh token、无黑名单**：无状态到期即失效是本期口径；吊销只靠 `token_version` 比对；
- **版本比对依赖 `LoginUserProvider`**：端口缺席时框架单独使用会降级放行，不阻止用户；
- **快照无版本 = 整体无效**：用户已删/停用、租户停用/到期时不降级为"仅认证"，避免停用用户继续访问；
- **强制改密是后端兜底**：前端路由守卫只是体验层，真正的闸在 `ForcePasswordChangeFilter`；
- **口令回传密钥在前端包里**：它是链路保护，不是端到端机密性；
- **过滤器不是 Bean**：不要加 `@Component`，否则会被容器重复挂载；
- **权限码不写进 JWT**：权限每请求解析，角色变更与收回即时生效（实现侧有缓存失效）。
