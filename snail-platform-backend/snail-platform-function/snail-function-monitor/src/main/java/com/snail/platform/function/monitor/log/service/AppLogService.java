package com.snail.platform.function.monitor.log.service;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.datasource.LogSource;
import com.snail.platform.function.monitor.log.LogLineParser;
import com.snail.platform.function.monitor.log.dto.AppLogDetailQuery;
import com.snail.platform.function.monitor.log.dto.AppLogDetailVO;
import com.snail.platform.function.monitor.log.dto.AppLogPageQuery;
import com.snail.platform.function.monitor.log.dto.AppLogPageVO;
import com.snail.platform.function.monitor.log.dto.LogFilesVO;
import com.snail.platform.function.monitor.log.dto.LogLineVO;
import com.snail.platform.function.monitor.log.dto.TailQuery;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 职责:应用日志检索服务——按 logs.source 取 LogSource Bean(Bean 图恒定,DD-01),
 * 时间窗/limit 校验在 service 首行;source 回显实际模式。
 * 上游:AppLogController;下游:FileLogSource / LokiLogSource。
 * 口径:时间跨度 ≤24h;detail 仅 file 模式(loki 直接 400「当前日志源不支持行上下文」);
 * 不做来源静默回退——外部源失败 400 透传原因,避免混源误导。
 */
@Service
public class AppLogService {

    private final MonitorProperties properties;
    private final Map<String, LogSource> sources;

    public AppLogService(MonitorProperties properties, Map<String, LogSource> sources) {
        this.properties = properties;
        this.sources = sources;
    }

    /** 当前来源:按 name() 匹配——注入 Map 的 key 是 Spring Bean 名,与来源名(file/loki)是两回事 */
    private LogSource source() {
        String configured = properties.getLogs().getSource();
        LogSource source = sources.values().stream()
                .filter(s -> configured.equals(s.name()))
                .findFirst().orElse(null);
        if (source == null) {
            throw BizException.badRequest("日志源配置非法: " + configured);
        }
        return source;
    }

    /** 文件枚举 / selector 固定单条 */
    public LogFilesVO files() {
        return source().files();
    }

    /** 时间窗检索 */
    public AppLogPageVO page(AppLogPageQuery query) {
        if (query.beginTime().isAfter(query.endTime())) {
            throw BizException.badRequest("开始时间不能晚于结束时间");
        }
        if (ChronoUnit.HOURS.between(query.beginTime(), query.endTime()) > 24) {
            throw BizException.badRequest("时间跨度不能超过 24 小时");
        }
        return source().page(query);
    }

    /** 增量 tail */
    public List<LogLineVO> tail(TailQuery query) {
        return source().tail(query).stream().map(LogLineVO::from).toList();
    }

    /** 行上下文;loki 模式直接 400 */
    public AppLogDetailVO detail(AppLogDetailQuery query) {
        if (!"file".equals(properties.getLogs().getSource())) {
            throw BizException.badRequest("当前日志源不支持行上下文");
        }
        return source().detail(query);
    }
}
