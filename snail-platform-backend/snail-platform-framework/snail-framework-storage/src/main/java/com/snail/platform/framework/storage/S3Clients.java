package com.snail.platform.framework.storage;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;

/**
 * 职责:S3Client 的单点构造——端点/寻址/凭证/HTTP 客户端的装配只写一遍,S3StoragePort 与
 * S3BucketProvisioner 共用,防止两处构造漂移出不同的寻址行为。
 */
final class S3Clients {

    private S3Clients() {
    }

    /** 按配置构造同步客户端:path-style 决定 MinIO 形态与云厂商虚拟主机形态 */
    static S3Client create(SnailStorageProperties.S3 config) {
        return S3Client.builder()
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
}
