package com.snail.platform.function.monitor.diag.checks;

import com.snail.platform.function.monitor.config.MonitorProperties;
import com.snail.platform.function.monitor.diag.DiagCheck;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * agent.health:GET agent 探针地址;URL 未配置 skip;延迟 >1s warn、非 200/异常 fail。
 */
@Component
public class AgentHealthCheck implements DiagCheck {

    private final MonitorProperties properties;

    public AgentHealthCheck(MonitorProperties properties) {
        this.properties = properties;
    }

    @Override
    public String key() {
        return "agent.health";
    }

    @Override
    public String name() {
        return "采集 Agent";
    }

    @Override
    public Item run() {
        String url = properties.getDiag().getAgentHealthUrl();
        if (url == null || url.isBlank()) {
            return Item.skip(key(), name(), "未配置 agent 健康地址",
                    "在 monitor.yml 配置 snail.monitor.diag.agent-health-url");
        }
        long start = System.nanoTime();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2000)).build();
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofMillis(2000)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (response.statusCode() != 200) {
                return Item.fail(key(), name(), ms, "HTTP " + response.statusCode() + "(非 200)",
                        "检查 agent 进程状态与探针地址");
            }
            if (ms > 1000) {
                return Item.warn(key(), name(), ms, "探测耗时 " + ms + "ms(>1s)", "检查 agent 所在网络延迟");
            }
            return Item.pass(key(), name(), ms, "agent 可达(" + ms + "ms)", null);
        } catch (Exception e) {
            return Item.fail(key(), name(), (System.nanoTime() - start) / 1_000_000,
                    "探测失败: " + e.getMessage(), "检查 agent 进程与地址配置");
        }
    }
}
