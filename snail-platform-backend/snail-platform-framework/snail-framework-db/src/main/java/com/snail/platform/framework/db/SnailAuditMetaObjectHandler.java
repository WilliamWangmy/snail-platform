package com.snail.platform.framework.db;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.MetaObject;

/**
 * 职责:审计四件套统一自动填充——insert 补创建与修改两组时间和操作人,update 只动修改侧;
 * 业务代码不允许手填审计字段。操作人取当前登录人快照,取不到时留空(系统级线程写入)。
 * 上游:framework-db 实体基类上的填充注解;下游:各业务表审计列。
 * 口径:MP 的填充只认实体,不认条件构造器——凡走 update(Wrapper) 的旁路写法都要传一个空实体
 * (如 {@code userMapper.update(new SysUser(), wrapper)}),否则 metaObject 里没有可填的对象,
 * update_time/update_by 会静默停在旧值;全仓不允许出现 update(null, wrapper)。
 */
public class SnailAuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        Long actor = LoginUserContextHolder.userId();
        if (actor != null) {
            strictInsertFill(metaObject, "createBy", Long.class, actor);
            strictInsertFill(metaObject, "updateBy", Long.class, actor);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        Long actor = LoginUserContextHolder.userId();
        if (actor != null) {
            strictUpdateFill(metaObject, "updateBy", Long.class, actor);
        }
    }
}
