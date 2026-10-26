package com.snail.platform.function.storage.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.storage.SnailStorageProperties;
import com.snail.platform.framework.storage.StoragePort;
import com.snail.platform.framework.storage.StoragePortRegistry;
import com.snail.platform.function.storage.entity.SysFile;
import com.snail.platform.function.storage.mapper.SysFileMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 职责:文件服务的纯单测——按元数据 storage_type 路由历史对象、后端类型未配置的明确拒绝、
 * 对象写成功但元数据入库失败时的补偿删除。不连库、不连存储。
 * 上游:FileStorageService;下游:Mockito 替身 mapper/quota/policy 与内存端口。
 * 口径:路由与补偿是 T3.3/T3.5 的核心承诺,必须能在没有外部依赖时被钉住。
 */
class FileStorageServiceTest {

    /** 文件元数据 mapper 替身 */
    private final SysFileMapper fileMapper = mock(SysFileMapper.class);

    /** 配额服务替身:单测不验证配额,只控制入库成功/失败 */
    private final FileQuotaService quotaService = mock(FileQuotaService.class);

    /** 可见性策略替身:路由用例直接放行 */
    private final FileAccessPolicy accessPolicy = mock(FileAccessPolicy.class);

    /** 历史 sftp 端口:记录取用情况 */
    private final FakePort sftp = new FakePort();

    /** 当前写入 s3 端口:记录写入与补偿删除 */
    private final FakePort s3 = new FakePort();

    /** 当前写入 s3,同时保留 sftp 供历史对象读取 */
    private final FileStorageService service = new FileStorageService(
            new StoragePortRegistry("s3", Map.of("sftp", sftp, "s3", s3)),
            fileMapper, quotaService, accessPolicy, properties());

    @Test
    void downloadUsesStoredTypeInsteadOfActiveBackend() throws Exception {
        SysFile row = file(11L, "sftp", "7/202610/legacy.txt", 7L);
        when(fileMapper.selectById(11L)).thenReturn(row);
        when(accessPolicy.canRead(any(), any())).thenReturn(true);
        sftp.objects.put("7/202610/legacy.txt", "legacy".getBytes(StandardCharsets.UTF_8));

        FileStorageService.StorageDownload download = service.download(11L);

        try (InputStream content = download.content()) {
            assertEquals("legacy", new String(content.readAllBytes(), StandardCharsets.UTF_8),
                    "历史文件应由 sftp 端口读取");
        }
        assertEquals(List.of("7/202610/legacy.txt"), sftp.gets, "sftp 端口应收到取用");
        assertTrue(s3.gets.isEmpty(), "不得回退到当前写入后端 s3");
    }

    @Test
    void downloadRejectsStoredTypeWithoutConfiguredBackend() {
        SysFile row = file(12L, "minio", "7/202610/orphan.txt", 7L);
        when(fileMapper.selectById(12L)).thenReturn(row);
        when(accessPolicy.canRead(any(), any())).thenReturn(true);

        BizException error = assertThrows(BizException.class, () -> service.download(12L));

        assertEquals(400, error.getCode(), "未配置后端应走业务拒绝");
        assertTrue(error.getMessage().contains("minio"), "报错要点名缺失的后端类型: " + error.getMessage());
        assertTrue(s3.gets.isEmpty(), "缺失后端不得回退到当前写入端口");
    }

    @Test
    void uploadDeletesWrittenObjectWhenMetadataInsertFails() {
        doThrow(new IllegalStateException("db down")).when(quotaService).insertWithinQuota(any());
        MockMultipartFile file = new MockMultipartFile("file", "note.txt",
                "text/plain", "hello".getBytes(StandardCharsets.UTF_8));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.upload(file, "test", null));

        assertEquals("db down", error.getMessage(), "原异常应原样抛出");
        assertEquals(1, s3.puts.size(), "对象应已写入");
        assertEquals(s3.puts, s3.deletes, "入库失败后应补偿删除同一个对象键");
        verify(quotaService).assertCapacity(file.getSize());
    }

    private static SysFile file(Long fileId, String storageType, String storageKey, Long uploadBy) {
        SysFile row = new SysFile();
        row.setFileId(fileId);
        row.setStorageType(storageType);
        row.setStorageKey(storageKey);
        row.setUploadBy(uploadBy);
        row.setFileName("a.txt");
        row.setSize(6L);
        return row;
    }

    private static SnailStorageProperties properties() {
        SnailStorageProperties properties = new SnailStorageProperties();
        properties.setType("s3");
        properties.getUpload().setMaxSizeMb(1);
        properties.getUpload().setExtWhitelist(List.of("txt"));
        return properties;
    }

    /** 内存端口:只记录键与内容,不触达网络 */
    private static final class FakePort implements StoragePort {

        private final Map<String, byte[]> objects = new HashMap<>();

        private final List<String> puts = new ArrayList<>();

        private final List<String> gets = new ArrayList<>();

        private final List<String> deletes = new ArrayList<>();

        @Override
        public void put(String key, InputStream in, long size, String contentType) {
            puts.add(key);
            try {
                objects.put(key, in.readAllBytes());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public InputStream get(String key) {
            gets.add(key);
            return new ByteArrayInputStream(objects.getOrDefault(key, new byte[0]));
        }

        @Override
        public void delete(String key) {
            deletes.add(key);
            objects.remove(key);
        }

        @Override
        public boolean exists(String key) {
            return objects.containsKey(key);
        }
    }
}
