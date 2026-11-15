package com.snail.platform.function.monitor.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责:监控模块类型化配置(snail.monitor.*)——总闸、采样、外部源、日志源、慢 SQL、服务端探针、
 * Arthas、诊断与清理的全部开关与预算,唯一绑定面;照 SnailExternalProperties 的嵌套静态类写法。
 * 上游:config/monitor.yml(结构显式化,凭据走环境变量占位);下游:MonitorAutoConfiguration 装配
 * 与 metric/log/db/datasource/diag/arthas/job 各子域。
 * 口径:enabled=false 是总闸——HTTP 侧由 MonitorGateFilter 关门,调度侧由各 @Scheduled 首行短路,
 * Bean 图恒定不随配置变化(DD-01);写值不热更,重启生效。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "snail.monitor")
public class MonitorProperties {

    /** 总闸:false 时端点 400、调度空转 */
    private boolean enabled = true;

    /** 采样 tick 间隔(秒),同时驱动评估与数据源状态刷新 */
    private long sampleIntervalSeconds = 60;

    /** 指标源:源选择与外部源预算/映射 */
    private final Metrics metrics = new Metrics();

    /** 日志源:file/loki 选择与检索预算 */
    private final Logs logs = new Logs();

    /** 数据库监控:慢 SQL 采集与服务端状态探针 */
    private final Db db = new Db();

    /** Arthas 在线诊断:tunnel 地址/凭据/缓存 */
    private final Arthas arthas = new Arthas();

    /** 外链:Grafana 面板地址,空则不出现入口 */
    private final Links links = new Links();

    /** 一键体检:agent 探针地址与超时/限频预算 */
    private final Diag diag = new Diag();

    /** 清理任务:cron 与各表保留期、分批参数(DD-19) */
    private final Cleanup cleanup = new Cleanup();

    @Getter
    @Setter
    public static class Metrics {

        /** 指标源:builtin / prometheus */
        private String source = "builtin";

        /** 外部源(prometheus)参数:baseUrl 空即不启用 */
        private final Prometheus prometheus = new Prometheus();
    }

    @Getter
    @Setter
    public static class Prometheus {

        /** Prometheus 地址,空即不启用外部源 */
        private String baseUrl = "";

        /** Basic auth 用户名,未配置不携带 */
        private String username = "";

        /** Basic auth 口令,仅服务端使用,不回吐 */
        private String password = "";

        /** 单键查询超时(ms) */
        private long timeoutMs = 3000;

        /** 单 tick 外部查询总预算(ms)(DD-06) */
        private long totalTimeoutMs = 5000;

        /** MetricKey → PromQL 映射;未映射键回退 builtin */
        private Map<String, String> queries = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Logs {

        /** 日志源:file / loki */
        private String source = "file";

        /** 本地文件检索参数 */
        private final File file = new File();

        /** 单次检索扫描行上限 */
        private long scanMaxLines = 2_000_000;

        /** 单次检索耗时上限(ms) */
        private long scanTimeoutMs = 3000;

        /** tail 只读活跃文件末尾字节数(DD-26) */
        private long tailMaxBytes = 2_097_152;

        /** Loki 适配参数 */
        private final Loki loki = new Loki();
    }

    @Getter
    @Setter
    public static class File {

        /** 日志目录,相对启动工作目录 */
        private String dir = "logs";

        /** 活跃文件名 */
        private String activeName = "snail-platform.log";
    }

    @Getter
    @Setter
    public static class Loki {

        /** Loki 地址,空即不可用(状态端点 unavailable) */
        private String baseUrl = "";

        /** 查询超时(ms) */
        private long timeoutMs = 5000;

        /** LogQL selector 标签约定 */
        private String selector = "{job=\"snail-platform\"}";
    }

    @Getter
    @Setter
    public static class Db {

        /** 慢 SQL 采集 */
        private final SlowSql slowSql = new SlowSql();

        /** MySQL 服务端状态探针 */
        private final Server server = new Server();
    }

    @Getter
    @Setter
    public static class SlowSql {

        /** 采集开关:false = 摘除拦截器口径(停采集,回滚开关) */
        private boolean enabled = true;

        /** 慢 SQL 阈值(ms),≥ 计入摘要 */
        private long thresholdMs = 1000;

        /** 摘要上限,超出淘汰最久未命中 */
        private int maxDigests = 500;

        /** 最近慢 SQL 事件条数(内存,不落库) */
        private int recentSize = 100;

        /** 归一化后 SQL 截断长度 */
        private int maxSqlLength = 500;
    }

    @Getter
    @Setter
    public static class Server {

        /** 服务端状态探针开关 */
        private boolean enabled = true;

        /** 探针查询超时(ms),失败置 available=false */
        private long timeoutMs = 1000;
    }

    @Getter
    @Setter
    public static class Arthas {

        /** 开关:false 时诊断页不渲染 Arthas tab */
        private boolean enabled = false;

        /** tunnel web 地址,空即不可达 */
        private String baseUrl = "";

        /** 浏览器直连 tunnel 的 agent WS 端口 */
        private int wsPort = 7777;

        /** actuator basic auth 用户名 */
        private String username = "arthas";

        /** actuator basic auth 口令,仅服务端使用,不回吐 */
        private String password = "";

        /** 探针超时(ms) */
        private long timeoutMs = 2000;

        /** 状态缓存秒数,由采样 tick 驱动刷新 */
        private long cacheSeconds = 30;
    }

    @Getter
    @Setter
    public static class Links {

        /** Grafana 外链,空则不出现入口 */
        private String grafanaUrl = "";
    }

    @Getter
    @Setter
    public static class Diag {

        /** agent 健康探针地址,空则该检查项 skip */
        private String agentHealthUrl = "";

        /** 单项超时(ms) */
        private long itemTimeoutMs = 2000;

        /** 12 项总预算(ms) */
        private long totalTimeoutMs = 10_000;

        /** 同用户触发限频(秒) */
        private long rateLimitSeconds = 10;
    }

    @Getter
    @Setter
    public static class Cleanup {

        /** 清理 cron(默认每日 03:30) */
        private String cron = "0 30 3 * * ?";

        /** 指标保留天数 */
        private int metricRetentionDays = 7;

        /** 告警保留天数(只删 resolved) */
        private int alertRetentionDays = 90;

        /** 诊断报告保留天数 */
        private int reportRetentionDays = 30;

        /** 单批删除行数 */
        private int batchSize = 1000;

        /** 单表单次清理最大批数 */
        private int maxBatches = 200;
    }
}
