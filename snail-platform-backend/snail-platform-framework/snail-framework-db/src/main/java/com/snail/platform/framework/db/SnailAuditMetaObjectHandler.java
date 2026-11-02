package com.snail.platform.framework.db;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import java.time.LocalDateTime;
import org.apache.ibatis.reflection.MetaObject;

/**
 * 职责:审计四件套统一自动填充——insert 补创建与修改两组时间和操作人,update 只动修改侧;
 * 业务代码不允许手填审计字段。操作人取当前登录人快照,取不到时留空(系统级线程写入)。
 * 上游:framework-db 实体基类上的填充注解;下游:各业务表审计列。
/**
 * 职责:审计四件套统一自动填充——insert 补创建与修改两组时间和操作人,update 只动修改侧;
 * 业务代码不允许手填审计字段。操作人取当前登录人快照,取不到时留空(系统级线程写入)。
 * 口径:填充用 setFieldValByName 强制覆盖而非 strictFill——strictFill 只填空位,
 * 请求体直绑 PO 时伪造的 createBy/updateBy/createTime 会顶掉自动填充,审计可信度即被破坏;
 * 审计列的值只能来自本处理器与登录上下文,不接受实体自带值。
 * 上游:framework-db 实体基类上的填充注解;下游:各业务表审计列。
 * 边界:MP 的填充只认实体,不认条件构造器——凡走 update(Wrapper) 的旁路写法都要传一个空实体
 * (如 {@code userMapper.update(new SysUser(), wrapper)}),否则 metaObject 里没有可填的对象,
 * update_time/update_by 会静默停在旧值;全仓不允许出现 update(null, wrapper)。
 */
public class SnailAuditMetaObjectHandler implements MetaObjectHandler {

    /** insert:补创建/修改时间;有登录人时再补创建人/修改人——一律强制覆盖,实体自带值不作数 */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        setFieldValByName("createTime", now, metaObject);
        setFieldValByName("updateTime", now, metaObject);
        Long actor = LoginUserContextHolder.userId();
        if (actor != null) {
            setFieldValByName("createBy", actor, metaObject);
            setFieldValByName("updateBy", actor, metaObject);
        }
    }

    /** update:只动修改侧;条件构造器写法必须传空实体,否则这里填不到值 */
    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        Long actor = LoginUserContextHolder.userId();
        if (actor != null) {
            setFieldValByName("updateBy", actor, metaObject);
        }
    }
}
