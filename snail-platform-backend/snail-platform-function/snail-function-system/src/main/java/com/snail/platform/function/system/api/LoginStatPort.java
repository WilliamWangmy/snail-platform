package com.snail.platform.function.system.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 职责:登录统计只读端口——跨模块取登录趋势与失败计数的唯一合法出口,跨模块不得再触达 log 子域内部。
 * 上游:monitor(总览登录趋势、login.fail.5m 键);下游:log 子域实现(LoginLogStatService,读 sys_login_log)。
 * 口径:sys_login_log 在租户拦截器豁免清单,本端口统计口径是平台全量(不区分租户,监控平台口径);
 * status=0 记失败(login_log 的 1 成功/0 失败语义);区间为左闭右开 [begin, end)。
 */
public interface LoginStatPort {

    /**
     * 按自然日聚合登录次数与失败次数,日序升序;窗口内无登录的日期缺席返回,由消费方补 0。
     *
     * @param begin 起始日(含)
     * @param end   结束日(不含)
     */
    List<DayCount> dailyCounts(LocalDate begin, LocalDate end);

    /** 自指定时刻起(含)的失败登录总数,login.fail.5m 键用 */
    long failCountSince(LocalDateTime since);

    /**
     * 单日登录统计快照:日期、总数、失败数;日期为统计的自然日。
     */
    record DayCount(
            /** 统计日 */
            LocalDate date,
            /** 登录总数 */
            long total,
            /** 失败数(status=0) */
            long fail) {
    }
}
