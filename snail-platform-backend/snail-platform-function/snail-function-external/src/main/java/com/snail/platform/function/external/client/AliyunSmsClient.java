package com.snail.platform.function.external.client;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.snail.platform.function.external.config.SnailExternalProperties;
import com.snail.platform.function.external.api.SmsPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 职责:阿里云短信厂商适配(号码认证服务 SendSmsVerifyCode)——验证码由本系统生成经
 * Valkey 校验,阿里云只负责送达,与老 snail 平台联调通过的同款链路。
 * 出站链路:SendSmsVerifyCode(signName+templateCode+phone+templateParam)异步发出,
 * 5 秒同步等待响应,body.code=OK 即送达;其余报错统一走模板方法归一 ExternalCallException。
 * 模板变量在调用方 params 基础上合并 ${min}(验证码有效分钟,报备模板含该占位符时使用)。
 * 快照口径:手机号先打码再拼,AK/SK 与签名不进快照;响应摘要只落阿里云业务码。
 * 装配:snail.external.sms.mock=false(生产缺省)时注册;AsyncClient 由本模块
 * AliyunSmsClientConfig 装配(SDK 类型不得越出网关,执法例外),region 未配置
 * (共享 yml 注释态)时 bean 缺席,available()=false 按通道未配置降级。
 * 上游:external.api 的 SmsPort(auth 验证码/notify 通知);下游:ExternalCallLedger 流水。
 */
@Component
@ConditionalOnProperty(name = "snail.external.sms.mock", havingValue = "false", matchIfMissing = true)
public class AliyunSmsClient extends AbstractExternalClient implements SmsPort {

    /** 同步等待阿里云响应的上限(秒);无限等待会在阿里云侧故障时挂死调用线程 */
    private static final long SEND_TIMEOUT_SECONDS = 5L;

    /** 阿里云业务成功码 */
    private static final String OK_CODE = "OK";

    /** 阿里云客户端,进程内单例,可能缺席(region 未配置时不装配) */
    private final ObjectProvider<AsyncClient> smsClients;

    /** 通道配置单点:签名与验证码有效分钟取这里,凭据只进 SDK */
    private final SnailExternalProperties props;

    public AliyunSmsClient(ExternalCallLedger ledger, ObjectProvider<AsyncClient> smsClients,
                           SnailExternalProperties props) {
        super(ledger);
        this.smsClients = smsClients;
        this.props = props;
    }

    @Override
    protected String vendor() {
        return "aliyun-sms";
    }

    @Override
    public boolean available() {
        return smsClients.getIfAvailable() != null && StrUtil.isNotBlank(props.getSms().getSignName());
    }

    @Override
    public void send(SmsCommand command) {
        AsyncClient client = smsClients.getIfAvailable();
        if (client == null) {
            // available() 已闸,这里只是防御:客户端缺席(通道未配置)直接报错,不做静默成功
            throw new IllegalStateException("阿里云短信通道未装配(region 未配置或 mock 开关冲突)");
        }
        // 模板变量:调用方 params 优先,再补验证码有效分钟(与老平台报备模板 ${code}/${min} 口径一致)
        Map<String, String> templateParam = new LinkedHashMap<>();
        if (command.params() != null) {
            templateParam.putAll(command.params());
        }
        templateParam.putIfAbsent("min", props.getSms().getCodeValidMinutes());

        // 请求快照:手机号先打码再拼,密钥/签名不进快照;模板变量键会带 code,不落码值
        String requestSnapshot = "{\"phone\":\"" + jsonString(ExternalMasker.maskPhone(command.phone()))
                + "\",\"templateCode\":\"" + jsonString(command.templateCode())
                + "\",\"paramKeys\":" + JSONUtil.toJsonStr(templateParam.keySet()) + "}";
        invoke(new ExternalInvocation("send-sms", command.caller(), command.bizType(),
                        command.bizId(), requestSnapshot),
                () -> {
                    SendSmsVerifyCodeRequest request = SendSmsVerifyCodeRequest.builder()
                            .signName(props.getSms().getSignName())
                            .templateCode(command.templateCode())
                            .phoneNumber(command.phone())
                            .templateParam(JSONUtil.toJsonStr(templateParam))
                            .build();
                    CompletableFuture<SendSmsVerifyCodeResponse> future = client.sendSmsVerifyCode(request);
                    try {
                        return summarize(future.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS).getBody());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("短信发送被中断", e);
                    } catch (ExecutionException | TimeoutException e) {
                        throw new IllegalStateException("短信发送失败:" + e.getMessage(), e);
                    }
                });
    }

    /** 响应摘要:只落阿里云业务码,失败在此抛出由模板方法归一留痕 */
    private static String summarize(
            com.aliyun.sdk.service.dypnsapi20170525.models.SendSmsVerifyCodeResponseBody body) {
        if (body == null) {
            throw new IllegalStateException("短信发送失败:阿里云返回为空");
        }
        String code = body.getCode();
        if (!OK_CODE.equals(code)) {
            throw new IllegalStateException("短信发送失败:" + code + " " + body.getMessage());
        }
        return OK_CODE;
    }
}
