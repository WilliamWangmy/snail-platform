# snail-function-notify

站内通知业务模块：消息主体 + 收件行落库、收件箱读写与已读维护、平台公告跨租户扇出、站外通道（邮件）异步投递，
以及对其他业务模块暴露的唯一发送入口 `NotifyApi`。

它只负责“把一条消息送到该收的人面前并记下投递痕迹”；收件人是谁、还启用不启用，归 system 模块；
谁该收到这条通知，归调用它的业务模块。

- 所属工程：[`snail-platform-backend`](../../pom.xml)（Maven 三层：framework / function / start）
- 基础包名：`com.snail.platform.function.notify`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [端点与准入边界](#端点与准入边界)
  - [一次发送的落库链](#一次发送的落库链)
  - [收件箱口径：私人数据](#收件箱口径私人数据)
  - [已读与移除的语义](#已读与移除的语义)
  - [平台公告扇出](#平台公告扇出)
  - [出站投递模型](#出站投递模型)
  - [通道 SPI 与可用性自查](#通道-spi-与可用性自查)
  - [多租户口径](#多租户口径)
- [详细设计：按类](#详细设计按类)
- [存储与索引](#存储与索引)
- [接口契约](#接口契约)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- 承载 `NotifyApi`：业务模块发通知的唯一入口，一条命令 = 一条消息主体 + N 条收件行；
- 收件箱分页、未读数、单条详情、标记已读、全部已读、移出收件箱，全部限定“只能动自己的行”；
- 平台公告：向指定租户的全部启用用户扇出，并提供跨租户的公告检索与扇出人数统计；
- 收件人显示名、发送人署名在发送时刻快照落库，此后改名与停用都不回溯；
- 出站投递：事务提交后把消息交给投递线程，逐通道投递、失败重试一次，终态写 `sys_notify_outbound`；
- 邮件通道实现（单封 MimeMessage、收件人全部 BCC）与通道 SPI 的接入约定。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 用户是否存在、是否启用、邮箱地址本身 | `snail-function-system` 的 `UserBriefPort` |
| 租户是否存在、是否停用 | 同上，`enabledUsersOfTenant` 内部校验并抛业务拒绝 |
| 什么业务事件该发通知、发给哪些角色 | 调用方业务模块（如 workflow 的 `WorkflowNotifyService`） |
| JWT 校验、`@PreAuthorize` 判定、401/403 输出 | `snail-framework-security` |
| 租户行级过滤 SQL、审计列填充、分页装配 | `snail-framework-db` |
| 站内消息的实时推送（WebSocket/SSE） | 本期不提供，前端 30 秒轮询未读数 |
| 投递失败的重试队列、死信、补偿 | 本期不提供，见[已知边界](#已知边界与注意事项) |

本模块不提供 `@Configuration` 与启动类：`OutboundDispatcher`、`MailOutboundChannel`、`NotifyService` 都是
`@Component`/`@Service`，由 `snail-platform-start` 的组件扫描装配；模块自身只是一个被依赖的 jar。

## 在平台中的位置

```
业务模块（workflow / 将来的 billing…）
   │  NotifyApi.send(NotifySendCommand)        ← 跨模块只准 import notify.api 包
   ▼
NotifyService ────────────────────────────────┐
   ├─▶ UserBriefPort（system.api）──▶ sys_user / sys_user_role / sys_tenant
   ├─▶ SysNotifyMessageMapper ─────▶ sys_notify_message   （消息主体）
   ├─▶ SysNotifyRecipientMapper ───▶ sys_notify_recipient （一人一行）
   └─▶ OutboundDispatcher ──afterCommit──▶ 队列 ─▶ 投递线程
                                                  ├─▶ MailOutboundChannel ─▶ JavaMailSender
                                                  └─▶ SysNotifyOutboundMapper ─▶ sys_notify_outbound

平台管理员 ──POST /platform/notify/announcement──▶ NotifyPlatformController ─▶ NotifyService.announce
普通用户   ──/system/notify/**─────────────────▶ NotifyController ─────────▶ NotifyService（收件箱侧）
```

跨模块依赖有两条，且都被 ArchUnit 钉在 `api` 包上：

- 出边 `notify → system`：只准 import `com.snail.platform.function.system.api`（取 `UserBriefPort`），
  不许触达 `user`/`role`/`tenant` 子域内部；
- 入边 `业务模块 → notify`：只准 import `com.snail.platform.function.notify.api`（取 `NotifyApi`），
  不许直接 new 消息实体或调 `NotifyService`。

这两条由 `ArchitectureRulesTest` 的 `API_PACKAGE_ENFORCED`（值为 `NOTIFY`、`WORKFLOW`）机械执法：notify 是
按“目标态”写的模块，`pom.xml` 的注释也写明了这条约束，新增依赖时不要绕过它。

## 包结构

```
com.snail.platform.function.notify
├── api/
│   └── NotifyApi.java              跨模块发送契约 + NotifySendCommand（唯一对外入口）
├── controller/
│   ├── NotifyController.java       /system/notify 收件箱六端点
│   └── NotifyPlatformController.java  /platform/notify 公告两端点
├── service/
│   ├── NotifyService.java          发送 / 公告 / 收件箱全部业务分支，实现 NotifyApi
│   ├── OutboundDispatcher.java     有界队列 + 投递线程 + 终态落库 + 停机排空
│   ├── OutboundChannel.java        出站通道 SPI（channel/available/send + 两个 record）
│   └── MailOutboundChannel.java    邮件通道实现
├── entity/
│   ├── SysNotifyMessage.java       消息主体 PO
│   ├── SysNotifyRecipient.java     收件行 PO
│   └── SysNotifyOutbound.java      出站投递记录 PO（ignore 表）
├── mapper/
│   ├── SysNotifyMessageMapper.java      BaseMapper，无自定义 SQL
│   ├── SysNotifyRecipientMapper.java    BaseMapper，无自定义 SQL
│   └── SysNotifyOutboundMapper.java     BaseMapper，无自定义 SQL
└── dto/
    ├── NotifyInboxQuery.java       收件箱分页入参
    ├── NotifyInboxItemVO.java      收件箱出参（收件行 + 消息投影）
    ├── AnnouncementQuery.java      公告分页入参
    ├── AnnouncementSendRequest.java 公告发送入参
    └── AnnouncementVO.java         公告出参（消息 + 扇出人数）
```

## 整体设计

### 端点与准入边界

| 方法 路径 | 准入 | 说明 |
| --- | --- | --- |
| `GET /system/notify/page` | `system:notify:list` | 收件箱分页，谓词恒带本人 `user_id` |
| `GET /system/notify/unread-count` | 已登录 | 本人未读数，顶栏铃铛 30 秒轮询它 |
| `GET /system/notify/{recipientId}` | 已登录 | 单条详情；不是本人的行按“不存在”拒绝 |
| `PUT /system/notify/{recipientId}/read` | 已登录 | 标记已读，已读行幂等返回 |
| `PUT /system/notify/read-all` | 已登录 | 只把本人的未读行置为已读 |
| `DELETE /system/notify/{recipientId}` | 已登录 | 移出收件箱（逻辑删本人收件行） |
| `GET /platform/notify/page` | `platform:notify:list` | 公告分页，跨租户检索 |
| `POST /platform/notify/announcement` | `platform:notify:send` | 向指定租户全员发公告，带 `@OperLog` |

口径有两点是刻意的：

1. **收件箱除列表外只要求登录**。收件行是私人数据，服务层强制本人谓词，再挂按钮权限码对“读自己的消息”
   没有增量价值；`system:notify:list` 只决定菜单和入口是否出现。
2. **已读与移除不写操作日志**。它们是自助读态动作，不是管理动作，进审计面只会淹没有效记录；
   只有公告发送（面向全员、不可撤销）标了 `@OperLog`。

路由不冲突：`/unread-count`、`/read-all` 是字面量路径，Spring 的分段匹配优先于 `{recipientId}`。

### 一次发送的落库链

`NotifyService.send` 是所有发送路径的唯一实现，`announce` 也走它。顺序固定：

1. **入参归一与闸值**：标题、正文 `trim` 后为空即拒绝；`tenantId` 为空拒绝；收件人集去 null、去重，
   空集拒绝；超过 `MAX_RECIPIENTS = 5000` 拒绝（提示分批发送）。
2. **切到收件租户**：`TenantContextHolder.asTenant(command.tenantId(), ...)`。平台管理员给租户 1 发公告时，
   自身上下文是平台租户，消息与收件行必须落在收件人租户的 `tenant_id` 上，所以在这里显式换窗口，
   与 provision 侧“替别人写数据”的口径一致。
3. **插消息主体**：标题、正文、`bizType`、`bizId`、发送人快照、`send_time = now`；`senderName` 空白时写 `系统通知`。
4. **取收件人快照**：`userBriefPort.briefs(recipients)`。端口只返回“当前租户口径下存在”的用户，
   跨租户与不存在的 ID 静默缺席。
5. **快照为空即回滚**：一个收件人都取不到时抛业务拒绝，事务把第 3 步插入的消息主体一起回滚，
   库里不留没有收件人的空壳消息。
6. **批量插收件行**：每个有效收件人一行，带 `nickname` 快照、`read_status = 0`；经 `Db.saveBatch` 分批提交，
   它复用当前事务的 `SqlSession`，所以租户注入与审计填充同样生效。
7. **登记提交后投递**：`dispatcher.dispatchAfterCommit(job)`。出站是尽力而为，绝不能让 SMTP 卡住业务事务，
   所以只在 `afterCommit` 回调里入队；此时不在事务中（如测试直调）才立即入队。

事务边界即第 3~6 步：站内消息与收件行要么一起成立，要么都不存在。第 7 步在提交之后，失败不回滚审批或公告。

### 收件箱口径：私人数据

- 查询谓词永远是 `user_id = 当前登录人`，**不走数据权限开窗**——数据范围（本人/部门/全租户）对
  “自己的消息”没有意义，开窗反而会把别人的收件行漏进来。
- `bizType` 挂在消息主体上，收件行没有这个列，所以先在 `sys_notify_message` 上按标签取出消息 ID 集，
  再用 `IN` 过滤收件行。取到空集时**直接返回空页**：空集合拼进 `IN` 是 SQL 语法错误，不是“查无结果”。
- 列表页取完收件行后，用 `selectBatchIds` 一次批量回查消息主体，避免逐条 N+1；
  映射时假定主体行存在（当前没有删除消息主体的入口，见[已知边界](#已知边界与注意事项)）。
- 未读数 `count(*)` 走 `idx_nr_inbox (tenant_id, user_id, read_status, recipient_id)` 直达，
  **不加缓存**：读得频繁但基数小，失效面（已读、移除、新消息三个动作都要点清）不值得。
- 详情、标已读、移除都先过 `requireOwnRecipient`：行不存在与行属于他人**返回同一句“通知不存在”**，
  存在性本身就是隐私，不区分“无权”和“没有”，免得端点变成收件行 ID 的探针。
- 服务层再兜一次登录态：控制器已要求认证，但 `NotifyApi` 是内部直调入口，未来的非 HTTP 调用不受保护。

### 已读与移除的语义

| 动作 | 改什么 | 幂等性 |
| --- | --- | --- |
| 标记已读 | 本人未读行 `read_status 0→1`，写 `read_time` | 已读行直接返回，不重复写 `read_time` |
| 全部已读 | 只更新 `user_id = 本人 AND read_status = 0` 的行 | 多次调用结果一致 |
| 移出收件箱 | 收件行逻辑删除（`deleted = 1`） | 重复删除按“不存在”拒绝 |
| 消息主体 | 谁都不动 | 公告对其他人仍然可见 |

移除是个人的收件箱整理，不是删除消息：一条公告扇出 500 人，其中一人移出，不该让别人的列表少一条。
已读时间用 `LocalDateTime.now()` 显式 `set`，因为更新走的是 `lambdaUpdate`，不经过实体的自动填充。

### 平台公告扇出

`announce(AnnouncementSendRequest)` 是发送的调用方之一，它决定“谁该收到”：

1. 从登录上下文取当前平台管理员，作为发送人快照（`senderId` / `senderName`）；上下文缺席时署名兜底 `系统通知`。
2. 调 `userBriefPort.enabledUsersOfTenant(tenantId)` 拿目标租户的全部启用用户。这一步顺带完成校验：
   租户不存在抛 `租户不存在`，租户已停用抛 `租户已停用`——所以公告发不进停用租户。
3. 人数超过 5000 直接拒绝，提示改用业务接口分批发送；扇出规模失控比发送失败更贵。
4. 以 `bizType = announcement`、`bizId = null` 走标准 `send`，此后与站内通知同链路。

`announcements(AnnouncementQuery)` 是平台面的跨租户检索：固定 `biz_type = announcement`、标题模糊，
读消息主体和扇出人数都在 `TenantContextHolder.withoutTenant(...)` 的窄窗口里做（平台管理动作，登记在案的旁路）。
扇出人数一次 `group by message_id` 查出，逐条 count 会是 N+1。

### 出站投递模型

`OutboundDispatcher` 是站内落库之外的第二条腿，形态是**有界队列 + 单条专用线程**：

```
afterCommit ─▶ queue.offer(job)         容量 2000；满 → 丢弃 + warn，不反压业务线程
                  │
                  ▼        （daemon 线程 "notify-outbound"）
              poll(500ms) ─▶ handle(job)
                              ├─ channelProvider.stream().filter(available)
                              │    ├─ 无可用通道 → record(channel=log, target=无可用出站通道, status=1) 返回
                              │    └─ 有可用通道 → 逐通道 dispatchWithRetry
                              │                       最多 2 次尝试（首发 + 重试 1 次）
                              │                       record(channel, target=batch×目标数, status=1|2, retry=尝试-1)
                              └─ 每条记录一行 sys_notify_outbound
```

关键取舍：

- **有界队列，满即丢**。出站留痕是尽力而为，宁可丢一条投递记录，也不能让发通知的业务事务被拖住或回滚。
- **单线程顺序投递**。同一条消息的多通道按注册顺序串行，够用且不需要并发控制；SMTP 慢只会慢自己。
- **投递线程在事务外**。`afterCommit` 保证只给已成立的消息投递；进程在提交后、入队前被杀，出站记录就没了，
  站内消息仍在（这是当前无持久化队列的必然代价）。
- **终态一律落库，包括兜底**。没有可用通道时仍写一行 `channel = log`，保证“每条消息都有投递痕迹”，
  排查时能区分“没配通道”和“通道配了但没轮到”。
- **`retry_count` 记实际重试次数**，一次成功即 0；不是尝试次数。
- **停机排空**：`destroy` 置停后 `join(10s)`，循环会把队列余量跑完再退出；超过 10 秒仍未排空则告警并随进程丢弃。
- 审计列的 `create_by` 自然留空——投递线程没有登录人上下文，这是 ignore 表，租户归属靠行内 `tenant_id` 快照。

### 通道 SPI 与可用性自查

`OutboundChannel` 是站内之外的扩展点，三个方法：`channel()`（落 `channel` 列的标识）、
`available()`（默认真，实现自己判配置）、`send()`（失败抛异常）。

**可用性为什么是运行期自查而不是 `@ConditionalOnBean`**：`JavaMailSender` 由 Spring Boot 自动配置提供，
业务组件的装配期判不到它——条件装配的求值顺序不可靠。所以 `MailOutboundChannel` 总是注册为 Bean，
用 `ObjectProvider<JavaMailSender>.getIfAvailable() != null` 在每次投递时自报可用与否；
dispatcher 在 `handle` 里过滤。副作用是通道中途失效（邮件服务宕掉）不会导致消息丢失，只是那一次记为失败。

`available()` 为 false 的通道被跳过，**不写任何记录**：一行“跳过”既不是终态，也没有信息量。

### 多租户口径

| 数据 | 租户处理 | 说明 |
| --- | --- | --- |
| `sys_notify_message` | 拦截器自动注入 | `TenantAuditedEntity`，写前用 `asTenant` 切到收件租户 |
| `sys_notify_recipient` | 拦截器自动注入 | 同上；收件箱查询在本人租户上下文里天然收敛 |
| `sys_notify_outbound` | **ignore 清单** | 投递线程无租户上下文，靠 `DispatchJob.tenantId` 写快照列溯源 |
| 平台公告列表 | `withoutTenant` 窄窗口 | 跨租户检索是平台管理动作，读消息主体与统计都走它 |
| `UserBriefPort.briefs` | 当前租户口径 | 跨租户 ID 静默缺席，不报错 |
| `UserBriefPort.enabledUsersOfTenant` | 实现内部切租户 | 调用方不需要自己构造上下文 |

## 详细设计：按类

### `NotifyApi` / `NotifyApi.NotifySendCommand`

跨模块契约，住在 `api` 包里。`send` 是唯一方法，`NotifySendCommand` 八字段：
`tenantId`（收件人归属租户）、`title`、`content`、`bizType`、`bizId`、`senderId`（系统代发为 null）、
`senderName`（显示名快照）、`recipients`（收件人用户 ID 集）。

契约口径写在 javadoc 上，实现不许擅自改语义：部分收件人已失效则**静默缺席**，只有全员无效才拒绝；
`senderName` 空白时由实现侧写 `系统通知`。命令是不可变 record，收件人展开在调用方完成——
把“谁该收到”的判定留在业务侧，notify 只负责“送到”。

### `NotifyService`

实现 `NotifyApi`，同时是 Controller 的服务面。公开方法按侧分组：

| 方法 | 侧 | 事务 | 要点 |
| --- | --- | --- | --- |
| `send(NotifySendCommand)` | 发送 | `@Transactional` | 校验 → 切租户 → `doSend` |
| `announce(AnnouncementSendRequest)` | 发送 | `@Transactional` | 取启用用户、校验规模，转调 `send` |
| `inbox(NotifyInboxQuery)` | 收件箱 | 读 | 本人谓词 + `bizType` 前置 ID 集 + 批量回查主体 |
| `unreadCount()` | 收件箱 | 读 | 索引直达，无缓存 |
| `detail(Long)` | 收件箱 | 读 | 先过 `requireOwnRecipient` |
| `markRead(Long)` / `markAllRead()` | 收件箱 | 写 | 未读才写，全部已读只动本人未读行 |
| `remove(Long)` | 收件箱 | 写 | 逻辑删收件行，主体保留 |
| `announcements(AnnouncementQuery)` | 平台面 | 读 | `withoutTenant` 分页 + group by 统计扇出人数 |

私有方法 `doSend` / `bizMessageIdsOf` / `messagesOf` / `requireOwnRecipient` / `requireLoginUser` 各承担一步，
没有额外的策略或工厂——发送路径只有一条，抽象在这里没有回报。

常量：`BIZ_ANNOUNCEMENT = "announcement"`（public，平台面列表用它圈定）、`MAX_RECIPIENTS = 5000`、
`UNREAD = 0` / `READ = 1`、`SYSTEM_SENDER = "系统通知"`。

### `OutboundDispatcher`

`@Component` + `DisposableBean`。构造即启动投递线程（daemon，名为 `notify-outbound`），
空队列时按 500ms 轮询——比再引一个调度器便宜，且停机响应足够快。

`DispatchJob` 是队列元素：`messageId`、`tenantId`（租户快照）、`title`、`content`、`targets`。
`record` 在入队时就把收件目标算好，投递线程不再回查用户档案——线程里没有登录上下文，
那时再去取 `UserBriefPort` 会撞上“无租户上下文”。

状态常量只有两个会被写：`STATUS_SUCCESS = 1`、`STATUS_FAILED = 2`；`QUEUE_CAPACITY = 2000`、
`FALLBACK_CHANNEL = "log"`。落库失败（例如库抖动）只 warn——投递记录已经比消息本身次要，
不能因为记不上而让线程退出。

### `OutboundChannel` / `MailOutboundChannel`

SPI 与它的第一个实现。`OutboundMessage` 携带 `messageId/title/content/targets`，
`OutboundTarget` 是 `userId + address`，`address` 按通道语义解释（邮件即邮箱，可为空）。

`MailOutboundChannel.send` 的做法：

1. `getIfAvailable()` 取 `JavaMailSender`，缺席即 `邮件通道未配置`；
2. 目标地址过滤链：非空白 → `trim` → `Validator::isEmail` → `distinct`。脏地址会让整封被 SMTP 拒收，
   重复地址会让一个人收到两封，所以在这里一次性清掉；
3. 全部无效即抛 `收件人没有可用邮箱地址`，由 dispatcher 记 `status = 2` 与错因，不静默当成功；
4. 单封 `MimeMessage` + `MimeMessageHelper(mime, false, "UTF-8")`，正文按纯文本发送（第二个参数 false
   即不解析 HTML，公告正文来自运营录入）；
5. 收件人一律 `addBcc`，同一封里互不可见地址；`from` 取 `spring.mail.username`，默认
   `snail-platform@noreply.local`。

### `NotifyController` / `NotifyPlatformController`

薄壳：参数绑定 + 准入注解 + 转调 + `R.ok` 包装，没有任何业务分支。
`@Operation(summary)` 是端点的说明来源，代码里不重复写注释。
收件箱六个端点的权限分层见[端点与准入边界](#端点与准入边界)。

### 实体与 DTO

三个 PO 全部继承框架基类，字段与列一一对应，含义写在字段注释上：

- `SysNotifyMessage`：`title` / `content` / `bizType` / `bizId` / `senderId` / `senderName`（快照）/ `sendTime`；
- `SysNotifyRecipient`：`messageId` / `userId`（收件箱谓词）/ `nickname`（快照）/ `readStatus` / `readTime`；
- `SysNotifyOutbound`：`messageId` / `channel` / `target`（摘要）/ `status`（1 成功 2 失败）/
  `error` / `retryCount` / `sendTime` / `tenantId`（快照）。

DTO 侧：两个查询 record 实现 `PageQuery`，紧凑构造器把 `pageNum` 缺省为 1、`pageSize` 缺省为 20，
`pageSize` 上限 200；出参 record 的 `of(...)` 是纯字段投影，不含逻辑；
`AnnouncementSendRequest` 只收三要素（`tenantId` / `title` / `content`），扇出与校验都在服务端。

## 存储与索引

建表在 [`V1__init_system_schema.sql`](../../snail-platform-start/src/main/resources/db/migration/V1__init_system_schema.sql)
的通知段落，菜单与权限码种子在
[`V4__seed_builtin_data.sql`](../../snail-platform-start/src/main/resources/db/migration/V4__seed_builtin_data.sql)
（`160` 通知中心 / `220` 公告管理 / `2201` 发送公告）。

| 表 | 租户 | 关键索引 | 说明 |
| --- | --- | --- | --- |
| `sys_notify_message` | 托管 | `KEY idx_nm_biz (tenant_id, biz_type, biz_id)` | 业务侧按标签 + 单据反查消息；`content` 为 `TEXT` |
| `sys_notify_recipient` | 托管 | `KEY idx_nr_inbox (tenant_id, user_id, read_status, recipient_id)`；`KEY idx_nr_message (message_id)` | 前者覆盖收件箱与未读数；后者服务扇出统计与按消息排查 |
| `sys_notify_outbound` | ignore | `KEY idx_no_message (message_id)` | 一消息一通道一行；`target` 截断到 100，`error` 截断到 500 |

三张表都带审计四件套与 `deleted` 逻辑删除；`sys_notify_outbound` 的 `deleted` 不提供删除入口，
它是投递账本，不是业务数据。

**没有缓存**：未读数、收件箱、公告列表都直查数据库，写路径不点清任何缓存键。

## 接口契约

### 响应信封

所有端点返回 `R{code,message,data}`；分页数据在 `data` 里再套一层 `PageResult{rows,total}`。
成功 `code = 0`，业务拒绝为非 0（`400`/`401`/`403`），HTTP 状态仍是 200；
只有认证与授权层面的失败才由安全链直接给 HTTP 401/403。

### 收件箱

`GET /system/notify/page?pageNum=1&pageSize=20&readStatus=0&bizType=announcement`

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "rows": [
      {
        "recipientId": 1024,
        "messageId": 88,
        "title": "系统维护公告",
        "content": "本周六 02:00-04:00 停机维护。",
        "bizType": "announcement",
        "bizId": null,
        "senderName": "王管理",
        "sendTime": "2026-10-29 10:00:00",
        "readStatus": 0,
        "readTime": null
      }
    ],
    "total": 1
  }
}
```

字段口径：`recipientId` 是后续所有动作的坐标（不是 `messageId`）；`readStatus` 为 `0 未读 / 1 已读`；
`bizId` 与 `senderName` 都可能为 `null`，前端要按可空处理。

| 端点 | 出参 |
| --- | --- |
| `GET /system/notify/unread-count` | `data` 为数字，本人未读条数 |
| `GET /system/notify/{recipientId}` | `data` 为单条 `NotifyInboxItemVO`，字段同上 |
| `PUT /system/notify/{recipientId}/read` | `data = null` |
| `PUT /system/notify/read-all` | `data = null` |
| `DELETE /system/notify/{recipientId}` | `data = null` |

### 公告

`GET /platform/notify/page?pageNum=1&pageSize=20&title=维护`

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "rows": [
      {
        "messageId": 88,
        "tenantId": 1,
        "title": "系统维护公告",
        "content": "本周六 02:00-04:00 停机维护。",
        "senderName": "王管理",
        "sendTime": "2026-10-29 10:00:00",
        "recipientCount": 42
      }
    ],
    "total": 1
  }
}
```

`recipientCount` 是该消息的收件行总数（含已读与已移出的行），不是“当前还能看到它的人数”。

`POST /platform/notify/announcement`，请求体三字段：

```json
{ "tenantId": 1, "title": "系统维护公告", "content": "本周六 02:00-04:00 停机维护。" }
```

成功返回 `{"code":0,"message":"ok","data":null}`；此端点带 `@OperLog`，会留一条模块为 `公告管理` 的操作日志。

### 业务错误文案

| 文案 | 触发条件 | 出处 |
| --- | --- | --- |
| `标题与正文不能为空` | 标题或正文 `trim` 后为空 | `NotifyService.send` |
| `收件租户不能为空` | `tenantId` 为 null | `NotifyService.send` |
| `收件人不能为空` | 收件人集去 null/去重后为空 | `NotifyService.send` |
| `单次收件人超过上限 5000,请分批发送` | 收件人 > 5000 | `NotifyService.send` |
| `收件人均不存在或已跨租户` | 快照一个都取不到，消息主体随事务回滚 | `NotifyService.doSend` |
| `通知不存在` | 收件行不存在，或属于他人 | `NotifyService.requireOwnRecipient` / `detail` |
| `未认证` | 线程上下文没有登录人 | `NotifyService.requireLoginUser` |
| `目标租户启用用户数超过 5000,请改用业务接口分批发送` | 公告扇出超限 | `NotifyService.announce` |
| `租户不存在` / `租户已停用` | 公告目标租户校验失败 | `system` 的 `UserBriefService` |

参数校验（`@NotBlank` / `@Size` / `@Min` / `@Max`）失败时，注解上的 `message` 就是前端看到的文案，
走与业务拒绝相同的信封；文案清单见 `AnnouncementSendRequest` 与两个查询 DTO。

## 使用方式

### 构建与测试

```bash
# 编译（含依赖的 framework 与 system 模块）
mvn -o -pl snail-platform-function/snail-function-notify -am -DskipTests compile

# 端到端回归（需本机 MySQL + Valkey，见下）
mvn -o -pl snail-platform-start -am -DSNAIL_DB_USERNAME=root -Dtest=NotifyFlowTest test
```

### 业务模块接入（Java）

只 import `api` 包，收件人展开自己负责：

```java
@Service
public class BillingNotifyService {

    private final NotifyApi notifyApi;

    public BillingNotifyService(NotifyApi notifyApi) {
        this.notifyApi = notifyApi;
    }

    public void notifyOverdue(Long tenantId, List<Long> userIds, String billNo) {
        notifyApi.send(new NotifyApi.NotifySendCommand(
                tenantId,                       // 收件人归属租户
                "账单逾期提醒",                   // 标题
                "单号 " + billNo + " 已逾期,请尽快处理。",
                "billing",                      // 业务域标签
                null,                           // 关联业务 ID
                null,                           // 发送人 ID：系统代发
                null,                           // 发送人名：留空即写“系统通知”
                userIds));
    }
}
```

在事务里调用是安全的：站内落库参与当前事务，出站投递排在提交之后。
若动作已提交而通知失败，只有日志，不会回滚业务——参考
`WorkflowNotifyService`（`snail-function-workflow`），它在登记回调前就把角色成员展开成收件人 ID，
因为 `afterCommit` 里线程上下文正在清理。

`bizType` 是与消费方约定出来的字符串（`announcement` / `workflow` / …）：收件箱用它筛选，
业务侧可用 `bizId` + `idx_nm_biz` 反查自己发过的消息。

### 手工验证（curl）

```bash
TOKEN=<登录返回的 token>

# 本人未读数
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/system/notify/unread-count"

# 收件箱前 20 条未读
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8080/system/notify/page?pageNum=1&pageSize=20&readStatus=0"

# 平台公告：向租户 1 全员发送（需 platform:notify:send）
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"tenantId":1,"title":"维护公告","content":"本周六 02:00 停机。"}' \
  "http://localhost:8080/platform/notify/announcement"
```

发完后查投递痕迹：

```sql
SELECT channel, target, status, retry_count, error
  FROM sys_notify_outbound WHERE message_id = <刚返回的消息 ID>;
```

未配置邮件时，这里应该有一行 `channel = log`、`target = 无可用出站通道`、`status = 1`。

### 前端接法

| 页面 | 文件 | 用的端点 |
| --- | --- | --- |
| API 封装 | `snail-platform-frontend-admin/src/api/notify.ts` | 全部八个 |
| 收件箱 | `src/views/system/notify/index.vue` | `pageInbox` / `markRead` / `markAllRead` / `removeInboxItem` / `fetchUnreadCount` |
| 公告管理 | `src/views/platform/notify/index.vue` | `pageAnnouncements` / `sendAnnouncement` |
| 顶栏铃铛 | `src/layouts/DefaultLayout.vue` | `fetchUnreadCount`，30 秒轮询 |

两处与契约直接相关的实现约定：

- 铃铛角标 `max = 99`，且只在持有 `system:notify:list` 时才起轮询——没有入口就不必替全站产生读放大；
- 收件箱“看详情即视为已读”直接用列表行的数据展开抽屉，不再调 `GET /system/notify/{id}`，
  所以详情端点是给直达链接与其他消费方留的，不是列表的必经路径。

### 启用邮件通道

仓库默认**不带** `spring.mail` 配置，此时 `JavaMailSender` 不装配，`MailOutboundChannel.available()` 为 false，
所有投递走 `log` 兜底行。要真实发信，在 `snail-platform-start` 的配置文件里补：

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 465
    username: notify@example.com
    password: <口令>
    properties:
      mail.smtp.ssl.enable: true
```

`from` 取 `spring.mail.username`（未配置时用默认占位地址 `snail-platform@noreply.local`，
那意味着没有 `JavaMailSender`，通道根本不会启用）。

### 新增一条出站通道

1. 实现 `OutboundChannel`：`channel()` 返回落库标识（会进 `sys_notify_outbound.channel`，列宽 20）；
2. 注册成 Spring Bean——`OutboundDispatcher` 通过 `ObjectProvider<OutboundChannel>` 自动看见它，无需改投递器；
3. **必须**重写 `available()`，缺配置时返回 false，而不是在 `send()` 里抛异常（否则每次投递都留一行失败）；
4. 收件地址从 `OutboundTarget.address()` 取，自行过滤空白与格式非法的地址，并在“全部不可用”时抛异常留下因；
5. 想清隐私口径：站内收件人列表对通道实现是明文的，不要把地址写进日志或对外响应；
6. 用 `NotifyFlowTest` 的口径验证：发一条通知 → 断言 `sys_notify_outbound` 出现该通道的新终态行。

## 验证手段

端到端回归是 [`NotifyFlowTest`](../../snail-platform-start/src/test/java/com/snail/platform/system/NotifyFlowTest.java)
（`snail-platform-start` 测试目录下）。它需要本机 MySQL 与 Valkey，靠环境变量 `SNAIL_DB_USERNAME` 开关，
未设置时整个测试类跳过，不会在纯净 CI 上假装通过。

它覆盖的口径，与本 README 一一对应：

| 断言 | 对应的规则 |
| --- | --- |
| 新用户收件箱为空；公告扇出后管理员与用户双方都看到未读 | 收件租户切换 + 启用用户展开 + 一人一行 |
| 用户读管理员的收件行得到非 0 业务码 | `通知不存在` 的隐私口径（存在性即隐私） |
| 标已读后未读数精确回落，详情不再含未读标记 | 已读闭环与 `read_status` 单一事实源 |
| `全部已读` 后未读数为 0；移出后列表不再出现该条 | `markAllRead` 只动本人未读、移除只动一行 |
| 平台公告列表能查到本次公告 | `biz_type = announcement` 与跨租户检索 |
| 发消息后 5 秒内出现 `channel = log`、`status = 1` 的投递记录 | 出站兜底与 `afterCommit` 入队 |
| 目标租户不存在时被拒绝 | `enabledUsersOfTenant` 的租户校验 |

架构约束由 [`ArchitectureRulesTest`](../../snail-platform-start/src/test/java/com/snail/platform/architecture/ArchitectureRulesTest.java)
执行：`notify` 在 `API_PACKAGE_ENFORCED` 名单里，任何绕过 `api` 包的跨模块引用都会红。

## 已知边界与注意事项

以下都是当前实现的真实边界，不是待修的笔误，动它们之前请先确认取舍：

1. **兜底行的 `status = 1` 表示“已按兜底处理”，不是“已送达”**。没有可用通道时写的是
   `channel = log / target = 无可用出站通道 / status = 1`；若把出站账本做成看板，这一类应单独统计，
   否则会误报成功率。
2. **通道投递失败不回落到 `log`**。`log` 兜底只在“一个可用通道都没有”时触发；邮件已启用但发送失败
   （例如收件人无有效邮箱）时，只有 `channel = mail / status = 2` 与错因，没有额外的兜底行。
3. **停用用户仍会收到站内通知**。`UserBriefPort.briefs` 不做状态过滤，所以任何走 `NotifyApi.send` 的
   调用方如果直接传入停用的用户 ID，收件行照写。公告与流程待办这两条路径传的是“启用用户”，不受影响；
   要严格按状态过滤，得在调用方展开收件人。
4. **`target` 记的是目标数，不是实际发出的地址数**。mail 通道在实现内部才做无效地址过滤，
   摘要列看不到这一步，排查时以 `error` 与邮件服务日志为准。
5. **单封邮件承载全部 BCC，不分批**。上限 5000 个收件人时，一次 `addBcc` 5000 个地址可能被服务商限流或拒收；
   本期没有分批逻辑，大群发请走业务接口分批（`send` 的提示文案就是这个意思）。
6. **`bizType` 过滤会把该标签下的全部消息 ID 载入内存再拼 `IN`**。收件箱按标签筛选时，如果某标签下消息量很大，
   `IN` 列表会随之膨胀；当前数据量下不构成问题，但改成子查询或给收件行冗余标签列才是终态。
7. **列表映射假定消息主体存在**。当前没有删除消息主体的入口，所以 `inbox` 的回查不做缺失兜底；
   一旦将来加了“删除公告”，外部删了主体而收件行还在，列表映射会踩空。
8. **公告列表返回完整正文**。`content` 是 `TEXT`，平台面一页最多 200 条，列表页大正文会明显放大响应体；
   扇出人数统计也是跨租户 `group by`，公告量大时成本随页大小上升。
9. **出站是尽力而为**：队列满即丢、无死信、无补偿；进程在事务提交后、投递前被杀，出站记录缺失而站内消息仍在。
   要做可靠投递，需要持久化队列表或换消息中间件，那是独立的一笔改造。
10. **`sys_notify_outbound.status` 的 `0` 只是 DDL 默认值**，代码从不写 0（终态只写 1/2）；
    看到 0 说明这行不是当前投递器写的。
11. **`NotifyApi.send` 不经 bean validation**：契约只校验“非空”和收件人规模，标题超过列宽 100 会由数据库拒绝，
    表现为系统级错误而不是友好文案。业务侧长标题请自行截断，或走公告 DTO 的校验路径。
12. **本模块只有端到端测试，没有单元测试**：`OutboundDispatcher`（队满丢弃、重试、停机排空）与
    `MailOutboundChannel`（地址过滤与全无效抛错）都依赖真实数据库与真实上下文，边界行为暂无独立用例覆盖。

## License

[MIT](../../../LICENSE)
