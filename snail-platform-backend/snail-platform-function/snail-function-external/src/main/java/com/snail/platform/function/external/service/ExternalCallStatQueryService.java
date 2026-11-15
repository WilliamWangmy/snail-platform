package com.snail.platform.function.external.service;

import com.snail.platform.function.external.api.ExternalCallStatPort;
import com.snail.platform.function.external.mapper.SysExternalCallLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 职责:ExternalCallStatPort 的 external 实现——按厂商聚合调用失败分布,只读 sys_external_call_log。
 * 上游:ExternalCallStatPort 契约(monitor 诊断 external.deps 消费);下游:SysExternalCallLogMapper 注解 SQL。
 * 口径:平台全量(本表在租户豁免清单,行内 tenant_id 快照不参与过滤);失败=status=1;
 * 窗口内无调用的厂商缺席返回,阈值判定由消费方完成。
 */
@Service
public class ExternalCallStatQueryService implements ExternalCallStatPort {

    /** 调用流水读写 */
    private final SysExternalCallLogMapper externalCallLogMapper;

    public ExternalCallStatQueryService(SysExternalCallLogMapper externalCallLogMapper) {
        this.externalCallLogMapper = externalCallLogMapper;
    }

    @Override
    public List<ProviderFailure> failureRateSince(LocalDateTime since) {
        return externalCallLogMapper.selectVendorStatsSince(since).stream()
                .map(row -> new ProviderFailure(row.getProvider(),
                        row.getTotal() == null ? 0L : row.getTotal(),
                        row.getFail() == null ? 0L : row.getFail()))
                .toList();
    }
}
