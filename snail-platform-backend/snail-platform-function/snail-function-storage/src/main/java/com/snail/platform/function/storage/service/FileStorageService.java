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
import com.snail.platform.framework.storage.SnailStorageProperties;
import com.snail.platform.framework.storage.StoragePort;
import com.snail.platform.framework.storage.StoragePortRegistry;
import com.snail.platform.function.storage.dto.FileQuery;
import com.snail.platform.function.storage.dto.FileVO;
import com.snail.platform.function.storage.entity.SysFile;
import com.snail.platform.function.storage.mapper.SysFileMapper;
import com.snail.platform.function.system.role.entity.SysRole;
import com.snail.platform.function.system.role.entity.SysUserRole;
import com.snail.platform.function.system.role.mapper.SysRoleMapper;
import com.snail.platform.function.system.role.mapper.SysUserRoleMapper;
import com.snail.platform.function.system.role.service.UserPermissionService;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 职责:文件元数据与对象内容的全链编排——上传校验链(文件名清洗→白名单→大小→配额预检)、
 * 入库失败的对象补偿、文件级可见性判权、按 storage_type 路由的下载与删除、本租户与平台面分页。
 * 上游:FileController/FilePlatformController;下游:StoragePortRegistry(按类型取端口)、
 * FileQuotaService(配额预检与锁行入库)、FileAccessPolicy(文件级可见性)、sys_file 读写。
 * 口径:写入端口缺席(snail.storage.type 为空)一律 400"文件存储未配置";
 * 下载先过权限码(@PreAuthorize)再过文件级可见性;删除同样先过权限码,数据面再按人收口——
 * 超管可删本租户任意文件,其余持码人只能删自己上传的;历史对象按 sys_file.storage_type 取端口,
 * 类型未配置明确报错,绝不回退当前后端;物理删失败只告警不回滚,残留对象由将来的 GC 任务清扫。
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

    /** bizType 合法字符集:字母数字与 _ : - ,不放进查询与元数据的裸字符串垃圾场 */
    private static final Pattern BIZ_TYPE_PATTERN = Pattern.compile("[\\w:-]+");

    /** bizType 长度上限,与列宽对齐即可,不需要更长 */
    private static final int BIZ_TYPE_MAX_LENGTH = 64;

    /** 文件类型长度上限,与 sys_file.file_type 列宽对齐 */
    private static final int FILE_TYPE_MAX_LENGTH = 50;

    /**
     * 文件类型缺省值:平台文件(platform),与内置字典 sys_file_type 的种子默认项、
     * sys_file.file_type 列默认值三方同码——字典值建后不可改且内置项不许删,该码终身稳定。
     */
    private static final String DEFAULT_FILE_TYPE = "platform";

    /** 头像业务域:与 bizId=用户ID 一起定位本人头像 */
    private static final String AVATAR_BIZ_TYPE = "avatar";

    /** 头像文件类型:内置字典 sys_file_type 的内置值 avatar(头像),上传时固定使用 */
    private static final String AVATAR_FILE_TYPE = "avatar";

    /** 头像只收图片扩展名,与 FileController 的图片响应类型映射对齐 */
    private static final Set<String> AVATAR_EXT_WHITELIST = Set.of("jpg", "jpeg", "png", "gif", "webp");

    /** 头像单文件上限:小图即可,避免头像拖垮列表渲染与带宽 */
    private static final long AVATAR_MAX_BYTES = 2L * BYTES_PER_MB;

    /** 写入端口缺席时的统一文案 */
    private static final String STORAGE_NOT_CONFIGURED = "文件存储未配置";

    /** 历史对象所属后端未配置时的文案前缀 */
    private static final String BACKEND_NOT_CONFIGURED = "文件所属存储后端未配置: ";

    /** 存储后端注册表:当前写入端口 + 历史类型端口 */
    private final StoragePortRegistry storageRegistry;

    /** 文件元数据读写 */
    private final SysFileMapper fileMapper;

    /** 用户-角色授权读:删除归属判定要先取当前登录人的角色 */
    private final SysUserRoleMapper userRoleMapper;

    /** 角色表读:与 isSuperAdmin 那把尺配套 */
    private final SysRoleMapper roleMapper;

    /** 配额预检与锁行入库 */
    private final FileQuotaService quotaService;

    /** 文件级可见性判定 */
    private final FileAccessPolicy accessPolicy;

    /** 上传约束:白名单、单文件上限与当前写入类型 */
    private final SnailStorageProperties properties;

    /** 归一化后的扩展名白名单,小写无点 */
    private final Set<String> extWhitelist;

    /** 单文件上限字节数,构造时换算一次 */
    private final long maxSizeBytes;

    public FileStorageService(StoragePortRegistry storageRegistry,
                              SysFileMapper fileMapper,
                              SysUserRoleMapper userRoleMapper,
                              SysRoleMapper roleMapper,
                              FileQuotaService quotaService,
                              FileAccessPolicy accessPolicy,
                              SnailStorageProperties properties) {
        this.storageRegistry = storageRegistry;
        this.fileMapper = fileMapper;
        this.userRoleMapper = userRoleMapper;
        this.roleMapper = roleMapper;
        this.quotaService = quotaService;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.extWhitelist = properties.getUpload().getExtWhitelist().stream()
                .map(ext -> ext.toLowerCase(Locale.ROOT))
                .map(ext -> ext.startsWith(EXT_LEADING_DOT) ? ext.substring(1) : ext)
                .filter(ext -> !ext.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.maxSizeBytes = (long) properties.getUpload().getMaxSizeMb() * BYTES_PER_MB;
    }

    /**
     * 上传:校验链顺序固定——文件名清洗→扩展名白名单(deny-by-default)→大小上限→配额预检;
     * 通过后对象键服务端生成,原文件名只进元数据,下载路径永不拼用户输入。
     * 对象写成功后元数据入库失败(含授权配额竞态)时,尽力回收刚写入的对象,避免孤儿文件。
     */
    public FileVO upload(MultipartFile file, String bizType, Long bizId, String fileType) {
        StoragePort port = requireActivePort();
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String fileName = cleanFileName(file.getOriginalFilename());
        String ext = extOf(fileName);
        if (ext == null || !extWhitelist.contains(ext)) {
            throw BizException.badRequest("文件类型不在白名单: " + (ext == null ? "无扩展名" : ext));
        }
        // bizType 是自由标注字段,但会进查询条件与元数据落库:收口长度与字符集,防当垃圾场
        String normalizedBizType = normalizeBizType(bizType);
        // fileType 取自内置字典 sys_file_type 的下拉:不传按缺省值(平台文件)归一,超长按业务拒绝
        String normalizedFileType = normalizeFileType(fileType);
        long size = file.getSize();
        if (size > maxSizeBytes) {
            throw BizException.badRequest("文件超过单文件上限 " + properties.getUpload().getMaxSizeMb() + "MB");
        }
        quotaService.assertCapacity(size);

        String storageKey = storageKey(ext);
        try (InputStream in = file.getInputStream()) {
            port.put(storageKey, in, size, file.getContentType());
        } catch (Exception e) {
            // 写对象失败是存储侧故障,不按业务拒绝包装:参数问题才归 400,后端挂了要让调用方看见
            throw new IllegalStateException("文件写入存储失败: " + e.getMessage(), e);
        }

        SysFile row = new SysFile();
        row.setFileName(fileName);
        row.setExt(ext);
        row.setContentType(file.getContentType());
        row.setSize(size);
        row.setStorageKey(storageKey);
        // 这一列是历史对象的路由依据:日后换写入后端,老文件仍按行内类型取端口读
        row.setStorageType(storageRegistry.activeType());
        row.setBizType(normalizedBizType);
        row.setBizId(bizId);
        row.setFileType(normalizedFileType);
        LoginUser uploader = LoginUserContextHolder.get();
        row.setUploadBy(uploader == null ? null : uploader.userId());
        row.setUploadByName(uploader == null ? null : uploader.nickname());
        try {
            quotaService.insertWithinQuota(row);
        } catch (RuntimeException e) {
            deleteObjectQuietly(port, storageKey, "元数据入库失败");
            throw e;
        }
        return FileVO.from(row);
    }

    /**
     * 本人头像上传:业务域与文件类型固定为 avatar(字典内置值),只收图片扩展名且限 2MB。
     * 新图落库成功后再清旧行——反过来先删后传,一旦写存储失败用户头像就丢了。
     */
    public FileVO uploadAvatar(MultipartFile file) {
        Long userId = LoginUserContextHolder.userId();
        if (file == null || file.isEmpty()) {
            throw BizException.badRequest("上传文件为空");
        }
        String ext = extOf(cleanFileName(file.getOriginalFilename()));
        if (ext == null || !AVATAR_EXT_WHITELIST.contains(ext)) {
            throw BizException.badRequest("头像仅支持 JPG/PNG/GIF/WEBP 图片");
        }
        if (file.getSize() > AVATAR_MAX_BYTES) {
            throw BizException.badRequest("头像大小不能超过 2MB");
        }
        FileVO created = upload(file, AVATAR_BIZ_TYPE, userId, AVATAR_FILE_TYPE);
        for (SysFile row : avatarRows(userId)) {
            if (!row.getFileId().equals(created.fileId())) {
                removeRow(row);
            }
        }
        return created;
    }

    /** 本人头像内容:未设置返回 null(接口层回 204),按 bizType+bizId=当前用户 取最新一条 */
    public StorageDownload avatar(Long userId) {
        if (userId == null) {
            return null;
        }
        SysFile file = avatarRows(userId).stream().findFirst().orElse(null);
        if (file == null) {
            return null;
        }
        return new StorageDownload(file, requirePortOf(file).get(file.getStorageKey()));
    }

    private List<SysFile> avatarRows(Long userId) {
        return fileMapper.selectList(Wrappers.<SysFile>lambdaQuery()
                .eq(SysFile::getBizType, AVATAR_BIZ_TYPE)
                .eq(SysFile::getBizId, userId)
                .eq(SysFile::getFileType, AVATAR_FILE_TYPE)
                .orderByDesc(SysFile::getFileId));
    }

    /**
     * 下载:本租户语义——selectById 被租户拦截器圈定,跨租户/已删一律按不存在拒绝
     * (存在性即租户隐私,与 OperLogService.detail 同口径);
     * 再判文件级可见性,最后按元数据记录的 storage_type 取端口;内容流由调用方负责关闭。
     */
    public StorageDownload download(Long fileId) {
        SysFile file = requireFile(fileId);
        if (!accessPolicy.canRead(file, LoginUserContextHolder.get())) {
            throw BizException.forbidden("无权访问该文件");
        }
        return new StorageDownload(file, requirePortOf(file).get(file.getStorageKey()));
    }

    /**
     * 删除:入口已挂 system:file:delete 权限码,数据面再按人收口——
     * 租户超管(语义 A 的 isSuperAdmin 尺)可删本租户任意文件,其余持码人只能删自己上传的;
     * 逻辑删即时生效;物理删按记录类型路由,失败或后端未配置只告警,残留由 GC 任务清扫。
     */
    public void remove(Long fileId) {
        SysFile file = requireFile(fileId);
        LoginUser user = LoginUserContextHolder.get();
        boolean owner = user != null && Objects.equals(file.getUploadBy(), user.userId());
        if (!owner && !isSuperAdmin(user == null ? null : user.userId())) {
            throw BizException.forbidden("只能删除本人上传的文件");
        }
        removeRow(file);
    }

    /**
     * 当前登录人是否租户超管:沿用 UserPermissionService.isSuperAdmin(role_code=admin 且 is_system=1),
     * 不用 roleCode 裸判——自建租户可以造一个名叫 admin 的普通角色,宽一档口径已在该模块被移除过一次。
     */
    private boolean isSuperAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).distinct().toList();
        if (roleIds.isEmpty()) {
            return false;
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .anyMatch(UserPermissionService::isSuperAdmin);
    }

    /** 已知行直接删:头像替换传入的是刚查出的旧行,并发重复删天然幂等,不必再做存在性检查 */
    private void removeRow(SysFile file) {
        fileMapper.deleteById(file.getFileId());
        storageRegistry.of(file.getStorageType()).ifPresentOrElse(
                port -> deleteObjectQuietly(port, file.getStorageKey(), "逻辑删除后清理"),
                () -> log.warn("文件 {} 所属存储后端 {} 未配置,对象留待 GC 清扫",
                        file.getFileId(), file.getStorageType()));
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

    /** 两个分页面共用同一谓词,差别只在调用方套不套 withoutTenant 窗口 */
    private PageResult<FileVO> doPage(FileQuery query) {
        Page<SysFile> page = PageAssembler.from(query);
        String fileName = trimToNull(query.fileName());
        String bizType = trimToNull(query.bizType());
        String fileType = trimToNull(query.fileType());
        IPage<SysFile> result = fileMapper.selectPage(page, Wrappers.<SysFile>lambdaQuery()
                .like(fileName != null, SysFile::getFileName, fileName)
                .eq(bizType != null, SysFile::getBizType, bizType)
                .eq(fileType != null, SysFile::getFileType, fileType)
                .orderByDesc(SysFile::getFileId));
        return PageResult.of(result.getRecords().stream().map(FileVO::from).toList(),
                result.getTotal());
    }

    private StoragePort requireActivePort() {
        return storageRegistry.active().orElseThrow(
                () -> BizException.badRequest(STORAGE_NOT_CONFIGURED));
    }

    private StoragePort requirePortOf(SysFile file) {
        return storageRegistry.of(file.getStorageType()).orElseThrow(
                () -> BizException.badRequest(BACKEND_NOT_CONFIGURED + file.getStorageType()));
    }

    /** 不存在、已逻辑删、属他租户三种情况在这一句里同口径——租户拦截器已把它们都读成空 */
    private SysFile requireFile(Long fileId) {
        SysFile file = fileMapper.selectById(fileId);
        if (file == null) {
            throw BizException.badRequest("文件不存在");
        }
        return file;
    }

    /** 回收对象:失败只告警,原异常继续抛,补偿不掩盖真实失败原因 */
    private void deleteObjectQuietly(StoragePort port, String storageKey, String reason) {
        try {
            port.delete(storageKey);
        } catch (Exception e) {
            log.warn("对象 {} 清理失败({}),残留等待 GC 任务清扫: {}", storageKey, reason, e.getMessage());
        }
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

    /**
     * bizType 收口:空归 null;超长或字符集外(仅允许字母数字与 _ : -)按业务拒绝——
     * 该值会进查询条件与元数据落库,不做白名单会变成任意字符串垃圾场。
     */
    private static String normalizeBizType(String bizType) {
        String value = trimToNull(bizType);
        if (value == null) {
            return null;
        }
        if (value.length() > BIZ_TYPE_MAX_LENGTH || !BIZ_TYPE_PATTERN.matcher(value).matches()) {
            throw BizException.badRequest("bizType 仅允许字母数字与 _ : - ,长度不超过 " + BIZ_TYPE_MAX_LENGTH);
        }
        return value;
    }

    /**
     * fileType 收口:空归缺省值(平台文件 platform),超长按业务拒绝;
     * 合法取值域由前端下拉(内置字典 sys_file_type)圈定,这里只守列宽。
     */
    private static String normalizeFileType(String fileType) {
        String value = trimToNull(fileType);
        if (value == null) {
            return DEFAULT_FILE_TYPE;
        }
        if (value.length() > FILE_TYPE_MAX_LENGTH) {
            throw BizException.badRequest("fileType 长度不超过 " + FILE_TYPE_MAX_LENGTH);
        }
        return value;
    }
}
