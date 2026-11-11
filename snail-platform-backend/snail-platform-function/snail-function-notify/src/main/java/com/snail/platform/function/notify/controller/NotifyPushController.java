package com.snail.platform.function.notify.controller;

import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.notify.SnailNotifyProperties;
import com.snail.platform.function.notify.service.NotifyPushService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 职责:站内消息实时推送的订阅入口——SSE 长连接,鉴权与普通端点同口径(JWT 头),
 * 前端经 fetch 流式读取(EventSource 带不了自定义头),断线重连由前端自行换新请求。
 * 总开关 snail.notify.push.enabled 关闭时按"不存在"拒绝,前端降级回 30s 轮询。
 * 上游:两端顶栏与收件箱页;下游:NotifyPushService(注册表与推送单点)。
 */
@Tag(name = "通知中心")
@RestController
@RequestMapping("/system/notify")
public class NotifyPushController {

    /** 推送总开关,与 SseOutboundChannel 的 available 同源 */
    private final boolean enabled;

    /** SSE 会话与推送单点 */
    private final NotifyPushService pushService;

    public NotifyPushController(NotifyPushService pushService, SnailNotifyProperties props) {
        this.pushService = pushService;
        this.enabled = props.getPush().isEnabled();
    }

    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "订阅站内消息实时推送(SSE 流)")
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        if (!enabled) {
            throw BizException.badRequest("实时推送未开启");
        }
        return pushService.subscribe();
    }
}
