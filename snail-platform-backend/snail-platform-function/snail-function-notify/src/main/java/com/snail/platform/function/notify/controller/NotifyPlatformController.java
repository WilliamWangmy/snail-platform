package com.snail.platform.function.notify.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.framework.log.BusinessType;
import com.snail.platform.framework.log.OperLog;
import com.snail.platform.function.notify.dto.AnnouncementQuery;
import com.snail.platform.function.notify.dto.AnnouncementSendRequest;
import com.snail.platform.function.notify.dto.AnnouncementVO;
import com.snail.platform.function.notify.service.NotifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:平台公告入口——跨租户公告检索与向指定租户全员发信。
 * 发送是面向全员的管理动作,必须留痕;扇出规模与租户状态在服务端校验。
 * 上游:公告管理页面;下游:NotifyService。
 */
@Tag(name = "公告管理")
@RestController
@RequestMapping("/platform/notify")
public class NotifyPlatformController {

    /** 通知服务 */
    private final NotifyService notifyService;

    public NotifyPlatformController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @PreAuthorize("hasAuthority('platform:notify:list')")
    @Operation(summary = "公告分页(跨租户)")
    @GetMapping("/page")
    public R<PageResult<AnnouncementVO>> page(@Valid AnnouncementQuery query) {
        return R.ok(notifyService.announcements(query));
    }

    @PreAuthorize("hasAuthority('platform:notify:send')")
    @Operation(summary = "向指定租户全员发送公告")
    @OperLog(module = "公告管理", businessType = BusinessType.INSERT)
    @PostMapping("/announcement")
    public R<Void> announce(@Valid @RequestBody AnnouncementSendRequest request) {
        notifyService.announce(request);
        return R.ok(null);
    }
}
