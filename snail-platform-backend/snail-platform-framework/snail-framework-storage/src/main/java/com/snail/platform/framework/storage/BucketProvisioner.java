package com.snail.platform.framework.storage;

/**
 * 职责:桶的开通侧动作——按名称确保桶存在(不存在即创建),供租户开通等业务装配点调用。
 * 口径:与 StoragePort 分离,桶概念不属于统一读写契约(SFTP 无桶,历史文件路由也不经过它);
 * 仅当活动写入后端是 s3 时由 SnailStorageConfig 装配,消费方用 ObjectProvider 按缺席降级。
 * 失败语义:网络/权限故障原样上抛,成败(阻断还是告警放行)由调用方按业务拍板。
 */
public interface BucketProvisioner {

    /** 桶不存在则创建,已存在直接返回(幂等);失败原样上抛 */
    void ensureBucket(String bucket);
}
