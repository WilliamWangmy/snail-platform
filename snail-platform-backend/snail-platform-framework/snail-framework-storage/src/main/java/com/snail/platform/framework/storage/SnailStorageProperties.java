package com.snail.platform.framework.storage;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 职责:文件存储构件的配置单点——后端类型(sftp/s3,空即整体关闭)与各后端连接参数、上传约束。
 * 上游:application*.yml 的 snail.storage 段;下游:SnailStorageConfig、各 StoragePort 实现。
 * 口径:type 合法性与所选后端的配置完整性在 SnailStorageConfig 装配时校验,缺失即拒绝启动,不给静默降级。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "snail.storage")
public class SnailStorageProperties {

    /** 存储类型:SFTP */
    public static final String TYPE_SFTP = "sftp";

    /** 存储类型:S3 协议兼容(MinIO/云 OSS) */
    public static final String TYPE_S3 = "s3";

    /** 后端类型:空 = 不装配 StoragePort,文件功能由消费方降级;合法值 sftp/s3 */
    private String type;

    /** SFTP 后端配置 */
    private final Sftp sftp = new Sftp();

    /** S3 协议兼容后端配置 */
    private final S3 s3 = new S3();

    /** 上传约束配置 */
    private final Upload upload = new Upload();

    /** SFTP 连接参数;password 与 privateKey 二选一 */
    @Getter
    @Setter
    public static class Sftp {

        /** 主机 */
        private String host;

        /** 端口 */
        private int port = 22;

        /** 登录用户 */
        private String username;

        /** 登录口令,privateKey 存在时可空 */
        private String password;

        /** 私钥文件路径,可空;与 password 二选一 */
        private String privateKey;

        /** 根目录,允许不存在,put 时逐级自建;SFTP 下对象路径 = baseDir + "/" + key */
        private String baseDir;

        /** known_hosts 文件路径;strict-host-key-checking=true 时必填 */
        private String knownHosts;

        /** 主机指纹校验:生产默认开启;自签指纹的开发/测试环境显式关闭 */
        private boolean strictHostKeyChecking = true;

        /** 建连与开通道超时(毫秒),避免网络故障时挂死请求线程 */
        private int connectTimeoutMs = 10_000;

        /** 会话读写超时(毫秒),覆盖通道操作等不到响应的情况 */
        private int sessionTimeoutMs = 30_000;
    }

    /** S3 协议兼容连接参数;type=s3 时全部必填 */
    @Getter
    @Setter
    public static class S3 {

        /** 协议兼容端点,如 MinIO 的 http://host:9000 或云 OSS 的兼容域名 */
        private String endpoint;

        /** 区域标识,MinIO 习惯用 us-east-1 */
        private String region;

        /** 桶名 */
        private String bucket;

        /** 访问密钥 */
        private String accessKey;

        /** 私有密钥 */
        private String secretKey;

        /**
         * 路径式寻址:{true} 走 endpoint/bucket/key(MinIO 等自建只吃这一种),
         * {false} 走 bucket.endpoint/key(阿里 OSS、腾讯 COS 的 S3 兼容端点用虚拟主机形态)。
         */
        private boolean pathStyle = true;
    }

    /** 上传约束;multipart 上限在 spring.servlet.multipart 同步声明,这里只管业务侧校验 */
    @Getter
    @Setter
    public static class Upload {

        /** 单文件上限(MB) */
        private int maxSizeMb = 50;

        /** 扩展名白名单,deny-by-default;比对前统一小写、去首点 */
        private List<String> extWhitelist = new ArrayList<>();
    }
}
