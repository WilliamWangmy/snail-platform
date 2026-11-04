package com.snail.platform.function.notify.service;

import com.snail.platform.function.external.api.MailPort;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 职责:邮件出站通道——出站投递语义(可用性/消息组装)住这里,SMTP 协议细节全部下沉到
 * snail-function-external 的 SmtpMailClient,本类只做 OutboundChannel 到 MailPort 的转调。
 * 上游:OutboundChannel SPI;下游:external.api 的 MailPort(跨模块只准经 api 包,ArchUnit 执法)。
 */
@Component
public class MailOutboundChannel implements OutboundChannel {

    /** 邮件发送端口,厂商细节在 external 网关 */
    private final MailPort mailPort;

    public MailOutboundChannel(MailPort mailPort) {
        this.mailPort = mailPort;
    }

    @Override
    public String channel() {
        return "mail";
    }

    @Override
    public boolean available() {
        return mailPort.available();
    }

    @Override
    public void send(OutboundMessage message) throws Exception {
        // 地址过滤(空白/非法/去重)与 BCC 细节在 SmtpMailClient;全无效时它抛 ExternalCallException,
        // 由 dispatcher 记 failed 与错因,不静默当成功——口径与一期完全一致
        List<String> addresses = message.targets().stream()
                .map(OutboundTarget::address)
                .toList();
        mailPort.send(new MailPort.MailMessage(
                message.title(), message.content(), addresses, "notify", "outbound", message.messageId()));
    }
}
