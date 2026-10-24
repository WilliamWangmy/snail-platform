package com.snail.platform.function.notify.controller;

import com.snail.platform.framework.core.response.PageResult;
import com.snail.platform.framework.core.response.R;
import com.snail.platform.function.notify.dto.NotifyInboxItemVO;
import com.snail.platform.function.notify.dto.NotifyInboxQuery;
import com.snail.platform.function.notify.service.NotifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 职责:收件箱入口——本人的收件行读与已读状态维护。除列表页入口挂 system:notify:list 外,
 * 其余端点只要求登录:收件箱是私人数据,服务层强制本人谓词,按钮权限码对"读自己的消息"没有增量价值。
 * 已读/移除是自助读态操作,不标 @OperLog——它们不是管理动作,留痕会污染审计面。
 * 上游:收件箱页面与顶栏未读铃铛;下游:NotifyService。
 */
@Tag(name = "通知中心")
@RestController
@RequestMapping("/system/notify")
public class NotifyController {

    /** 通知服务 */
    private final NotifyService notifyService;

    public NotifyController(NotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @PreAuthorize("hasAuthority('system:notify:list')")
    @Operation(summary = "收件箱分页(本人)")
    @GetMapping("/page")
    public R<PageResult<NotifyInboxItemVO>> page(@Valid NotifyInboxQuery query) {
        return R.ok(notifyService.inbox(query));
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "本人未读数(顶栏铃铛轮询)")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(notifyService.unreadCount());
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "单条详情(须为本人收件行)")
    @GetMapping("/{recipientId}")
    public R<NotifyInboxItemVO> detail(@PathVariable Long recipientId) {
        return R.ok(notifyService.detail(recipientId));
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "标记已读")
    @PutMapping("/{recipientId}/read")
    public R<Void> read(@PathVariable Long recipientId) {
        notifyService.markRead(recipientId);
        return R.ok(null);
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "全部已读")
    @PutMapping("/read-all")
    public R<Void> readAll() {
        notifyService.markAllRead();
        return R.ok(null);
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "移出收件箱")
    @DeleteMapping("/{recipientId}")
    public R<Void> remove(@PathVariable Long recipientId) {
        notifyService.remove(recipientId);
        return R.ok(null);
    }
}
