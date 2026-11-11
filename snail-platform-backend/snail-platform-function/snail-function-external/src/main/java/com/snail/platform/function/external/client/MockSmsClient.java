package com.snail.platform.function.external.client;

import com.snail.platform.function.external.api.SmsPort;
import com.snail.platform.function.external.config.SnailExternalProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 职责:开发态短信厂商适配——把验证码"发送"动作变成一条日志,使短信登录在无阿里云账号时
 * 也能端到端联调;snail.external.sms.mock=true 时注册,生产缺省缺席,由 AliyunSmsClient(M2)顶位。
 * 与真实通道同一留痕口径:经模板方法落 sys_external_call_log,手机号先经 ExternalMasker 打码;
 * 码值任何日志都不输出——mock 只是替换传输方式,不放宽隐私口径。本地自测要从
 * Valkey 取 snail:auth:sms:{phone},或显式开 snail.external.sms.mock-echo-code(仅限开发环境)。
 * 上游:external.api 的 SmsPort;下游:ExternalCallLedger 流水。
 */
@Component
@ConditionalOnProperty(name = "snail.external.sms.mock", havingValue = "true")
public class MockSmsClient extends AbstractExternalClient implements SmsPort {

    private static final Logger log = LoggerFactory.getLogger(MockSmsClient.class);

    /** 开发自测便利开关:打开后码值随日志回显,默认关闭守住"码不落日志"口径 */
    private final boolean echoCode;

    public MockSmsClient(ExternalCallLedger ledger, SnailExternalProperties props) {
        super(ledger);
        this.echoCode = props.getSms().isMockEchoCode();
    }

    @Override
    protected String vendor() {
        return "mock-sms";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void send(SmsCommand command) {
        String requestSnapshot = "{\"phone\":\"" + jsonString(ExternalMasker.maskPhone(command.phone()))
                + "\",\"templateCode\":\"" + jsonString(command.templateCode()) + "\"}";
        invoke(new ExternalInvocation("send-sms", command.caller(), command.bizType(),
                        command.bizId(), requestSnapshot),
                () -> {
                    String code = command.params() == null ? null : command.params().get("code");
                    if (echoCode && code != null) {
                        log.info("短信验证码已发送至 {}(mock 通道,验证码 {},仅开发自测回显)",
                                ExternalMasker.maskPhone(command.phone()), code);
                    } else {
                        log.info("短信验证码已发送至 {}(mock 通道,模板 {},参数键 {})",
                                ExternalMasker.maskPhone(command.phone()), command.templateCode(),
                                command.params() == null ? "[]" : command.params().keySet());
                    }
                    return "mock";
                });
    }
}
