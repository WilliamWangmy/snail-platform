package com.snail.platform.function.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.snail.platform.function.external.api.SmsPort;
import com.snail.platform.function.notify.service.OutboundChannel.OutboundMessage;
import com.snail.platform.function.notify.service.OutboundChannel.OutboundTarget;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 职责:短信出站通道的可用性与寻址单测(方案 D8)——缺模板编码降级不投、端口缺席降级、
 * 空手机号静默缺席、逐号发送与模板变量透传。mock SmsPort,不依赖 Spring 与真实厂商。
 */
class SmsOutboundChannelTest {

    private final SmsPort smsPort = mock(SmsPort.class);

    @Test
    void unavailableWhenTemplateBlank() {
        when(smsPort.available()).thenReturn(true);
        assertThat(new SmsOutboundChannel(providerOf(smsPort), "  ").available()).isFalse();
    }

    @Test
    void unavailableWhenPortAbsent() {
        assertThat(new SmsOutboundChannel(providerOf(null), "SMS_TODO").available()).isFalse();
    }

    @Test
    void availableWhenPortReadyAndTemplateConfigured() {
        when(smsPort.available()).thenReturn(true);
        assertThat(new SmsOutboundChannel(providerOf(smsPort), "SMS_TODO").available()).isTrue();
    }

    @Test
    void sendSkipsBlankPhonesAndSendsEachNumber() throws Exception {
        when(smsPort.available()).thenReturn(true);
        SmsOutboundChannel channel = new SmsOutboundChannel(providerOf(smsPort), "SMS_TODO");
        OutboundMessage message = new OutboundMessage(42L, "待办提醒", "内容", "workflow",
                List.of(new OutboundTarget(1L, null, "13800000001"),
                        new OutboundTarget(2L, null, null),
                        new OutboundTarget(3L, null, "  "),
                        new OutboundTarget(4L, null, "13800000002")),
                Map.of("processName", "报销流程", "nodeName", "审批", "businessKey", "bk-1"));

        channel.send(message);

        ArgumentCaptor<SmsPort.SmsCommand> captor = ArgumentCaptor.forClass(SmsPort.SmsCommand.class);
        verify(smsPort, org.mockito.Mockito.times(2)).send(captor.capture());
        assertThat(captor.getAllValues()).extracting(SmsPort.SmsCommand::phone)
                .containsExactly("13800000001", "13800000002");
        assertThat(captor.getAllValues()).allSatisfy(command -> {
            assertThat(command.templateCode()).isEqualTo("SMS_TODO");
            assertThat(command.params()).containsEntry("processName", "报销流程");
            assertThat(command.caller()).isEqualTo("notify");
            assertThat(command.bizType()).isEqualTo("outbound");
            assertThat(command.bizId()).isEqualTo(42L);
        });
    }

    @Test
    void sendWithAllBlankPhonesIsSilentSuccess() throws Exception {
        when(smsPort.available()).thenReturn(true);
        SmsOutboundChannel channel = new SmsOutboundChannel(providerOf(smsPort), "SMS_TODO");
        OutboundMessage message = new OutboundMessage(1L, "t", "c", "workflow",
                List.of(new OutboundTarget(1L, null, null)), null);

        org.mockito.Mockito.doNothing().when(smsPort).send(any());
        channel.send(message);

        verify(smsPort, never()).send(any());
    }

    private static ObjectProvider<SmsPort> providerOf(SmsPort port) {
        return new ObjectProvider<>() {
            @Override
            public Stream<SmsPort> stream() {
                return port == null ? Stream.empty() : Stream.of(port);
            }

            @Override
            public SmsPort getIfAvailable() {
                return port;
            }
        };
    }
}
