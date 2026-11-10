# snail-function-external

第三方接口网关：平台**所有**第三方调用的唯一出口——厂商适配、密钥配置、调用留痕一处管完。
邮件（SMTP）、短信（阿里云，M2）、IP 归属地（高德，M3）都在这里，业务模块只面对厂商无关的端口。

它只负责“把一次第三方调用做完并记下一行流水”；调不调、什么时候调、失败了业务怎么办，归调用方。

- 所属工程：[`snail-platform-backend`](../../pom.xml)（Maven 三层：framework / function / start）
- 基础包名：`com.snail.platform.function.external`
- 版本：`0.0.1` ｜ License：[MIT](../../../LICENSE)
- 详细设计：[`docs/design/external-gateway.md`](../../../docs/design/external-gateway.md)

## 目录

- [这个模块负责什么](#这个模块负责什么)
- [在平台中的位置](#在平台中的位置)
- [包结构](#包结构)
- [整体设计](#整体设计)
  - [端口与失败语义分档](#端口与失败语义分档)
  - [一次调用的留痕链](#一次调用的留痕链)
  - [异步落账模型](#异步落账模型)
  - [脱敏口径：两层叠加](#脱敏口径两层叠加)
  - [多租户口径](#多租户口径)
  - [“所有第三方必须过网关”的执法](#所有第三方必须过网关的执法)
- [详细设计：按类](#详细设计按类)
- [存储与索引](#存储与索引)
- [接口契约](#接口契约)
- [使用方式](#使用方式)
- [验证手段](#验证手段)
- [已知边界与注意事项](#已知边界与注意事项)

## 这个模块负责什么

**负责**：

- 三个厂商无关端口：`MailPort`（发邮件）、`SmsPort`（发模板短信）、`IpGeoPort`（IP 归属地），全部住在 `api` 包，是消费方唯一准 import 的地方；
- 厂商适配器：`SmtpMailClient`（单封 MimeMessage + 全 BCC + 地址过滤）已落地，阿里云短信与高德定位按同一形态接入；
- 调用留痕：每次调用一行 `sys_external_call_log`——厂商、动作、调用方、请求/响应快照、耗时、终态、错因、trace_id、租户/操作人快照；
- 统一横切：计时、异常归一（任意厂商异常 → `ExternalCallException`）、快照脱敏截断、异步落账，全部收口在模板方法里，适配器只写协议细节；
- 流水查询端点与菜单（`platform:external:log`），供平台侧排查“第三方到底通没通”。

**不负责**：

| 事情 | 归谁 |
| --- | --- |
| 调用失败后的重试 | 调用方（notify 的 `OutboundDispatcher` 已有首发+重试 1 次的现成实现）——网关内不做重试，避免双重重试 |
| 收件地址/手机号从哪来、该发给谁 | 调用方业务模块 |
| 短信签名/模板的报备与审核 | 运营在服务商控制台完成，代码只持模板编码 |
| 密钥（AK/SK/SMTP 口令）的存储 | `snail-platform-start` 配置文件 + 环境变量注入，本模块只读配置，不落库不入日志 |
| 验证码短信的频控/防刷/有效期 | `snail-function-auth` 的安全流程——验证码不是通知，不挂在本模块端口上 |
| JWT 校验、`@PreAuthorize` 判定 | `snail-framework-security` |
| 租户行级过滤 SQL、分页装配 | `snail-framework-db` |
| 熔断限流、storage(OSS) 纳管、trace 全链路 | 后置池，见设计文档 §11 |

本模块不提供 `@Configuration` 与启动类：`SmtpMailClient`、`ExternalCallLedger`、`ExternalCallLogService`
都是 `@Component`/`@Service`，由 `snail-platform-start` 的组件扫描装配；模块自身只是一个被依赖的 jar。

## 在平台中的位置

```
业务模块(notify / 将来的 auth、system…)          平台管理员
   │ MailPort.send(MailMessage)                   │ GET /platform/external/call-log
   │ SmsPort.send(SmsCommand)   (M2)              ▼
   │ IpGeoPort.locate(ip)      (M3)   ExternalCallController ─▶ ExternalCallLogService
   ▼        ← 跨模块只准 import external.api 包              │
AbstractExternalClient.invoke(模板方法)                     ▼
   ├─ 计时 / 异常归一 / 快照脱敏截断                sys_external_call_log（只增账本）
   ├─ 调用线程组装流水行(上下文快照现取)
   └─▶ ExternalCallLedger ──▶ 有界队列(2000) ─▶ 落账线程 ─▶ SysExternalCallLogMapper
   │
   └─▶ doInvoke:厂商适配器
        ├─ SmtpMailClient ──▶ JavaMailSender (SMTP 直连,非 HTTP)
        ├─ AliyunSmsClient ──▶ 服务商 HTTPS API (M2)
        └─ AmapIpClient   ──▶ 高德 v3/ip (M3)
```

依赖形态是本模块的核心取舍：

- **零依赖其他 function 模块**，只依赖 framework 四件（core/db/log/security）——所有模块都可能成为消费方，
  依赖图天然无环；
- **入边 `业务模块 → external`**：只准 import `com.snail.platform.function.external.api`，由
  `ArchitectureRulesTest` 的 `API_PACKAGE_ENFORCED`（含 `EXTERNAL`）机械执法；
- **第三方 SDK 依赖只准住本模块**：`org.springframework.mail.` / `jakarta.mail.` / `com.aliyun.` /
  `com.tencentcloudapi.` / `com.amap.` 前缀的类，任何 external 之外的类引用即测试红灯（见
  [执法](#所有第三方必须过网关的执法)）。

首个消费方是 notify：`MailOutboundChannel` 已薄壳化为 `OutboundChannel → MailPort` 的纯转调，
SMTP 细节从 notify 整体下沉到这里，`spring-boot-starter-mail` 依赖也随迁（notify 的 pom 不再直接依赖邮件栈）。

## 包结构

```
com.snail.platform.function.external
├── api/
│   ├── MailPort.java               邮件端口 + MailMessage record（厂商无关契约）
│   ├── SmsPort.java                短信端口 + SmsCommand record（M2 实现）
│   ├── IpGeoPort.java              IP 归属地端口 + IpLocation record（M3 实现）
│   └── ExternalCallException.java  失败统一载体（vendor/action 随行）
├── client/
│   ├── AbstractExternalClient.java 模板方法：计时/异常归一/脱敏/落账/上抛
│   ├── ExternalInvocation.java     一次调用的描述（模板方法入参，包私有 record）
│   ├── ExternalCallLedger.java     异步落账单点：队列 + 专用线程 + 停机排空
│   ├── ExternalMasker.java         地址打码单点：邮箱/手机号按位保留
│   └── SmtpMailClient.java         SMTP 厂商适配器，实现 MailPort
├── controller/
│   └── ExternalCallController.java GET /platform/external/call-log（只读单端点）
├── service/
│   └── ExternalCallLogService.java 流水分页查询，写路径零介入
├── entity/
│   └── SysExternalCallLog.java     流水 PO（不继承审计基类，见存储一节）
├── mapper/
│   └── SysExternalCallLogMapper.java    BaseMapper，无自定义 SQL
└── dto/
    ├── ExternalCallLogQuery.java   分页入参（vendor/caller/status/时间区间）
    └── ExternalCallLogVO.java      出参 = 流水行全字段投影（无详情端点，列表行即全行）
```

## 整体设计

### 端口与失败语义分档

三个端口各自把**失败语义写进 javadoc 契约**，实现不许擅自改：

| 端口 | 语义档 | 失败时 | available() 为 false 时 |
| --- | --- | --- | --- |
| `MailPort` | 送达承诺 | 抛 `ExternalCallException`，由调用方决定重试 | 调用方按“通道未配置”降级，不发起调用、不留失败流水 |
| `SmsPort` | 送达承诺 | 同上；服务商限流原样上抛，不自动重试 | 同上 |
| `IpGeoPort` | 装饰信息 | **返回 `Optional.empty()` 静默降级**，消费方（如登录日志）拿不到就写“未知” | 同上，消费方直接写“未知” |

降级策略住端口契约，不藏在网关实现里——消费方看 javadoc 就知道该抛给用户还是吞掉。

`available()` 是运行期自查而不是 `@ConditionalOnBean`：`JavaMailSender` 由 Spring Boot 自动配置提供，
业务组件的装配期判不到它。所以适配器总是注册为 Bean，用 `ObjectProvider` 在每次调用前自报可用与否
（口径与 notify 出站通道一期一致）。

### 一次调用的留痕链

`AbstractExternalClient.invoke(invocation, call)` 是所有厂商调用的唯一通道，顺序固定：

1. **计时起点**（`System.nanoTime`，耗时上限钳到 `Integer.MAX_VALUE` 毫秒）；
2. **请求快照定稿**：客户端在调用线程里已把地址经 `ExternalMasker` 打码，这里再过一遍
   `SensitiveDataMasker` 键级脱敏，按列宽 2000 截断；
3. **执行 `call.get()`**：真正的协议调用，返回值 `String.valueOf` 后作为响应摘要落流水（如 `"bcc×3"`）；
4. **成功**：组装流水行（`status = 0`）入队，原样返回业务结果；
5. **失败**：组装流水行（`status = 1`，error 按列宽 500 截断）入队，异常归一为
   `ExternalCallException.wrap(vendor, action, cause)` 上抛——原因串进文案，原始堆栈保留。

关键点：**流水行在调用线程组装**。`invoke` 与调用方同线程，登录人/租户/traceId 的上下文还在，
快照现取；真正落库在落账线程，只消费行内快照，不再回查任何上下文——所以异步化不需要 ThreadLocal 传递。

### 异步落账模型

`ExternalCallLedger` 是落账单点，形态镜像 notify 的 `OutboundDispatcher` 与 framework-log 的
`OperLogAspect`——“留痕不反压”取舍的第三次复用：

```
调用线程 ─▶ queue.offer(row)         容量 2000；满 → 丢弃 + warn，绝不卡业务线程
               │
               ▼        （daemon 线程 "external-ledger"）
           poll(500ms) ─▶ callLogMapper.insert(row)
                            落库失败只 warn——流水比调用本身次要，线程不能因此退出
停机 ─▶ destroy(): running=false, join(10s) —— 循环把队列余量排空再退出
```

- **队满即丢**：留痕是尽力而为，宁可丢一行流水，也不能让发通知/发短信的业务事务被拖住；
- **单线程顺序写**：流水只增不改，无并发冲突，单线程足够；
- **daemon 线程 + `DisposableBean`**：优雅停机给 10 秒排空，仍排不完的随进程退出丢弃并告警——
  与投递记录同一代价口径（进程在入队后、落库前被杀，那行流水没了，调用本身已完成）。

### 脱敏口径：两层叠加

| 层 | 类 | 策略 | 场景 |
| --- | --- | --- | --- |
| 第一层（按值形态） | `ExternalMasker` | 邮箱 `a***@domain.com`（本地部分只留首字符）、手机号 `138****1234`（前三后四）；位数不足 7 的短串整串打码 | 客户端拼请求快照时对地址逐个打码——排查要看得出是哪个人/哪个号，但不能全裸 |
| 第二层（按 JSON 键名） | `SensitiveDataMasker`（framework-log） | 按键名整体替换值 | 模板方法里对所有快照兜底一遍，防客户端漏打 |

密钥类字段（SMTP 口令、AK/SK）**根本不进快照**——客户端组装 `ExternalInvocation` 时就不携带，
不是“记下来再遮住”。`jsonString()` 把明文转成可嵌入 JSON 的字面量（转义引号/控制字符），
刻意不引 JSON 库：快照是给人排查看的摘要，不是机器契约。

### 多租户口径

| 数据 | 租户处理 | 说明 |
| --- | --- | --- |
| `sys_external_call_log` | **ignore 清单** | 落账在异步线程，无线程租户上下文；靠行内 `tenant_id` 快照溯源，平台级调用（如短信验证码）该列为 NULL |
| 流水查询端点 | 平台面全局 | 流水表里本就有平台级行，租户面不提供入口；查询不挂数据权限开窗 |
| 上下文快照 | 调用线程现取 | `operatorId` / `tenantId` / `traceId`（MDC）在 `invoke` 时定格，此后改名与上下文清理都不回溯 |

流水是账本，**不提供删除/导出**（后置）：给删除字段就是给篡改留门，所以实体不继承审计基类、
无 `deleted` 列、无更新入口；`create_time` 由落账线程显式写（异步落库不过 MyBatis-Plus 自动填充）。

### “所有第三方必须过网关”的执法

不是口头纪律，是 `ArchitectureRulesTest.thirdPartySdkViolations` 的机械红灯：

- 黑名单前缀：`org.springframework.mail.`、`jakarta.mail.`、`com.aliyun.`、`com.tencentcloudapi.`、`com.amap.`；
- 任何类的依赖落到这些前缀且自己不在 `external` 包内 → 立案：`第三方 SDK 依赖越出 external 网关`；
- 规则自检：`ThirdPartySdkRuleTest` 用一个**故意违规**的样例类
  （`architecture.fixture.MailSdkFixture`，external 模块外引用 jakarta.mail）断言规则会立案，
  再用 external 内的合法样例（`function.external.fixture.ExternalMailFixture`）断言豁免不被误报——
  规则写错而静默通过比没有规则更危险，所以不只靠主套件零违规的弱信号。

新引第三方 SDK（比如下一个厂商）时：依赖加进本模块 pom，黑名单追加对应包前缀，一步到位。

## 详细设计：按类

### `MailPort` / `SmsPort` / `IpGeoPort`

跨模块契约，住在 `api` 包里，都是“接口 + 不可变 record 命令”的形态：

- `MailPort.MailMessage`：`subject` / `content`（纯文本）/ `to`（原始地址列表，**过滤与去重在实现侧做**，
  消费方传原始列表即可）/ `caller`（自报标识，落流水）/ `bizType` + `bizId`（业务溯源，可空）；
- `SmsPort.SmsCommand`：`phone`（单发一号一次，群发由调用方展开）/ `templateCode`（与服务商报备一一对应）/
  `params`（模板变量键值）——国内短信本就是“签名+报备模板+参数”形态，端口只收模板编码，自由文案降级是调用方的事；
- `IpGeoPort.IpLocation`：`province` / `city` / `adcode`，字段可能为空（高德对内网 IP 返回不完整）。

`caller` 的约定值：`notify` / `auth` / `system`；空白时落账写 `unknown`，不拒绝调用。

### `AbstractExternalClient`

抽象模板方法基类，适配器只实现 `vendor()`（落流水 `vendor` 列：`smtp-mail` / `aliyun-sms` / `amap`）
与具体的协议调用。常量：`STATUS_SUCCESS = 0` / `STATUS_FAILED = 1`、`SNAPSHOT_MAX = 2000`、`ERROR_MAX = 500`。

静态工具 `truncate`（按列宽截断，null 原样）与 `jsonString`（JSON 字面量转义）供适配器复用；
受检异常（如 jakarta messaging）由适配器自行归一为运行时，模板方法统一按失败留痕后 `wrap` 上抛。

### `SmtpMailClient`

SMTP 厂商适配器，`extends AbstractExternalClient implements MailPort`，vendor 为 `smtp-mail`。
`send` 的做法（从 notify 一期原样下沉，口径不变）：

1. `getIfAvailable()` 取 `JavaMailSender`，缺席即抛 `邮件通道未配置`；
2. 地址过滤链：非空白 → `trim` → `Validator::isEmail` → `distinct`——脏地址会让整封被 SMTP 拒收，
   重复地址会让一个人收到两封；
3. 全部无效即抛 `收件人没有可用邮箱地址`，不静默当成功；
4. 请求快照：地址先打码再拼 JSON 数组，响应摘要记 `bcc×N`（地址数），不记报文；
5. 单封 `MimeMessage` + `MimeMessageHelper(mime, false, "UTF-8")`：纯文本发送（正文可能来自运营录入，
   不按 HTML 解析免标签注入）；收件人一律 `addBcc`，同一封里互不可见；`from` 取 `spring.mail.username`。

### `ExternalCallLedger`

见[异步落账模型](#异步落账模型)。`record` 是唯一写入口，非阻塞 `offer`；队满 warn 丢失。

### `ExternalCallController` / `ExternalCallLogService`

查询面薄壳。分页谓词：`vendor` / `caller` 精确、`status` 精确、`createTime` 闭区间，
空条件不拼进 SQL；按自增 `call_id` 倒序——同毫秒的调用也有确定次序。
没有详情端点：快照已脱敏截断，列表行就是全行（与登录日志同口径）。服务只读，写路径全在落账线程。

### 实体与 DTO

- `SysExternalCallLog`：十四个业务字段 + `create_time`，**不继承审计基类**（日志无修改无假删除）；
  `status` 为 `0 成功 / 1 失败`（注意与 notify 出站的 `1 成功 / 2 失败` 取值相反，两表口径独立，勿混用）；
- `ExternalCallLogQuery`：record 实现 `PageQuery`，紧凑构造器把 `pageNum` 缺省为 1、`pageSize` 缺省为 20
  （上限 200），包装类型接绑定、校验对 null 放行；
- `ExternalCallLogVO.of(...)`：纯字段投影，`request`/`response` 原样透出（已是脱敏截断后的文本）。

## 存储与索引

建表在 [`V4__init_external.sql`](../../snail-platform-start/src/main/resources/db/migration/external/V4__init_external.sql)，
菜单与权限码种子同文件后段
（`222` 第三方调用 / `platform:external:log`，默认租户 admin 平台面授权与 V6 同口径幂等）。

| 表 | 租户 | 关键索引 | 说明 |
| --- | --- | --- | --- |
| `sys_external_call_log` | ignore | `KEY idx_ecl_vendor (vendor, status, call_id)`；`KEY idx_ecl_biz (caller, biz_type, biz_id)` | 一次调用一行，只增不改；前者服务“某厂商的失败率”，后者服务“某业务调了什么” |

列宽即截断规则：`request`/`response` 2000、`error` 500、`vendor` 30、`caller` 30。
清理策略（保留期/归档）未定——短信验证码类调用量大后需要拍板，见设计文档 §11。

## 接口契约

### 响应信封

返回 `R{code,message,data}`；分页数据在 `data` 里再套一层 `PageResult{rows,total}`。
成功 `code = 0`；`@PreAuthorize` 不满足由安全链直接给 HTTP 403。

### 流水查询

`GET /platform/external/call-log?vendor=smtp-mail&status=1&beginTime=2026-11-01T00:00:00&pageNum=1&pageSize=20`
（权限码 `platform:external:log`）

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "rows": [
      {
        "callId": 1042,
        "vendor": "smtp-mail",
        "action": "send-mail",
        "caller": "notify",
        "bizType": "outbound",
        "bizId": 88,
        "request": "{\"subject\":\"系统维护公告\",\"to\":[\"a***@example.com\"]}",
        "response": "bcc×3",
        "status": 0,
        "error": null,
        "costMs": 412,
        "traceId": "c0b9481b49b64110a0f814a167d19095",
        "operatorId": 1,
        "tenantId": 1,
        "createTime": "2026-11-04 13:20:00"
      }
    ],
    "total": 1
  }
}
```

排查姿势：公告发出后用户说没收到邮件 → `caller=notify` + `bizId=<消息ID>` 查一次调用；
`status=1` 看 `error`（地址全无效 / SMTP 拒收 / 通道未配置），`status=0` 说明网关侧已交 SMTP，
继续排查要靠邮件服务商侧日志。

## 使用方式

### 构建与测试

```bash
# 编译（含依赖的 framework 模块）
mvn -o -pl snail-platform-function/snail-function-external -am -DskipTests compile

# 端到端回归（邮件经本网关,需本机 MySQL + Valkey）
mvn -o -pl snail-platform-start -am -DSNAIL_DB_USERNAME=<user> -Dtest=NotifyFlowTest test
```

### 消费方接入（Java）

只 import `api` 包，先问 `available()` 再发：

```java
@Service
public class AlarmMailService {

    private final MailPort mailPort;

    public AlarmMailService(MailPort mailPort) {
        this.mailPort = mailPort;
    }

    public void alarm(String email, String content) {
        if (!mailPort.available()) {
            return;                       // 未配置 spring.mail:静默降级,不留失败流水
        }
        mailPort.send(new MailPort.MailMessage(
                "告警通知",                // 主题
                content,                  // 正文(纯文本)
                List.of(email),           // 原始地址列表,过滤去重由网关做
                "alarm",                  // caller,落流水
                "alarm",                  // bizType
                null));                   // bizId
    }
}
```

失败会抛 `ExternalCallException`（送达承诺语义）：重试与否、重试几次由调用方定；
notify 的出站通道已示范了“首发 + 重试 1 次 + 终态落库”的现成形态，可直接复用 `OutboundDispatcher` 的写法。

### 新增一个厂商适配器

1. 在 `api` 包定义（或复用）端口；若是新厂商，先在 `ArchitectureRulesTest.THIRD_PARTY_SDK_PACKAGES`
   追加其 SDK 包前缀；
2. 在 `client` 包实现 `XxxClient extends AbstractExternalClient implements 端口`：
   `vendor()` 返回落库标识（列宽 30），协议调用包在 `invoke(...)` 里，请求快照先经 `ExternalMasker` 打码；
3. 注册成 `@Component`——可用性自查（`ObjectProvider` / 配置项判空），缺配置返回 false，而不是在调用里抛异常；
4. 密钥只读 `snail-platform-start` 的配置（环境变量注入），不落库、不进快照、不打日志；
5. 失败语义按端口档位执行：送达承诺类抛异常，装饰信息类返回空值；
6. 用 `NotifyFlowTest`（邮件）或新增同款端到端用例验证：调用一次 → 断言 `sys_external_call_log`
   出现该 vendor 的终态行。

### 启用邮件通道

仓库默认**不带** `spring.mail` 配置，此时 `SmtpMailClient.available()` 为 false，notify 出站走 `log` 兜底。
要真实发信，在 `snail-platform-start` 的配置文件里补：

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

### 流水页面（前端）

菜单种子 `222` 已指向 `platform/external/index`（权限码 `platform:external:log`），但前端页面**尚未开发**
——阶段 1 只交付了后端与种子。当前用 curl / SQL 核对流水，页面排期见
[`docs/开发计划_外部网关与站内消息.md`](../../../docs/开发计划_外部网关与站内消息.md)。

## 验证手段

- **端到端回归** [`NotifyFlowTest`](../../snail-platform-start/src/test/java/com/snail/platform/system/NotifyFlowTest.java)：
  发通知 → 出站分发 → `MailOutboundChannel` 转调 `MailPort` → `sys_external_call_log` 留痕 →
  `sys_notify_outbound` 终态，两条账本同时落行；需本机 MySQL 与 Valkey，靠环境变量 `SNAIL_DB_USERNAME` 开关，
  未设置时整个测试类跳过。
- **架构执法** [`ArchitectureRulesTest`](../../snail-platform-start/src/test/java/com/snail/platform/architecture/ArchitectureRulesTest.java)
  + [`ThirdPartySdkRuleTest`](../../snail-platform-start/src/test/java/com/snail/platform/architecture/ThirdPartySdkRuleTest)：
  前者全仓扫描第三方 SDK 越界与 api 包纪律，后者用违规/豁免样例对规则本身做自检。

手工核对（无邮件配置时发一条通知后）：

```sql
SELECT vendor, action, caller, status, response, cost_ms
  FROM sys_external_call_log WHERE caller = 'notify' ORDER BY call_id DESC LIMIT 1;
```

## 已知边界与注意事项

以下都是当前实现的真实边界，不是待修的笔误，动它们之前请先确认取舍：

1. **网关内不做重试**。失败即归一上抛；重试归调用方。在这里再叠一层会与调用方的重试相乘
   （notify 一次投递最多变成 2×N 次真实调用），且重试语义各端口不同，收不进通用模板。
2. **流水不保证不丢**。队满丢弃、落库失败丢弃、停机 10 秒排不净丢弃——与 notify 出站、操作日志同一取舍：
   留痕是尽力而为，绝不反压业务。要做可靠流水需持久化队列表，是独立的一笔改造。
3. **`status` 取值与 `sys_notify_outbound` 相反**。本表 `0 成功 / 1 失败`，notify 出站 `1 成功 / 2 失败`。
   两表口径独立，做联合看板时必须映射，勿直接 UNION。
4. **响应摘要可能是 `null` 或形似错误**。模板方法用 `String.valueOf(call.get())` 记响应，返回 `null` 的
   调用记字符串 `"null"`；这是摘要不是结构化结果，程序化消费请以各端口返回值为准。
5. **流水表无清理机制**。目前只增不改，无保留期与归档策略；短信接入后写入量上升，清理策略是 M2 的前置拍板项。
6. **trace_id 依赖 MDC，无链路体系时为 NULL**。当前项目未全链路接 trace，该列只在有 MDC 值的请求线程里有值。
7. **IP 定位端口的降级是静默的**。消费方拿 `Optional.empty()` 写“未知”，网关侧仍留一行失败流水
   ——排查“归属地怎么全是未知”时先看流水而不是业务表。
8. **单封邮件承载全部 BCC，不分批**。上限内的收件人一次 `addBcc`；大群发被服务商限流的场景，
   分批责任在调用方（与 notify 一期口径一致）。
9. **`caller` 是自报标识，无强制**。消费方传空白落 `unknown`；它用于流水检索，不是鉴权手段。

## License

[MIT](../../../LICENSE)
