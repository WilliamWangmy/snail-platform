package com.snail.platform.function.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.snail.platform.framework.db.entity.TenantAuditedEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 职责:sys_file 表 PO。文件元数据,租户托管;对象内容不在库里,经 storage_key 到 StoragePort 取。
 * 上游:framework-db 的 TenantAuditedEntity;下游:storage 模块的 service 与 mapper。
 * 口径:MVP 不算内容摘要(无 sha256 列),文件名是清洗后的原始名,上传人显示名发送时快照落库。
 */
@Getter
@Setter
@TableName("sys_file")
public class SysFile extends TenantAuditedEntity {

    /** 文件 ID */
    @TableId(value = "file_id", type = IdType.AUTO)
    private Long fileId;

    /** 原始文件名,已清洗路径与控制字符 */
    private String fileName;

    /** 小写扩展名,无点 */
    private String ext;

    /** 客户端声明的类型,不信任仅记录 */
    private String contentType;

    /** 字节数 */
    private Long size;

    /** 对象键 {tenantId}/{yyyyMM}/{uuid}.{ext},下载路径永不拼用户输入 */
    private String storageKey;

    /** 存储后端类型:sftp | s3 */
    private String storageType;

    /** 业务域标签:contract/avatar/… */
    private String bizType;

    /** 关联业务 ID */
    private Long bizId;

    /** 上传人用户 ID */
    private Long uploadBy;

    /** 上传人显示名快照,管理页免跨模块读 */
    private String uploadByName;
}
