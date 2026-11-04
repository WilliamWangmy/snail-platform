package com.snail.platform.function.external.client;

import cn.hutool.core.lang.Validator;
import cn.hutool.core.util.StrUtil;
import com.snail.platform.function.external.api.ExternalCallException;
import com.snail.platform.function.external.api.MailPort;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 职责:SMTP 邮件厂商适配——单封 MimeMessage、收件人全部走 BCC(互不暴露地址),
 * 地址过滤(空白/非法/去重)收口在这里,消费方传原始列表即可。
 * 可用性运行期自查而非条件装配:JavaMailSender 由 Spring Boot 自动配置提供,
 * 业务组件的装配期判不到它,ObjectProvider 每次投递时自报可用与否。
 * 上游:external.api 的 MailPort;下游:JavaMailSender 自动配置。
 */
@Component
public class SmtpMailClient extends AbstractExternalClient implements MailPort {

    /** 发件人地址,取邮件账号自身;JavaMailSender 在场时该配置必有值,缺省仅兜底占位 */
    @Value("${spring.mail.username:snail-platform@noreply.local}")
    private String from;

    /** 邮件发送器,可能缺席 */
    private final ObjectProvider<JavaMailSender> mailSenders;

    public SmtpMailClient(ExternalCallLedger ledger, ObjectProvider<JavaMailSender> mailSenders) {
        super(ledger);
        this.mailSenders = mailSenders;
    }

    @Override
    protected String vendor() {
        return "smtp-mail";
    }

    @Override
    public boolean available() {
        return mailSenders.getIfAvailable() != null;
    }

    @Override
    public void send(MailMessage message) {
        JavaMailSender sender = mailSenders.getIfAvailable();
        if (sender == null) {
            throw new ExternalCallException(vendor(), "send-mail", "邮件通道未配置");
        }
        // 空白/非法/重复地址在此剔除:脏地址会让整封被 SMTP 拒收,一个人也只该收到一封
        List<String> addresses = message.to() == null ? List.of() : message.to().stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .filter(Validator::isEmail)
                .distinct()
                .toList();
        if (addresses.isEmpty()) {
            // 全部无效按失败上抛,由调用方(或 notify 投递器)记失败与错因,不静默当成功
            throw new ExternalCallException(vendor(), "send-mail", "收件人没有可用邮箱地址");
        }
        // 请求快照:地址先打码再拼,密钥类配置根本不进快照;摘要记地址数,不记报文
        String requestSnapshot = "{\"subject\":\"" + jsonString(message.subject())
                + "\",\"to\":" + maskedAddressList(addresses) + "}";
        invoke(new ExternalInvocation("send-mail", message.caller(), message.bizType(),
                        message.bizId(), requestSnapshot),
                () -> {
                    try {
                        MimeMessage mime = sender.createMimeMessage();
                        MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
                        helper.setFrom(from);
                        helper.setSubject(message.subject());
                        // 第二个参数 false 即纯文本:正文由运营录入,不按 HTML 解析,免去标签注入客户端
                        helper.setText(message.content(), false);
                        // 收件人一律 BCC,同一封里互不可见地址
                        for (String address : addresses) {
                            helper.addBcc(address);
                        }
                        sender.send(mime);
                        return "bcc×" + addresses.size();
                    } catch (Exception e) {
                        // 受检异常(messaging)归一为运行时,模板方法统一按失败留痕后上抛
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });
    }

    /** 已打码地址的 JSON 数组字面量:["a***@x.com",...] */
    private String maskedAddressList(List<String> addresses) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < addresses.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(jsonString(ExternalMasker.maskEmail(addresses.get(i)))).append('"');
        }
        return json.append(']').toString();
    }
}
