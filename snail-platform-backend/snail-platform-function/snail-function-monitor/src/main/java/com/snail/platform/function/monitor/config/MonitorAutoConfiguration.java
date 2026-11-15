package com.snail.platform.function.monitor.config;

import com.snail.platform.function.monitor.metric.MetricTickEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:monitor 模块装配单点——启用 snail.monitor 配置绑定、开启 @Scheduled 调度、
 * 把总闸 Filter 以 order 0 注册到 /monitor/*。不对 monitor 包做条件扫描:关闸是
 * "Filter + 调度首行布尔短路"两道闸,Bean 图恒定,测试与装配不随配置变化(DD-01)。
 * 上游:config/monitor.yml;下游:MonitorGateFilter(/monitor/** 入口)、
 * MetricSampler/AlertEvaluator/MonitorCleanupJob 的调度运行(启用状态各自短路)。
 * 口径:filter order 0 排在 Spring Security(-100)之后、DispatcherServlet 之前——
 * 未认证请求仍由 JWT 链返回 401,关闸信息只对已认证请求可见。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MonitorProperties.class)
@EnableScheduling
public class MonitorAutoConfiguration {

    /**
     * 总闸注册:只拦 /monitor/*,关闸时 200 + R.fail(400,「监控功能未开放」)。
     * 模块内常驻;enabled=false 时调度方法首行空转,本 Bean 无外部连接,开销可忽略(DD-01)。
     */
    @Bean
    public FilterRegistrationBean<MonitorGateFilter> monitorGateFilterRegistration(
            MonitorProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<MonitorGateFilter> registration =
                new FilterRegistrationBean<>(new MonitorGateFilter(properties, objectMapper));
        registration.addUrlPatterns("/monitor/*");
        registration.setOrder(0);
        registration.setName("monitorGateFilter");
        return registration;
    }

    /**
     * 慢 SQL 拦截器:db.slow-sql.enabled=false 时不注册(MyBatis starter 自动收集 Interceptor[],
     * 不改 MybatisPlusConfig);关闭即摘除,qps 键断点(§5.2.2)。
     */
    @Bean
    @ConditionalOnProperty(prefix = "snail.monitor.db.slow-sql", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public com.snail.platform.function.monitor.db.slow.SlowSqlInterceptor slowSqlInterceptor(
            MonitorProperties properties,
            com.snail.platform.function.monitor.db.slow.SlowSqlCollector collector) {
        return new com.snail.platform.function.monitor.db.slow.SlowSqlInterceptor(collector, properties);
    }
}
