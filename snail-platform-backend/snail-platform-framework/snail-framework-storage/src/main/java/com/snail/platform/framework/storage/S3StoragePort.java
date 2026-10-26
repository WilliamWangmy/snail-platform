package com.snail.platform.framework.storage;

import java.io.InputStream;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

/**
 * 职责:StoragePort 的 S3 协议兼容实现——MinIO 与云厂商对象存储的 S3 兼容端点共用一套协议栈。
 * 上游:StoragePort;下游:AWS SDK v2 的同步 S3Client(url-connection HTTP 客户端,依赖树最小)。
 * 口径:寻址方式由 snail.storage.s3.path-style 决定,默认路径式(MinIO 不吃虚拟主机寻址),
 * 阿里 OSS/腾讯 COS 的兼容端点要显式关掉本开关改走虚拟主机寻址;
 * put 显式携带 ContentLength(已知,来自元数据);
 * get 返回 SDK 的 ResponseInputStream,调用方关闭;exists 走 headObject,NoSuchKey 即不存在。
 */
public class S3StoragePort implements StoragePort {

    /** 桶名,客户端构造后只读 */
    private final String bucket;

    /** 同步 S3 客户端,线程安全,随端口生命周期复用 */
    private final S3Client client;

    public S3StoragePort(SnailStorageProperties.S3 config) {
        this.bucket = config.getBucket();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(config.getEndpoint()))
                .region(Region.of(config.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())))
                // 自建 MinIO 只吃路径式;云厂商兼容端点由 path-style=false 切到虚拟主机寻址
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.isPathStyle())
                        .build())
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    @Override
    public void put(String key, InputStream in, long size, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        client.putObject(request, RequestBody.fromInputStream(in, size));
    }

    @Override
    public InputStream get(String key) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return client.getObject(request);
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public boolean exists(String key) {
        HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucket).key(key).build();
        try {
            client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
