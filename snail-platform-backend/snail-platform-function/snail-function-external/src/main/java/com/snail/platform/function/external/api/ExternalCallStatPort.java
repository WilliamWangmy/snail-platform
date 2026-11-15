package com.snail.platform.function.external.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:第三方调用统计只读端口——跨模块取厂商失败率分布的唯一合法出口,跨模块不得再触达 external 内部。
 * 上游:monitor(诊断 external.deps 检查项);下游:external 实现(ExternalCallStatQueryService,读 sys_external_call_log)。
 * 口径:sys_external_call_log 在租户拦截器豁免清单,统计是平台全量口径(行内 tenant_id 快照不参与过滤);
 * status 语义与登录日志相反——0 成功 1 失败,失败数按 status=1 计。
 */
public interface ExternalCallStatPort {

    /**
     * 自指定时刻起(含)按厂商聚合调用总数与失败数;窗口内无调用的厂商缺席返回。
     *
     * @param since 起始时刻(含)
     */
    List<ProviderFailure> failureRateSince(LocalDateTime since);

    /**
     * 单厂商调用统计快照:厂商标识(vendor)、总数、失败数。
     */
    record ProviderFailure(
            /** 厂商标识:smtp-mail/aliyun-sms/amap 等 */
            String provider,
            /** 调用总数 */
            long total,
            /** 失败数(status=1) */
            long fail) {
    }
}
