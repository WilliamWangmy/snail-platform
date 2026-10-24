package com.snail.platform.function.notify.service;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 职责:邮件出站通道——单封 MimeMessage、收件人全部走 BCC(互不暴露地址),
 * 可用性取决于容器里是否有 JavaMailSender(未配置 spring.mail.host 时缺席)。
 * 上游:OutboundChannel SPI;下游:JavaMailSender 自动配置。
 */
@Component
public class MailOutboundChannel implements OutboundChannel {

    /** 发件人地址,取邮件账号自身,未配置时 available() 兜底不外发 */
    @Value("${spring.mail.username:snail-platform@noreply.local}")
    private String from;

    /** 邮件发送器,可能缺席 */
    private final ObjectProvider<JavaMailSender> mailSenders;

    public MailOutboundChannel(ObjectProvider<JavaMailSender> mailSenders) {
        this.mailSenders = mailSenders;
    }

    @Override
    public String channel() {
        return "mail";
    }

    @Override
    public boolean available() {
        return mailSenders.getIfAvailable() != null;
    }

    @Override
    public void send(OutboundMessage message) throws Exception {
        JavaMailSender sender = mailSenders.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("邮件通道未配置");
        }
        List<String> addresses = message.targets().stream()
                .map(OutboundTarget::address)
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .filter(Validator::isEmail)
                .distinct()
                .toList();
        if (addresses.isEmpty()) {
            throw new IllegalStateException("收件人没有可用邮箱地址");
        }
        MimeMessage mime = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
        helper.setFrom(from);
        helper.setSubject(message.title());
        helper.setText(message.content(), false);
        for (String address : addresses) {
            helper.addBcc(address);
        }
        sender.send(mime);
    }
}
