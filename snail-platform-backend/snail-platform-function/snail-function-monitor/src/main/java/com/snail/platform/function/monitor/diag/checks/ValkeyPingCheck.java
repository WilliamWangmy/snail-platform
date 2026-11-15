package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.diag.DiagCheck;
import com.snail.platform.function.monitor.metric.HealthProbe;
import org.springframework.stereotype.Component;

/**
 * valkey.ping:PING 计时;>100ms warn、异常或 >500ms fail。
 */
@Component
public class ValkeyPingCheck implements DiagCheck {

    private final HealthProbe healthProbe;

    public ValkeyPingCheck(HealthProbe healthProbe) {
        this.healthProbe = healthProbe;
    }

    @Override
    public String key() {
        return "valkey.ping";
    }

    @Override
    public String name() {
        return "Valkey 连通";
    }

    @Override
    public Item run() {
        HealthProbe.ProbeResult result = healthProbe.probeValkey();
        Long ms = result.latencyMs();
        if ("down".equals(result.status())) {
            return Item.fail(key(), name(), ms, "PING 失败" + (ms == null ? "" : "(" + ms + "ms)"),
                    "检查 Valkey 地址/口令/网络与防火墙");
        }
        if (ms != null && ms > 500) {
            return Item.fail(key(), name(), ms, "PING 耗时 " + ms + "ms(>500ms)", "检查 Valkey 负载与网络抖动");
        }
        if (ms != null && ms > 100) {
            return Item.warn(key(), name(), ms, "PING 耗时 " + ms + "ms(>100ms)", "关注 Valkey 慢命令与大键");
        }
        return Item.pass(key(), name(), ms, "PING 耗时 " + ms + "ms", null);
    }
}
