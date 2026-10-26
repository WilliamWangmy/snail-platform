package com.snail.platform.framework.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 职责:存储后端的装配单点——装配当前写入后端,并登记所有已配置后端供历史对象按类型路由。
 * 上游:application*.yml 的 snail.storage 段;下游:StoragePortRegistry、SftpStoragePort/S3StoragePort。
 * 口径:type 为空表示整体关闭;type 非空但对应后端未配置、或任一后端配置半截,启动即失败
 * (对齐 JwtTokenService 密钥校验姿势),不给静默降级——半配置跑到第一笔上传才炸是更大的事故。
 * 切换后端时把旧后端配置继续留着,历史文件即可按 sys_file.storage_type 继续读取。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SnailStorageProperties.class)
public class SnailStorageConfig {

    /** 合法的存储类型清单:空串表示整体关闭 */
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
    public StoragePortRegistry storagePortRegistry(SnailStorageProperties properties) {
        Map<String, StoragePort> ports = new LinkedHashMap<>();
        if (sftpPresent(properties.getSftp())) {
            ports.put(SnailStorageProperties.TYPE_SFTP, buildSftp(properties.getSftp()));
        }
        if (s3Present(properties.getS3())) {
            ports.put(SnailStorageProperties.TYPE_S3, buildS3(properties.getS3()));
        }
        String activeType = trimToNull(properties.getType());
        if (activeType != null && !ports.containsKey(activeType)) {
            throw new IllegalStateException(
                    "snail.storage.type=" + activeType + " 但该后端配置缺失,拒绝启动");
        }
        return new StoragePortRegistry(activeType, ports);
    }

    /** 配置是否出现:只要填了任一身份/连接键就要求配全,半截配置不允许静默消失 */
    private static boolean sftpPresent(SnailStorageProperties.Sftp sftp) {
        return hasText(sftp.getHost()) || hasText(sftp.getUsername())
                || hasText(sftp.getPassword()) || hasText(sftp.getPrivateKey())
                || hasText(sftp.getKnownHosts());
    }

    /** S3 以端点/桶/凭证为出现标记;region 有默认值,不能作为标记 */
    private static boolean s3Present(SnailStorageProperties.S3 s3) {
        return hasText(s3.getEndpoint()) || hasText(s3.getBucket())
                || hasText(s3.getAccessKey()) || hasText(s3.getSecretKey());
    }

    private static StoragePort buildSftp(SnailStorageProperties.Sftp sftp) {
        requireText(sftp.getHost(), "snail.storage.sftp.host");
        requireText(sftp.getUsername(), "snail.storage.sftp.username");
        requireText(sftp.getBaseDir(), "snail.storage.sftp.base-dir");
        if (!hasText(sftp.getPassword()) && !hasText(sftp.getPrivateKey())) {
            throw new IllegalStateException("snail.storage.sftp 缺 password 或 private-key,拒绝启动");
        }
        if (sftp.isStrictHostKeyChecking() && !hasText(sftp.getKnownHosts())) {
            throw new IllegalStateException(
                    "snail.storage.sftp.known-hosts 缺失:严格指纹校验开启时必填,拒绝启动");
        }
        if (sftp.getConnectTimeoutMs() <= 0 || sftp.getSessionTimeoutMs() <= 0) {
            throw new IllegalStateException("snail.storage.sftp 超时时间必须为正数,拒绝启动");
        }
        return new SftpStoragePort(sftp);
    }

    private static StoragePort buildS3(SnailStorageProperties.S3 s3) {
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

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
