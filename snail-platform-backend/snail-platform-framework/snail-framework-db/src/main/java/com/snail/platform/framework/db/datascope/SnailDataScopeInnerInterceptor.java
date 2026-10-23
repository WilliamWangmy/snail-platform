package com.snail.platform.framework.db.datascope;

import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.ResultHandler;

/**
 * 职责:数据权限谓词的 SQL 注入点——线程窗口里有 DataScopeFilter 时,把 "(org IN (...) OR create_by = 本人)"
 * AND 进 SELECT 的 WHERE;四档并集算法在业务判定服务,这里只负责机械拼接,不认任何表名与业务语义。
 * 装配顺序固定在租户之后、分页之前:分页 count 会先于 beforeQuery 逐序消费改写后的 SQL,顺序换了 count 就漏权限。
 * 只改写单表 PlainSelect;UNION/JOIN 形态原样放过——权限口径下放到消费层,业务库单据接进来时再扩。
 * 上游:MybatisPlusConfig;下游:开窗的业务列表查询产生的 SELECT。
 */
public class SnailDataScopeInnerInterceptor implements InnerInterceptor {

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds,
                            ResultHandler resultHandler, BoundSql boundSql) {
        if (InterceptorIgnoreHelper.willIgnoreDataPermission(ms.getId())) {
            return;
        }
        DataScopeFilter filter = DataScopeContextHolder.get();
        if (filter == null || filter.isEmpty()) {
            return;
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(boundSql.getSql());
        } catch (Exception e) {
            throw new IllegalStateException("数据权限无法解析查询 SQL: " + ms.getId(), e);
        }
        if (!(statement instanceof PlainSelect select)) {
            return;
        }
        try {
            Expression scope = CCJSqlParserUtil.parseCondExpression(buildPredicate(filter));
            Expression where = select.getWhere();
            select.setWhere(where == null ? scope : new AndExpression(where, scope));
            PluginUtils.mpBoundSql(boundSql).sql(select.toString());
        } catch (Exception e) {
            throw new IllegalStateException("数据权限谓词注入失败: " + ms.getId(), e);
        }
    }

    /** 两臂 OR 并集;ID 全部来自库内 BIGINT,拼串无注入面 */
    private String buildPredicate(DataScopeFilter filter) {
        List<String> arms = new ArrayList<>(2);
        if (filter.orgIds() != null && !filter.orgIds().isEmpty()) {
            String ids = filter.orgIds().stream().map(String::valueOf).collect(Collectors.joining(","));
            arms.add(filter.orgColumn() + " IN (" + ids + ")");
        }
        if (filter.creatorId() != null) {
            arms.add(filter.creatorColumn() + " = " + filter.creatorId());
        }
        return "(" + String.join(" OR ", arms) + ")";
    }
}
