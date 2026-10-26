package com.snail.platform.function.storage.service;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.security.authority.AuthorityChecker;
import com.snail.platform.function.storage.entity.SysFile;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 职责:文件可见性的默认口径——上传者本人、租户管理员、持有文件管理权限者可以读取。
 * 上游:FileAccessPolicy;下游:AuthorityChecker 与登录人快照里的角色标识。
 * 口径:租户内"能列文件"不等于"能读文件";业务附件需要更宽可见性时,
 * 由业务模块提供 @Primary 的 FileAccessPolicy 覆盖,而不是放宽本类。
 */
@Component
public class DefaultFileAccessPolicy implements FileAccessPolicy {

    /** 文件管理权限码:视为可读全租户文件的标记 */
    private static final String FILE_MANAGE = "system:file:delete";

    /** 内置租户管理员的角色标识 */
    private static final String TENANT_ADMIN = "admin";

    @Override
    public boolean canRead(SysFile file, LoginUser user) {
        if (file == null || user == null || user.userId() == null) {
            return false;
        }
        if (Objects.equals(file.getUploadBy(), user.userId())) {
            return true;
        }
        return isTenantAdmin(user) || AuthorityChecker.has(FILE_MANAGE);
    }

    private boolean isTenantAdmin(LoginUser user) {
        return user.roles().stream().anyMatch(role -> TENANT_ADMIN.equals(role.roleCode()));
    }
}
