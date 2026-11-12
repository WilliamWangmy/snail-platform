package com.snail.platform.framework.storage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 职责:BucketProvisioner 的 S3 实现——headBucket 探测,404 即 createBucket。
 * 上游:SnailStorageConfig 装配(仅 type=s3 时注册 bean);下游:S3Clients 构造的同步客户端。
 * 口径:幂等——桶已存在直接返回;仅 404 按"桶缺失"处理,其余错误原样上抛,
 * 部分兼容端点对 404 的异常映射不同时错误会在开通侧暴露而不是静默假成功。
 */
public class S3BucketProvisioner implements BucketProvisioner {

    /** 同步 S3 客户端,线程安全;开通是低频动作,不与读写端口共享实例 */
    private final S3Client client;

    public S3BucketProvisioner(SnailStorageProperties.S3 config) {
        this.client = S3Clients.create(config);
    }

    @Override
    public void ensureBucket(String bucket) {
        ensure(client, bucket);
    }

    /**
     * 确保桶存在:headBucket 404 即 createBucket,其余错误原样上抛。
     * 不携带 CreateBucketConfiguration:region 默认 us-east-1,MinIO/云 OSS 兼容端点均可接受;
     * 标准 AWS 分区需要 LocationConstraint 时应预建桶或关掉自动建桶路径。
     */
    static void ensure(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            // SDK 对 headBucket 404 建模为 NoSuchBucketException,S3Exception 的子类;
            // 部分兼容端点回裸 404,统一按状态码收口
            if (e.statusCode() != 404) {
                throw e;
            }
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }
}
