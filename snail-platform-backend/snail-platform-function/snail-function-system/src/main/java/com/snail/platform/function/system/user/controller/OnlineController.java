package com.snail.platform.function.system.user.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.system.user.dto.OnlineQuery;
import com.snail.platform.function.system.user.dto.OnlineSessionVO;
import com.snail.platform.function.system.user.service.OnlineSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:在线用户入口——本租户在线会话检索与强制下线。
 * 上游:在线用户页面;下游:OnlineSessionService。
 */
@Tag(name = "在线用户")
@RestController
@RequestMapping("/system/online")
public class OnlineController {

    /** 在线会话服务 */
    private final OnlineSessionService onlineSessionService;

    public OnlineController(OnlineSessionService onlineSessionService) {
        this.onlineSessionService = onlineSessionService;
    }

    @PreAuthorize("hasAuthority('system:online:list')")
    @Operation(summary = "在线用户分页(本租户)")
    @GetMapping("/page")
    public R<PageResult<OnlineSessionVO>> page(@Valid OnlineQuery query) {
        return R.ok(onlineSessionService.page(query));
    }

    @PreAuthorize("hasAuthority('system:online:kick')")
    @Operation(summary = "强制下线(令牌版本自增,全设备生效)")
    @OperLog(module = "在线用户", businessType = BusinessType.UPDATE)
    @PostMapping("/{userId}/kick")
    public R<Void> kick(@PathVariable Long userId) {
        onlineSessionService.kick(userId);
        return R.ok(null);
    }
}
