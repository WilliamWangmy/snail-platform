# snail-framework-redis

Redis（Lettuce）接入与无状态服务所需的最小共享状态：键名收口与通用计数锁定器。

不做业务缓存（缓存走 Spring Cache + Caffeine），不做令牌黑名单（令牌吊销走 `token_version` 比对）。

- 所属工程：[`snail-platform-backend`](../../pom.xml)
- 基础包名：`com.snail.platform.framework.redis`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [键名收口](#键名收口)
  - [计数锁定器](#计数锁定器)
- [详细设计：按类](#详细设计按类)
- [配置项](#配置项)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- Redis 客户端依赖接入（`spring-boot-starter-data-redis`，连接参数由 start 侧 `spring.data.redis` 声明）；
- 全平台 Redis 键名的唯一收口：前缀与键段格式；
- "N 次失败锁 M 分钟"的通用计数锁定器，INCR 与窗口设置用 Lua 原子执行。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 业务缓存（字典、菜单、租户配置等） | Spring Cache + Caffeine（`snail-framework-core` 开闸，start 声明名单） |
| 令牌黑名单 / 会话存储 | 不存在；令牌吊销由 `token_version` 实现 |
| 登录失败、改密失败、在线会话的业务口径 | `snail-function-auth` / `snail-function-system` |
| 连接串、密码、超时 | `snail-platform-start` 的 `application*.yml` |

## 在平台中的位置

```
LoginFailGuard / CaptchaService / ProfileService
        │  失败计数
        ▼
SnailRateGuard ──Lua(INCR + PEXPIRE)──▶ Valkey / Redis
        ▲
        │  键名
SnailRedisKeys ──▶ snail:auth:* / snail:online:*

OnlineSessionService ──▶ snail:online:{tenantId}:{userId}
                      └─ snail:online:idx:{tenantId}
```

## 包结构

```
com.snail.platform.framework.redis
├── SnailRedisKeys.java   Redis 键名收口
└── SnailRateGuard.java   原子计数锁定器（@Component）
```

## 整体设计

### 键名收口

- 全平台键前缀 `snail:`；
- 认证/限频段 `snail:auth:*`，在线会话段 `snail:online:*`；
- 当前键：

| 常量 / 方法 | 形态 | 用途 |
| --- | --- | --- |
| `PROFILE_PWD_FAIL` | `snail:auth:profile-fail:{userId}` | 自助改密失败计数 |
| `ONLINE` / `online(tenantId, userId)` | `snail:online:{tenantId}:{userId}` | 在线会话详情 |
| `ONLINE_INDEX` / `onlineIndex(tenantId)` | `snail:online:idx:{tenantId}` | 租户在线会话索引（SET，成员为用户 ID） |

业务侧不允许手拼裸键；新增键段只改本类。

### 计数锁定器

- 脚本：`INCR` 后仅当计数为 1 时 `PEXPIRE`，两步在一个 Lua 脚本里原子执行；
- 结果：窗口从首次失败起算，首个请求进程死掉也不会留下永不过期的计数键；
- 三个动作：`isLocked(key, threshold)` 判阈值、`record(key, window)` 记一次并返回当前计数、`clear(key)` 成功后清零；
- 锁定是运行态，只存 Valkey，不落业务表。

## 详细设计：按类

| 类 | 职责 | 关键约束 |
| --- | --- | --- |
| [`SnailRedisKeys`](src/main/java/com/snail/platform/framework/redis/SnailRedisKeys.java) | 键名常量与组合方法 | 所有键以 `snail:` 开头；键段格式只在此处定义 |
| [`SnailRateGuard`](src/main/java/com/snail/platform/framework/redis/SnailRateGuard.java) | 计数锁定 | INCR/PEXPIRE 原子化；键由调用方经 `SnailRedisKeys` 组合 |

## 配置项

本模块无自有配置前缀，连接由 start 侧声明：

| 配置键 | 说明 |
| --- | --- |
| `spring.data.redis.url` | 连接串；dev 默认 `redis://127.0.0.1:6379`，prod 必填 `SNAIL_REDIS_URL` |

## 使用方式

```java
String key = SnailRedisKeys.PROFILE_PWD_FAIL + userId;
if (rateGuard.isLocked(key, 5)) {
    throw BizException.badRequest("失败次数过多,请稍后再试");
}
long count = rateGuard.record(key, Duration.ofMinutes(10));
// 成功路径
rateGuard.clear(key);
```

## 验证手段

- 本模块无独立单测；
- 行为由 start 侧流程测试覆盖：`SelfSecurityFlowTest` 覆盖自助改密 5 次锁 10 分钟与在线会话可见/强制下线，
  `LoginFlowTest` / `AuthSessionFlowTest` 覆盖登录态与令牌失效；
- 全量回归：在 `snail-platform-backend` 执行 `mvn test`（需要可连的 Valkey）。

## 已知边界与注意事项

- **不是缓存模块**：业务缓存不允许写到这里，缓存的名单与 TTL 只在 `application.yml`；
- **不是令牌存储**：令牌是无状态 JWT，吊销靠 `token_version` 比对，没有黑名单键；
- **原子性只在脚本内**：`isLocked` 与 `record` 是两次独立调用，判定与计数之间的并发窗口由业务口径接受；
- **键必须经 `SnailRedisKeys`**：手拼键会破坏前缀/键段约定，也让清理与排障失去单点；
- **锁定不落表**：清空 Valkey 等于清空锁定状态，这是运行态语义，不是审计事实。
