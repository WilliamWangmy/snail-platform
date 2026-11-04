package com.snail.platform.function.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.snail.platform.framework.core.context.LoginUser;
import com.snail.platform.framework.core.context.LoginUserContextHolder;
import com.snail.platform.framework.core.exception.BizException;
import com.snail.platform.function.notify.service.NotifyPushService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 职责:NotifyPushService 的注册表与载荷口径单测——不依赖网络与 Redis。
 * 口径说明:emitter 的 onCompletion/onError 回调由 servlet 容器异步驱动,离线单测里
 * complete() 不会触发摘除,摘除路径的端到端验证归集成流(NotifyFlowTest),这里只锁
 * 注册计数、未登录拒绝、推送鲁棒性与载荷序列化往返。
 */
class NotifyPushServiceTest {

    private final NotifyPushService service = new NotifyPushService(new ObjectMapper());

    @AfterEach
    void tearDown() throws Exception {
        LoginUserContextHolder.clear();
        service.destroy();
    }

    @Test
    void distinctUserIdsDedupesAndFiltersNull() {
        assertThat(NotifyPushService.distinctUserIds(null)).isEmpty();
        // Arrays.asList 允许 null 元素;List.of 不允许,与生产入参形态一致
        assertThat(NotifyPushService.distinctUserIds(Arrays.asList(1L, null, 2L, 1L)))
                .containsExactly(1L, 2L);
    }

    @Test
    void subscribeWithoutLoginUserIsRejected() {
        assertThatThrownBy(service::subscribe).isInstanceOf(BizException.class);
    }

    @Test
    void subscribeCountsConnectionsPerUser() {
        LoginUserContextHolder.set(LoginUser.minimal(7L, 1L, "u7", null));
        service.subscribe();
        assertThat(service.onlineConnections()).isEqualTo(1);

        // 同一用户第二条连接(多标签页/多端)独立计数
        service.subscribe();
        assertThat(service.onlineConnections()).isEqualTo(2);
    }

    @Test
    void pushLocalSurvivesUnknownUsersAndOfflineEmitters() {
        LoginUserContextHolder.set(LoginUser.minimal(7L, 1L, "u7", null));
        service.subscribe();

        // 未注册用户与未初始化(离线缓冲态)连接都不产生异常,推送是尽力而为
        assertThatCode(() -> service.pushLocal(
                new NotifyPushService.PushPayload(1L, "标题", "workflow", List.of(7L, 999L))))
                .doesNotThrowAnyException();
        assertThat(service.onlineConnections()).isEqualTo(1);
    }

    @Test
    void payloadRoundTripsThroughJackson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        NotifyPushService.PushPayload payload =
                new NotifyPushService.PushPayload(42L, "流程已通过", "workflow", List.of(7L, 8L));
        String json = mapper.writeValueAsString(payload);
        NotifyPushService.PushPayload parsed =
                mapper.readValue(json, NotifyPushService.PushPayload.class);
        assertThat(parsed.messageId()).isEqualTo(42L);
        assertThat(parsed.title()).isEqualTo("流程已通过");
        assertThat(parsed.bizType()).isEqualTo("workflow");
        assertThat(parsed.userIds()).containsExactly(7L, 8L);
    }
}
