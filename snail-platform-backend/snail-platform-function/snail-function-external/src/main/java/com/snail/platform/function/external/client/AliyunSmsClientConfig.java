package com.snail.platform.function.external.client;

import cn.hutool.core.util.StrUtil;
import com.aliyun.auth.credentials.Credential;
import com.aliyun.auth.credentials.provider.DefaultCredentialProvider;
import com.aliyun.auth.credentials.provider.ICredentialProvider;
import com.aliyun.auth.credentials.provider.StaticCredentialProvider;
import com.aliyun.sdk.service.dypnsapi20170525.AsyncClient;
import com.snail.platform.function.external.config.SnailExternalProperties;
import darabonba.core.client.ClientOverrideConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:阿里云短信客户端装配(号码认证服务 SendSmsVerifyCode)——snail.external.sms.mock=false
 * (生产缺省)且共享 yml 解开 sms 段(region 有值)时创建,进程内单例随应用关闭销毁。
 * 与 MockSmsClient 条件互斥:dev mock=true 时本类不装配,短信走 mock 通道。
 * 凭证双模式:AK/SK 配了走静态凭证;两者都留空走默认凭证链(环境变量
 * ALIBABA_CLOUD_ACCESS_KEY_ID / ALIBABA_CLOUD_ACCESS_KEY_SECRET 等)。
 * region 是装配开关:注释态缺省则本类整体缺席,AliyunSmsClient.available()=false,
 * 短信登录按"通道未配置"降级;解开但 region 为空串属显式配错,启动即失败(带清晰错因)。
 * 注:第三方 SDK 类型不得越出本网关(架构执法,外部网关设计 §8),故 SDK 客户端工厂住本模块,
 * 与"模块不持 @Configuration、装配层住 start"的惯例冲突时以执法口径优先,属已登记的例外。
 * 上游:application*.yml 的 snail.external.sms 段;下游:AliyunSmsClient 发送链。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "snail.external.sms.mock", havingValue = "false", matchIfMissing = true)
public class AliyunSmsClientConfig {

    /**
     * AsyncClient 自带线程池与连接池,必须全进程复用,禁止每次发送新建(泄漏且昂贵);
     * 签名缺失不在装配期拦:发送时 available()=false 由调用方按通道未配置降级
     */
    @Bean(destroyMethod = "close")
    public AsyncClient smsAsyncClient(SnailExternalProperties props) {
        String region = props.getSms().getRegion();
        if (StrUtil.isBlank(region)) {
            throw new IllegalStateException("阿里云短信 region 未配置:"
                    + "解开 application.yml 的 snail.external.sms 段并回填 region(如 cn-chengdu)");
        }
        return AsyncClient.builder()
                .region(region)
                .credentialsProvider(credentialProvider(props))
                .overrideConfiguration(ClientOverrideConfiguration.create()
                        .setEndpointOverride(props.getSms().getRegionEndpoint()))
                .build();
    }

    /** 凭证双模式:AK/SK 齐全走静态凭证;任一缺失走默认凭证链,不在 yml 落任何密钥 */
    private ICredentialProvider credentialProvider(SnailExternalProperties props) {
        String accessKeyId = props.getSms().getAccessKeyId();
        String accessKeySecret = props.getSms().getAccessKeySecret();
        if (StrUtil.isNotBlank(accessKeyId) && StrUtil.isNotBlank(accessKeySecret)) {
            return StaticCredentialProvider.create(Credential.builder()
                    .accessKeyId(accessKeyId)
                    .accessKeySecret(accessKeySecret)
                    .build());
        }
        try {
            return DefaultCredentialProvider.builder().build();
        } catch (Exception noCredentials) {
            // 凭证链不可得属显式配错(region 已解开即声明要用真实通道):启动即失败,错因必须可操作
            throw new IllegalStateException("阿里云短信凭证未配置:AK/SK 留空时须提供默认凭证链——"
                    + "回填 SNAIL_SMS_ACCESS_KEY_ID/SECRET、设环境变量 ALIBABA_CLOUD_ACCESS_KEY_ID/SECRET、"
                    + "或放置 ~/.alibabacloud/credentials;开发态联调可设 SNAIL_SMS_MOCK=true 走 mock 通道",
                    noCredentials);
        }
    }
}
