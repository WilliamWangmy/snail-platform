package com.snail.platform.function.system.log.service;

import com.snail.platform.function.system.api.LoginStatPort;
import com.snail.platform.function.system.log.mapper.SysLoginLogMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 职责:LoginStatPort 的 log 子域实现——登录趋势日聚合与失败计数,只读 sys_login_log。
 * 上游:LoginStatPort 契约(monitor 模块消费);下游:SysLoginLogMapper 注解 SQL。
 * 口径:平台全量(本表在租户豁免清单,平台监控口径不区分租户);失败=status=0;
 * 无登录的日期缺席返回,消费方(总览趋势)自行补 0。
 */
@Service
public class LoginLogStatService implements LoginStatPort {

    /** 登录日志读写 */
    private final SysLoginLogMapper loginLogMapper;

    public LoginLogStatService(SysLoginLogMapper loginLogMapper) {
        this.loginLogMapper = loginLogMapper;
    }

    @Override
    public List<DayCount> dailyCounts(LocalDate begin, LocalDate end) {
        return loginLogMapper.selectDailyCounts(begin, end).stream()
                .map(row -> new DayCount(row.getDay(),
                        row.getTotal() == null ? 0L : row.getTotal(),
                        row.getFail() == null ? 0L : row.getFail()))
                .toList();
    }

    @Override
    public long failCountSince(LocalDateTime since) {
        return loginLogMapper.countFailsSince(since);
    }
}
