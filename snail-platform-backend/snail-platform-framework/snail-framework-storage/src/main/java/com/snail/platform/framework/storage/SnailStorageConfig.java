package com.snail.platform.framework.storage;

import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:存储端口的装配单点——type=sftp/s3 时二选一装配对应 StoragePort;
 * type 为空(缺省)时两个 Bean 都不装配,文件功能由业务侧按端口缺席降级(对齐 OperLogSink 模式)。
 * 上游:application*.yml 的 snail.storage 段;下游:SftpStoragePort/S3StoragePort。
 * 口径:type 非法或所选后端配置缺失,启动即失败(IllegalStateException,对齐 JwtTokenService 密钥校验姿势),
 * 不给静默降级——半配置状态跑到第一笔上传才炸是更大的事故。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SnailStorageProperties.class)
public class SnailStorageConfig {

    /** 合法的存储类型清单:空串表示整体关闭,与两个 @Bean 的 havingValue 对齐 */
    private static final Set<String> VALID_TYPES =
            Set.of("", SnailStorageProperties.TYPE_SFTP, SnailStorageProperties.TYPE_S3);

    public SnailStorageConfig(SnailStorageProperties properties) {
        String type = properties.getType();
        if (type == null || !VALID_TYPES.contains(type.trim())) {
            throw new IllegalStateException(
                    "snail.storage.type 只允许空/sftp/s3,当前值: " + type + ",拒绝启动");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "snail.storage.type", havingValue = SnailStorageProperties.TYPE_SFTP)
    public StoragePort sftpStoragePort(SnailStorageProperties properties) {
        SnailStorageProperties.Sftp sftp = properties.getSftp();
        requireText(sftp.getHost(), "snail.storage.sftp.host");
        requireText(sftp.getUsername(), "snail.storage.sftp.username");
        requireText(sftp.getBaseDir(), "snail.storage.sftp.base-dir");
        if (!hasText(sftp.getPassword()) && !hasText(sftp.getPrivateKey())) {
            throw new IllegalStateException("snail.storage.sftp 缺 password 或 private-key,拒绝启动");
        }
        return new SftpStoragePort(sftp);
    }

    @Bean
    @ConditionalOnProperty(name = "snail.storage.type", havingValue = SnailStorageProperties.TYPE_S3)
    public StoragePort s3StoragePort(SnailStorageProperties properties) {
        SnailStorageProperties.S3 s3 = properties.getS3();
        requireText(s3.getEndpoint(), "snail.storage.s3.endpoint");
        requireText(s3.getRegion(), "snail.storage.s3.region");
        requireText(s3.getBucket(), "snail.storage.s3.bucket");
        requireText(s3.getAccessKey(), "snail.storage.s3.access-key");
        requireText(s3.getSecretKey(), "snail.storage.s3.secret-key");
        return new S3StoragePort(s3);
    }

    /** 配置完整性校验:缺失即启动失败,报错点名缺失的配置键 */
    private static void requireText(String value, String key) {
        if (!hasText(value)) {
            throw new IllegalStateException(key + " 缺失,拒绝启动");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
