package com.snail.platform.function.notify.service;

import cn.hutool.core.util.StrUtil;
import com.snail.platform.function.external.api.SmsPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 职责:短信出站通道——模板制降级(方案 D8):端口契约是"签名+报备模板+参数",自由文案不进契约;
 * 待办模板编码走配置,未配置即通道不可用,投递器跳过(通道挂了不堵审批,站内信不受影响)。
 * 逐号发送是 SmsPort 契约(单发一号一次);手机号为空的收件人静默缺席——地址缺失不算失败(方案 §6.2)。
 * 上游:OutboundChannel SPI;下游:external.api 的 SmsPort(跨模块只准经 api 包,ArchUnit 执法)。
 */
@Component
public class SmsOutboundChannel implements OutboundChannel {

    /** 短信发送端口;开发/测试走 mock 厂商,生产由真实厂商适配顶位 */
    private final ObjectProvider<SmsPort> smsPort;

    /** 流程待办通用模板编码:阿里云报备后回填,缺省空 = 通道不可用 */
    private final String todoTemplateCode;

    public SmsOutboundChannel(ObjectProvider<SmsPort> smsPort,
                              @Value("${snail.notify.sms.templates.todo:}") String todoTemplateCode) {
        this.smsPort = smsPort;
        this.todoTemplateCode = todoTemplateCode;
    }

    @Override
    public String channel() {
        return CHANNEL_SMS;
    }

    @Override
    public boolean available() {
        SmsPort port = smsPort.getIfAvailable();
        return port != null && port.available() && StrUtil.isNotBlank(todoTemplateCode);
    }

    @Override
    public void send(OutboundMessage message) throws Exception {
        SmsPort port = smsPort.getIfAvailable();
        if (port == null) {
            // available() 已闸,这里只是防御:通道缺席时静默,不产生任何投递
            return;
        }
        for (OutboundTarget target : message.targets()) {
            if (StrUtil.isBlank(target.phone())) {
                continue;
            }
            port.send(new SmsPort.SmsCommand(target.phone(), todoTemplateCode, message.params(),
                    "notify", "outbound", message.messageId()));
        }
    }
}
