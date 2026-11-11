package com.snail.platform.function.notify;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责:通知中心开关与模板的配置单点——SSE 实时推送总开关、短信出站模板编码。
 * 此前散落在 SseOutboundChannel/NotifyPushController/SmsOutboundChannel 的 @Value,
 * 现收口为类型化配置,同一开关值天然同源(SSE 通道与订阅端点读同一份)。
 * 上游:application*.yml 的 snail.notify 段;下游:三个消费方与投递器。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "snail.notify")
public class SnailNotifyProperties {

    /** 站内消息实时推送(SSE)配置 */
    private final Push push = new Push();

    /** 短信出站配置 */
    private final Sms sms = new Sms();

    /** 实时推送 */
    @Getter
    @Setter
    public static class Push {

        /** 总开关:关闭即退化为 30s 轮询,订阅端点按"未开启"拒绝,出站投递跳过 */
        private boolean enabled = true;
    }

    /** 短信出站 */
    @Getter
    @Setter
    public static class Sms {

        /** 模板编码(阿里云报备制):自由文案不进契约 */
        private final Templates templates = new Templates();
    }

    /** 报备通过的模板编码;缺省空 = 短信通道不可用,投递器跳过 */
    @Getter
    @Setter
    public static class Templates {

        /** 流程待办通用短信模板编码 */
        private String todo;
    }
}
