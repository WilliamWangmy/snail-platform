package com.snail.platform.function.monitor.datasource;

import com.snail.platform.function.monitor.log.dto.AppLogDetailQuery;
import com.snail.platform.function.monitor.log.dto.AppLogDetailVO;
import com.snail.platform.function.monitor.log.dto.AppLogPageQuery;
import com.snail.platform.function.monitor.log.dto.AppLogPageVO;
import com.snail.platform.function.monitor.log.dto.LogFilesVO;
import com.snail.platform.function.monitor.log.dto.TailQuery;
import java.util.List;

/**
 * 职责:应用日志源适配接口——file 与 loki 两种实现的统一签名,AppLogService 按
 * logs.source 取 Bean(Bean 图恒定,DD-01);日志检索无 SQL,无注入面。
 * 上游:AppLogService;下游:FileLogSource(本地文件流式)、LokiLogSource(Step 7,HTTP query_range)。
 * 口径:实现永不静默回退另一来源(避免混源误导);IO 失败转 400 业务拒绝。
 */
public interface LogSource {

    /** 文件枚举(file)/固定单条 selector(loki) */
    LogFilesVO files();

    /** 时间窗检索:窗口内最后 limit 条,时间倒序 */
    AppLogPageVO page(AppLogPageQuery query);

    /** 增量:since 之后的行,正序,file 只读活跃文件末尾(DD-26) */
    List<com.snail.platform.function.monitor.log.LogLineParser.LogLine> tail(TailQuery query);

    /** 行上下文:target + before + after(已解析结构);loki 直接 400 */
    AppLogDetailVO detail(AppLogDetailQuery query);

    /** 来源名:file / loki */
    String name();
}
