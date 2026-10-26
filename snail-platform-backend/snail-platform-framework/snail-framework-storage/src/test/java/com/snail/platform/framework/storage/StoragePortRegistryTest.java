package com.snail.platform.framework.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 职责:存储注册表的纯单测——写入端口与历史类型端口相互独立,未知类型不冒充当前后端。
 * 上游:StoragePortRegistry;下游:两个不联网的占位端口。
 * 口径:切换后端后旧文件按元数据记录的类型仍可取到原端口,是 T3.3 的核心承诺。
 */
class StoragePortRegistryTest {

    /** 占位端口:本测试只验证类型到端口的映射,不读写内容 */
    private static final StoragePort SFTP = new NoopPort();

    /** 占位端口:作为切换后的当前写入后端 */
    private static final StoragePort S3 = new NoopPort();

    @Test
    void activeIsWriteBackendAndHistoricalTypeKeepsItsOwnPort() {
        StoragePortRegistry registry = new StoragePortRegistry("s3", Map.of("sftp", SFTP, "s3", S3));

        assertSame(S3, registry.active().orElseThrow(), "当前写入端口应为 s3");
        assertSame(SFTP, registry.of("sftp").orElseThrow(), "历史 sftp 文件应路由回 sftp 端口");
        assertSame(S3, registry.of("s3").orElseThrow(), "按记录类型取 s3");
        assertTrue(registry.of("minio").isEmpty(), "未配置类型必须返回空,不能回退当前后端");
    }

    @Test
    void disabledStorageHasNoActivePort() {
        StoragePortRegistry registry = new StoragePortRegistry(null, Map.of("sftp", SFTP));

        assertTrue(registry.active().isEmpty(), "type 为空时没有写入端口");
        assertFalse(registry.of("sftp").isEmpty(), "历史端口仍可按类型取到");
    }

    /** 不联网的端口占位 */
    private static final class NoopPort implements StoragePort {

        @Override
        public void put(String key, InputStream in, long size, String contentType) {
        }

        @Override
        public InputStream get(String key) {
            return InputStream.nullInputStream();
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public boolean exists(String key) {
            return false;
        }
    }
}
