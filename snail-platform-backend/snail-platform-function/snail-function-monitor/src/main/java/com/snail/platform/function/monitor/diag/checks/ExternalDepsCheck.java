package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.external.api.ExternalCallStatPort;
import com.snail.platform.function.monitor.diag.DiagCheck;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * external.deps:近 1 小时第三方调用失败率(按 provider 分组);任 provider >5% warn、>20% fail;
 * 窗口无调用 pass。
 */
@Component
public class ExternalDepsCheck implements DiagCheck {

    private final ExternalCallStatPort externalCallStatPort;

    public ExternalDepsCheck(ExternalCallStatPort externalCallStatPort) {
        this.externalCallStatPort = externalCallStatPort;
    }

    @Override
    public String key() {
        return "external.deps";
    }

    @Override
    public String name() {
        return "第三方依赖";
    }

    @Override
    public Item run() {
        try {
            List<ExternalCallStatPort.ProviderFailure> failures =
                    externalCallStatPort.failureRateSince(LocalDateTime.now().minusHours(1));
            if (failures.isEmpty()) {
                return Item.pass(key(), name(), null, "近 1 小时无第三方调用", null);
            }
            String worst = null;
            double worstRate = 0;
            for (ExternalCallStatPort.ProviderFailure provider : failures) {
                if (provider.total() <= 0) {
                    continue;
                }
                double rate = provider.fail() * 100.0 / provider.total();
                if (rate > worstRate) {
                    worstRate = rate;
                    worst = provider.provider();
                }
            }
            double rounded = Math.round(worstRate * 10) / 10.0;
            if (rounded > 20) {
                return Item.fail(key(), name(), null,
                        "近 1 小时 " + worst + " 失败率 " + rounded + "%(>20%)",
                        "检查该厂商的凭据/额度/网络,确认其服务状态页");
            }
            if (rounded > 5) {
                return Item.warn(key(), name(), null,
                        "近 1 小时 " + worst + " 失败率 " + rounded + "%(>5%)",
                        "关注该厂商调用日志与重试策略");
            }
            return Item.pass(key(), name(), null, "近 1 小时 " + failures.size() + " 个厂商,最高失败率 " + rounded + "%", null);
        } catch (Exception e) {
            return Item.fail(key(), name(), null, "统计失败: " + e.getMessage(), null);
        }
    }
}
