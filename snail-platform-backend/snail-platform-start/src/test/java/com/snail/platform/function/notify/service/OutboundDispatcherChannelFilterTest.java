package com.snail.platform.function.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.snail.platform.function.notify.entity.SysNotifyOutbound;
import com.snail.platform.function.notify.mapper.SysNotifyOutboundMapper;
import com.snail.platform.function.notify.service.OutboundChannel.OutboundMessage;
import com.snail.platform.function.notify.service.OutboundChannel.OutboundTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 职责:投递器通道集快照过滤的口径单测——null 全通道广播、非空精确投递、
 * 声明通道全不可用仍落 log 兜底行。用内存双打通道与 mock 落库器,不依赖 Spring 上下文;
 * 投递线程 500ms 轮询,断言前按落库行数等待,再留一个轮询周期的宽限核对"未投"断言。
 */
class OutboundDispatcherChannelFilterTest {

    /** 轮询 500ms + 单 job 处理余量;宽限至少盖过一个轮询周期,"未投"断言才站得住 */
    private static final long AWAIT_MS = 2000;
    private static final long GRACE_MS = 600;

    private final SysNotifyOutboundMapper outboundMapper = mock(SysNotifyOutboundMapper.class);

    private OutboundDispatcher dispatcher;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (dispatcher != null) {
            dispatcher.destroy();
        }
    }

    @Test
    void declaredChannelsDispatchOnlyDeclared() throws Exception {
        RecordingChannel mail = new RecordingChannel(OutboundChannel.CHANNEL_MAIL, true);
        RecordingChannel sse = new RecordingChannel(OutboundChannel.CHANNEL_SSE, true);
        List<SysNotifyOutbound> rows = stubOutboundRows();
        dispatcher = new OutboundDispatcher(providerOf(List.of(mail, sse)), outboundMapper);

        dispatcher.dispatchAfterCommit(job(Set.of(OutboundChannel.CHANNEL_MAIL)));
        awaitRows(rows, 1);
        Thread.sleep(GRACE_MS);

        assertThat(mail.sent).hasSize(1);
        assertThat(sse.sent).isEmpty();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getChannel()).isEqualTo(OutboundChannel.CHANNEL_MAIL);
            assertThat(row.getStatus()).isEqualTo(1);
        });
    }

    @Test
    void nullChannelsBroadcastToAllAvailable() throws Exception {
        RecordingChannel mail = new RecordingChannel(OutboundChannel.CHANNEL_MAIL, true);
        RecordingChannel sse = new RecordingChannel(OutboundChannel.CHANNEL_SSE, true);
        List<SysNotifyOutbound> rows = stubOutboundRows();
        dispatcher = new OutboundDispatcher(providerOf(List.of(mail, sse)), outboundMapper);

        dispatcher.dispatchAfterCommit(job(null));
        awaitRows(rows, 2);

        assertThat(mail.sent).hasSize(1);
        assertThat(sse.sent).hasSize(1);
        assertThat(rows).extracting(SysNotifyOutbound::getChannel)
                .containsExactlyInAnyOrder(OutboundChannel.CHANNEL_MAIL, OutboundChannel.CHANNEL_SSE);
    }

    @Test
    void declaredButUnavailableFallsBackToLogRow() throws Exception {
        RecordingChannel mail = new RecordingChannel(OutboundChannel.CHANNEL_MAIL, true);
        List<SysNotifyOutbound> rows = stubOutboundRows();
        dispatcher = new OutboundDispatcher(providerOf(List.of(mail)), outboundMapper);

        dispatcher.dispatchAfterCommit(job(Set.of(OutboundChannel.CHANNEL_SMS)));
        awaitRows(rows, 1);
        Thread.sleep(GRACE_MS);

        assertThat(mail.sent).isEmpty();
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getChannel()).isEqualTo("log");
            assertThat(row.getStatus()).isEqualTo(1);
        });
    }

    /* ------------------------------ 辅助 ------------------------------ */

    private static OutboundDispatcher.DispatchJob job(Set<String> channels) {
        return new OutboundDispatcher.DispatchJob(1L, 1L, "标题", "正文", "workflow",
                List.of(new OutboundTarget(9L, "a@b.c", null)), channels, null);
    }

    private static ObjectProvider<OutboundChannel> providerOf(List<OutboundChannel> channels) {
        return new ObjectProvider<>() {
            @Override
            public Stream<OutboundChannel> stream() {
                return channels.stream();
            }
        };
    }

    /** 落库行改存内存列表;insert 在真实 mapper 里由 MyBatis 生成,单测只关心行内容 */
    private List<SysNotifyOutbound> stubOutboundRows() {
        List<SysNotifyOutbound> rows = new ArrayList<>();
        when(outboundMapper.insert(any(SysNotifyOutbound.class))).thenAnswer(invocation -> {
            rows.add(invocation.getArgument(0, SysNotifyOutbound.class));
            return 1;
        });
        return rows;
    }

    private static void awaitRows(List<SysNotifyOutbound> rows, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (rows.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(rows).hasSize(expected);
    }

    /** 内存通道双打:按名字与可用性参与过滤,send 只记录不外发 */
    private static final class RecordingChannel implements OutboundChannel {

        private final String name;
        private final boolean available;
        private final List<OutboundMessage> sent = new ArrayList<>();

        private RecordingChannel(String name, boolean available) {
            this.name = name;
            this.available = available;
        }

        @Override
        public String channel() {
            return name;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void send(OutboundMessage message) {
            sent.add(message);
        }
    }
}
