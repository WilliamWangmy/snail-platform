package com.snail.platform.function.storage.service;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.security.authority.AuthorityChecker;
import com.snail.platform.function.storage.entity.SysFile;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 职责:文件可见性的默认口径——上传者本人或持文件管理权限者可以读取。
 * 上游:FileAccessPolicy;下游:AuthorityChecker 与登录人快照。
 * 口径:租户内"能列文件"不等于"能读文件";读全租户文件的凭据是权限码而非角色名——
 * 旧口径的"roleCode=admin 即可读"比 UserPermissionService.isSuperAdmin 宽一档
 * (不自建租户可以造一个名叫 admin 的普通角色白拿全租户读权),已移除;
 * 系统内置 admin 由语义 A 隐式持有全部租户面权限码(含 system:file:delete),读权不受影响。
 * 业务附件需要更宽可见性时,由业务模块提供 @Primary 的 FileAccessPolicy 覆盖,而不是放宽本类。
 * 独立的 system:file:read-all 码属后续演进项,当前复用管理码并在种子文档中声明口径。
 */
@Component
public class DefaultFileAccessPolicy implements FileAccessPolicy {

    /** 文件管理权限码:租户内读全量文件的凭据;种子给了 list/upload/download/delete 四把码,暂无独立读码 */
    private static final String FILE_MANAGE = "system:file:delete";

    @Override
    public boolean canRead(SysFile file, LoginUser user) {
        if (file == null || user == null || user.userId() == null) {
            return false;
        }
        if (Objects.equals(file.getUploadBy(), user.userId())) {
            return true;
        }
        return AuthorityChecker.has(FILE_MANAGE);
    }
}
