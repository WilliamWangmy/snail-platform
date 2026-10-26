package com.snail.platform.function.storage.service;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.function.storage.entity.SysFile;

/**
 * 职责:文件级可见性判定端口——权限码只回答"能不能进文件库",本端口回答"这一条能不能读"。
 * 上游:FileStorageService.download 在取内容前调用;下游:默认实现 DefaultFileAccessPolicy,
 * 业务模块出现敏感附件时提供 @Primary 实现覆盖即可,不必改存储主流程。
 * 口径:返回 false 一律按无权限拒绝;本端口不返回 404,存在性仍由租户拦截器保证。
 */
public interface FileAccessPolicy {

    /** 当前登录人能否读取该文件;file/user 任一为空按拒绝处理 */
    boolean canRead(SysFile file, LoginUser user);
}
