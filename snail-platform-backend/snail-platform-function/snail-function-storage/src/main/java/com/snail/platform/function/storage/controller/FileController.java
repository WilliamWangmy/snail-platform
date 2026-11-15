package com.snail.platform.function.storage.controller;

import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.storage.dto.FileQuery;
import com.snail.platform.function.storage.dto.FileVO;
import com.snail.platform.function.storage.service.FileStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.util.UriUtils;

/**
 * 职责:租户文件管理入口——上传、本租户分页、流式下载与删除;另含本人头像的上传与读取。
 * 下载要求 system:file:download,文件级可见性再由 FileAccessPolicy 判定;
 * 上传/删除是写操作挂 system:file:* 权限码并留痕;头像属于自助能力,只要求登录且只作用于本人。
 * 上游:文件管理页面、个人中心;下游:FileStorageService。
 */
@Tag(name = "文件管理")
@RestController
@RequestMapping("/system/file")
public class FileController {

    /** 兜底内容类型:白名单外的扩展名一律按二进制流回 */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** 扩展名 → 响应内容类型:按扩展名映射,不信任客户端上传时声明的值 */
    private static final Map<String, String> EXT_CONTENT_TYPES = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("csv", "text/csv"),
            Map.entry("md", "text/markdown"),
            Map.entry("txt", "text/plain"),
            Map.entry("zip", "application/zip"),
            Map.entry("7z", "application/x-7z-compressed"));

    /** 扩展名分隔符 */
    private static final char EXT_SEPARATOR = '.';

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PreAuthorize("hasAuthority('system:file:upload')")
    @Operation(summary = "上传文件")
    @OperLog(module = "文件管理", businessType = BusinessType.IMPORT)
    @PostMapping("/upload")
    public R<FileVO> upload(@RequestPart("file") MultipartFile file,
                            @RequestParam(required = false) String bizType,
                            @RequestParam(required = false) Long bizId,
                            @RequestParam(required = false) String fileType) {
        return R.ok(fileStorageService.upload(file, bizType, bizId, fileType));
    }

    @PreAuthorize("hasAuthority('system:file:list')")
    @Operation(summary = "文件分页(本租户)")
    @GetMapping("/page")
    public R<PageResult<FileVO>> page(@Valid FileQuery query) {
        return R.ok(fileStorageService.page(query));
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "上传本人头像(文件类型固定 avatar,仅图片,≤2MB)")
    @OperLog(module = "个人中心", businessType = BusinessType.UPDATE)
    @PostMapping("/avatar")
    public R<FileVO> uploadAvatar(@RequestPart("file") MultipartFile file) {
        return R.ok(fileStorageService.uploadAvatar(file));
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "读取本人头像(未设置返回 204)")
    @GetMapping("/avatar")
    public ResponseEntity<StreamingResponseBody> avatar() {
        FileStorageService.StorageDownload download =
                fileStorageService.avatar(LoginUserContextHolder.userId());
        if (download == null) {
            return ResponseEntity.noContent().build();
        }
        StreamingResponseBody body = output -> {
            try (InputStream content = download.content()) {
                content.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        inlineDisposition(download.file().getFileName()))
                .contentType(MediaType.parseMediaType(contentTypeOf(download.file().getFileName())))
                .body(body);
    }

    @PreAuthorize("hasAuthority('system:file:download')")
    @Operation(summary = "下载文件(本租户 + 文件级可见性)")
    @OperLog(module = "文件管理", businessType = BusinessType.EXPORT)
    @GetMapping("/{fileId}/raw")
    public ResponseEntity<StreamingResponseBody> raw(@PathVariable Long fileId) {
        FileStorageService.StorageDownload download = fileStorageService.download(fileId);
        String fileName = download.file().getFileName();
        StreamingResponseBody body = output -> {
            // 流的生命周期覆盖到异步回写结束:复制完随 finally 关闭
            try (InputStream content = download.content()) {
                content.transferTo(output);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(fileName))
                .contentType(MediaType.parseMediaType(contentTypeOf(fileName)))
                .body(body);
    }

    @PreAuthorize("hasAuthority('system:file:delete')")
    @Operation(summary = "删除文件(逻辑删+尽力物理删)")
    @OperLog(module = "文件管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{fileId}")
    public R<Void> remove(@PathVariable Long fileId) {
        fileStorageService.remove(fileId);
        return R.ok(null);
    }

    /** RFC 5987 形态的下载名:filename*=UTF-8'',与前端 http.ts 的双正则解析对齐 */
    private static String contentDisposition(String fileName) {
        return "attachment; filename*=UTF-8''"
                + UriUtils.encode(fileName, StandardCharsets.UTF_8);
    }

    /** 头像走 inline:语义上是页面内图片展示,不是附件下载 */
    private static String inlineDisposition(String fileName) {
        return "inline; filename*=UTF-8''"
                + UriUtils.encode(fileName, StandardCharsets.UTF_8);
    }

    /** 响应类型只认服务端扩展名映射:沿用上传声明会让 text/html 的附件被浏览器当页面渲染 */
    private static String contentTypeOf(String fileName) {
        int dot = fileName.lastIndexOf(EXT_SEPARATOR);
        if (dot < 0) {
            return DEFAULT_CONTENT_TYPE;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return EXT_CONTENT_TYPES.getOrDefault(ext, DEFAULT_CONTENT_TYPE);
    }
}
