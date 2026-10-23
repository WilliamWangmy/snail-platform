package com.snail.platform.framework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.framework.db.PageAssembler;
import com.snail.platform.function.system.log.dto.OperLogQuery;
import com.snail.platform.function.system.user.dto.UserQuery;
import com.snail.platform.function.system.user.entity.SysUser;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 职责:PageAssembler 单点装配与排序收口的纯单测——不连库，只看装配值与白名单拒绝语义。
 * 上游:约定 §7(分页装配唯一入口、排序只准走白名单);下游:无。
 * 口径:排序命中后仅断 ORDER BY 片段生成(列名解析靠 TableInfoHelper 手工初始化 lambda 缓存);
 * 深翻保护、排序端到端断言留给首个真接排序的域以 HTTP 方式覆盖。
 */
class PageAssemblerTest {

    private static final Map<String, SFunction<SysUser, ?>> SORTABLE =
            Map.of("createTime", SysUser::getCreateTime, "userId", SysUser::getUserId);

    @BeforeAll
    static void initLambdaCache() {
        // 不建 Spring 上下文，手工注册表信息，供 Lambda 列名解析使用
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    @Test
    void fromAppliesPerDomainDefaults() {
        Page<SysUser> crud = PageAssembler.from(new UserQuery(null, null, null, null, null, null));
        assertEquals(1L, crud.getCurrent());
        assertEquals(10L, crud.getSize());

        Page<SysUser> log = PageAssembler.from(
                new OperLogQuery(null, null, null, null, null, null, null, null, null));
        assertEquals(1L, log.getCurrent());
        assertEquals(20L, log.getSize());
    }

    @Test
    void blankSortIsNoop() {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.lambdaQuery();
        assertDoesNotThrow(() -> PageAssembler.applySort(wrapper, null, SORTABLE));
        assertDoesNotThrow(() -> PageAssembler.applySort(wrapper, "  ", SORTABLE));
        assertEquals("", wrapper.getSqlSegment());
    }

    @Test
    void whitelistedSortAppendsOrderBy() {
        LambdaQueryWrapper<SysUser> desc = Wrappers.lambdaQuery();
        PageAssembler.applySort(desc, "createTime", SORTABLE);
        assertTrue(desc.getSqlSegment().contains("ORDER BY"), "缺省方向应拼出 ORDER BY: " + desc.getSqlSegment());
        assertTrue(desc.getSqlSegment().contains("DESC"), "缺省方向是 DESC: " + desc.getSqlSegment());

        LambdaQueryWrapper<SysUser> asc = Wrappers.lambdaQuery();
        PageAssembler.applySort(asc, "userId:asc", SORTABLE);
        assertTrue(asc.getSqlSegment().contains("ORDER BY"), "显式 asc 应拼出 ORDER BY: " + asc.getSqlSegment());
        assertTrue(!asc.getSqlSegment().contains("DESC"), "asc 不应带 DESC: " + asc.getSqlSegment());
    }

    @Test
    void nonWhitelistFieldAndBadDirectionRejected() {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.lambdaQuery();
        BizException unknown = assertThrows(BizException.class,
                () -> PageAssembler.applySort(wrapper, "password", SORTABLE));
        assertTrue(unknown.getMessage().contains("白名单"), "拒绝文案应点明白名单: " + unknown.getMessage());

        assertThrows(BizException.class, () -> PageAssembler.applySort(wrapper, "createTime:sideways", SORTABLE));
    }
}
