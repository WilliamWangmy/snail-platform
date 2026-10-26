package com.snail.platform.function.storage.dto;

import com.snail.platform.function.storage.entity.SysFile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

/**
 * 职责:文件出参对象。管理页与下载页面的唯一投影,不带对象键(内部布局,前端无消费场景)。
 * 上游:FileStorageService 组装;下游:文件管理页面、平台文件页面。
 */
@Schema(description = "文件信息")
public record FileVO(

        @Schema(description = "文件 ID")
        Long fileId,

        @Schema(description = "租户 ID")
        Long tenantId,

        @Schema(description = "文件名,已清洗路径与控制字符")
        String fileName,

        @Schema(description = "小写扩展名,无点")
        String ext,

        @Schema(description = "客户端声明的类型,不信任仅记录")
        String contentType,

        @Schema(description = "字节数")
        Long size,

        @Schema(description = "存储后端类型:sftp | s3")
        String storageType,

        @Schema(description = "业务域标签")
        String bizType,

        @Schema(description = "关联业务 ID")
        Long bizId,

        @Schema(description = "上传人用户 ID")
        Long uploadBy,

        @Schema(description = "上传人显示名快照")
        String uploadByName,

        @Schema(description = "上传时间")
        LocalDateTime createTime) {

    public static FileVO from(SysFile file) {
        return new FileVO(file.getFileId(), file.getTenantId(), file.getFileName(), file.getExt(),
                file.getContentType(), file.getSize(), file.getStorageType(), file.getBizType(),
                file.getBizId(), file.getUploadBy(), file.getUploadByName(), file.getCreateTime());
    }
}
