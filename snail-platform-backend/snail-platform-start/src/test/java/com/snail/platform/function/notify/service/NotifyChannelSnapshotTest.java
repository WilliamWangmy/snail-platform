package com.snail.platform.function.notify.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 职责:出站通道集快照(outboundChannelsOf)的口径单测——null 保持全通道广播、
 * 落收件行(含 inner)才附加 sse、inner 本身不进快照。不依赖 Spring 与数据库。
 */
class NotifyChannelSnapshotTest {

    @Test
    void nullChannelsKeepsCurrentBroadcast() {
        assertThat(NotifyService.outboundChannelsOf(null)).isNull();
    }

    @Test
    void innerOnlyDegradesToSse() {
        // 只勾站内信:没有站外出站,但铃铛实时推送必须还在
        assertThat(NotifyService.outboundChannelsOf(Set.of(OutboundChannel.CHANNEL_INNER)))
                .containsExactly(OutboundChannel.CHANNEL_SSE);
    }

    @Test
    void innerAppendsSseForPersistedRows() {
        assertThat(NotifyService.outboundChannelsOf(Set.of(OutboundChannel.CHANNEL_INNER, OutboundChannel.CHANNEL_MAIL)))
                .containsExactlyInAnyOrder(OutboundChannel.CHANNEL_SSE, OutboundChannel.CHANNEL_MAIL);
    }

    @Test
    void mailOnlyKeepsSseOut() {
        // 不落收件行就不推铃铛:消息不在任何收件箱里,推了也无处可点
        assertThat(NotifyService.outboundChannelsOf(Set.of(OutboundChannel.CHANNEL_MAIL)))
                .containsExactly(OutboundChannel.CHANNEL_MAIL);
    }

    @Test
    void innerItselfNeverEntersOutboundSet() {
        assertThat(NotifyService.outboundChannelsOf(Set.of(OutboundChannel.CHANNEL_INNER, "unknown")))
                .containsExactly(OutboundChannel.CHANNEL_SSE, "unknown");
    }
}
