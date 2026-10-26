package com.snail.platform.function.storage.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.context.TenantContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.framework.storage.StoragePort;
import com.snail.platform.framework.storage.SnailStorageProperties;
import com.snail.platform.function.storage.dto.FileQuery;
import com.snail.platform.function.storage.dto.FileVO;
import com.snail.platform.function.storage.entity.SysFile;
import com.snail.platform.function.storage.mapper.SysFileMapper;
import com.snail.platform.function.system.tenant.entity.SysTenant;
import com.snail.platform.function.system.tenant.mapper.SysTenantMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 职责:文件元数据与对象内容的全链编排——上传校验链(文件名清洗→白名单→大小→配额)、
 * 本租户下载、逻辑删+尽力物理删、本租户与平台面分页。
 * 上游:FileController/FilePlatformController;下游:StoragePort(可缺席)、sys_file 读写、
 * SysTenantMapper 配额直读(与 auth 同款的登记例外,单一只读边)。
 * 口径:端口缺席(snail.storage.type 为空)一律 400"文件存储未配置";租户内全可见(检索面口径);
 * 物理删失败只告警不回滚,残留对象由将来的 GC 任务清扫;事务里不做外部 IO,上传/删除均不开事务。
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    /** 字节换算 MB 的基数 */
    private static final long BYTES_PER_MB = 1024L * 1024L;

    /** 对象键的月份段格式 */
    private static final DateTimeFormatter KEY_MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    /** 对象键分隔符 */
    private static final String KEY_SEPARATOR = "/";

    /** 扩展名前导点 */
    private static final String EXT_LEADING_DOT = ".";

    /** 扩展名分隔符 */
    private static final char EXT_SEPARATOR = '.';

    /** 文件名最大长度,与 sys_file.file_name 列宽一致 */
    private static final int FILE_NAME_MAX_LENGTH = 255;

    /** 文件名非法段:路径分隔符与控制字符,清洗时统一替换 */
    private static final Pattern ILLEGAL_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    /** 非法字符替换符 */
    private static final String NAME_REPLACEMENT = "_";

    /** 配额汇总列别名,selectObjs 取首个单元格 */
    private static final String USED_SIZE_SELECT = "COALESCE(SUM(`size`), 0) AS used_size";

    /** 存储端口缺席时的统一文案 */
    private static final String STORAGE_NOT_CONFIGURED = "文件存储未配置";

    /** 存储端口提供者:type 为空时无实现,按缺席降级 */
    private final ObjectProvider<StoragePort> storagePortProvider;

    /** 文件元数据读写 */
    private final SysFileMapper fileMapper;

    /** 租户资料读:存储配额直读(登记例外,与 auth 同款) */
    private final SysTenantMapper tenantMapper;

    /** 存储后端类型标识,落 sys_file.storage_type */
    private final SnailStorageProperties properties;

    /** 归一化后的扩展名白名单,小写无点 */
    private final Set<String> extWhitelist;

    /** 单文件上限字节数,构造时换算一次 */
    private final long maxSizeBytes;

    public FileStorageService(ObjectProvider<StoragePort> storagePortProvider,
                              SysFileMapper fileMapper,
                              SysTenantMapper tenantMapper,
                              SnailStorageProperties properties) {
        this.storagePortProvider = storagePortProvider;
        this.fileMapper = fileMapper;
        this.tenantMapper = tenantMapper;
        this.properties = properties;
        this.extWhitelist = properties.getUpload().getExtWhitelist().stream()
                .map(ext -> ext.toLowerCase(Locale.ROOT))
                .map(ext -> ext.startsWith(EXT_LEADING_DOT) ? ext.substring(1) : ext)
                .filter(ext -> !ext.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.maxSizeBytes = (long) properties.getUpload().getMaxSizeMb() * BYTES_PER_MB;
    }

    /**
     * 上传:校验链顺序固定——文件名清洗→扩展名白名单(deny-by-default)→大小上限→租户配额;
     * 通过后对象键服务端生成,原文件名只进元数据,下载路径永不拼用户输入。
     */
    public FileVO upload(MultipartFile file, String bizType, Long bizId) {
        StoragePort port = requirePort();
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String fileName = cleanFileName(file.getOriginalFilename());
        String ext = extOf(fileName);
        if (ext == null || !extWhitelist.contains(ext)) {
            throw BizException.badRequest("文件类型不在白名单: " + (ext == null ? "无扩展名" : ext));
        }
        long size = file.getSize();
        if (size > maxSizeBytes) {
            throw BizException.badRequest("文件超过单文件上限 " + properties.getUpload().getMaxSizeMb() + "MB");
        }
        checkQuota(size);

        String storageKey = storageKey(ext);
        try (InputStream in = file.getInputStream()) {
            port.put(storageKey, in, size, file.getContentType());
        } catch (Exception e) {
            throw new IllegalStateException("文件写入存储失败: " + e.getMessage(), e);
        }

        SysFile row = new SysFile();
        row.setFileName(fileName);
        row.setExt(ext);
        row.setContentType(file.getContentType());
        row.setSize(size);
        row.setStorageKey(storageKey);
        row.setStorageType(properties.getType());
        row.setBizType(trimToNull(bizType));
        row.setBizId(bizId);
        LoginUser uploader = LoginUserContextHolder.get();
        row.setUploadBy(uploader == null ? null : uploader.userId());
        row.setUploadByName(uploader == null ? null : uploader.nickname());
        fileMapper.insert(row);
        return FileVO.from(row);
    }

    /**
     * 下载:本租户语义——selectById 被租户拦截器圈定,跨租户/已删一律按不存在拒绝
     * (存在性即租户隐私,与 OperLogService.detail 同口径);内容流由调用方负责关闭。
     */
    public StorageDownload download(Long fileId) {
        SysFile file = requireFile(fileId);
        return new StorageDownload(file, requirePort().get(file.getStorageKey()));
    }

    /** 删除:逻辑删即时生效;物理删尽力而为,失败只告警不回滚,残留由 GC 任务清扫 */
    public void remove(Long fileId) {
        SysFile file = requireFile(fileId);
        fileMapper.deleteById(fileId);
        try {
            requirePort().delete(file.getStorageKey());
        } catch (Exception e) {
            log.warn("文件 {} 物理删除失败,残留对象等待 GC 任务清扫: {}", fileId, e.getMessage());
        }
    }

    /** 本租户文件分页,租户拦截器圈定 */
    public PageResult<FileVO> page(FileQuery query) {
        return doPage(query);
    }

    /** 平台面跨租户检索:走登记的第三类豁免路径(TenantContextHolder.withoutTenant 窄窗口) */
    public PageResult<FileVO> platformPage(FileQuery query) {
        return TenantContextHolder.withoutTenant(() -> doPage(query));
    }

    /** 下载载荷:元数据 + 内容流;content 由调用方负责关闭 */
    public record StorageDownload(SysFile file, InputStream content) {
    }

    private PageResult<FileVO> doPage(FileQuery query) {
        Page<SysFile> page = PageAssembler.from(query);
        String fileName = trimToNull(query.fileName());
        String bizType = trimToNull(query.bizType());
        IPage<SysFile> result = fileMapper.selectPage(page, Wrappers.<SysFile>lambdaQuery()
                .like(fileName != null, SysFile::getFileName, fileName)
                .eq(bizType != null, SysFile::getBizType, bizType)
                .orderByDesc(SysFile::getFileId));
        return PageResult.of(result.getRecords().stream().map(FileVO::from).toList(),
                result.getTotal());
    }

    private StoragePort requirePort() {
        StoragePort port = storagePortProvider.getIfAvailable();
        if (port == null) {
            throw BizException.badRequest(STORAGE_NOT_CONFIGURED);
        }
        return port;
    }

    private SysFile requireFile(Long fileId) {
        SysFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw BizException.badRequest("文件不存在");
        }
        return file;
    }

    /** 清洗文件名:去路径段(浏览器可能原样传全路径)、替换控制字符、保留尾段截断到列宽 */
    private String cleanFileName(String original) {
        if (original == null || original.isBlank()) {
            throw BizException.badRequest("上传文件名不能为空");
        }
        String name = original;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        name = ILLEGAL_NAME_CHARS.matcher(name).replaceAll(NAME_REPLACEMENT).trim();
        if (name.isBlank()) {
            throw BizException.badRequest("上传文件名不能为空");
        }
        // 从尾段截断,保住扩展名
        return name.length() > FILE_NAME_MAX_LENGTH
                ? name.substring(name.length() - FILE_NAME_MAX_LENGTH)
                : name;
    }

    /** 取小写无点扩展名;无扩展名返回 null(白名单自然拒绝) */
    private String extOf(String fileName) {
        int dot = fileName.lastIndexOf(EXT_SEPARATOR);
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 配额校验:storageLimitMb 为 NULL 不限;占用为全租户 SUM(十万行内可接受,phase 2 上汇总表) */
    private void checkQuota(long incomingSize) {
        Long tenantId = TenantContextHolder.get();
        if (tenantId == null) {
            return;
        }
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null || tenant.getStorageLimitMb() == null) {
            return;
        }
        long limitBytes = tenant.getStorageLimitMb() * BYTES_PER_MB;
        if (usedBytes() + incomingSize > limitBytes) {
            throw BizException.badRequest("租户存储配额不足");
        }
    }

    /** 全租户已用字节数;租户条件与逻辑删条件由拦截器自动注入 */
    private long usedBytes() {
        List<Object> totals = fileMapper.selectObjs(
                Wrappers.<SysFile>query().select(USED_SIZE_SELECT));
        Object first = totals.isEmpty() ? null : totals.get(0);
        return first instanceof Number number ? number.longValue() : 0L;
    }

    /** 对象键:{tenantId}/{yyyyMM}/{uuid}.{ext}——隔离靠元数据表与接口层,键布局只方便人工排查 */
    private String storageKey(String ext) {
        Long tenantId = TenantContextHolder.get();
        return tenantId + KEY_SEPARATOR + KEY_MONTH.format(LocalDate.now())
                + KEY_SEPARATOR + UUID.randomUUID() + EXT_LEADING_DOT + ext;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
