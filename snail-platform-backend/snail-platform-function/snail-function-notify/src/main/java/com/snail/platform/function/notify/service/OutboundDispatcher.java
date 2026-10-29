package com.snail.platform.function.notify.service;

import cn.hutool.core.util.StrUtil;
import com.snail.platform.function.notify.entity.SysNotifyOutbound;
import com.snail.platform.function.notify.mapper.SysNotifyOutboundMapper;
import com.snail.platform.function.notify.service.OutboundChannel.OutboundMessage;
import com.snail.platform.function.notify.service.OutboundChannel.OutboundTarget;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 职责:出站投递单点——有界队列 + 专用投递线程,事务提交后入队(afterCommit),
 * 队满丢弃并告警(留痕是尽力而为,不堵塞业务事务);停机时排空队列再退出。
 * 每条消息对每个可用通道投递一次,失败重试一次,终态落 sys_notify_outbound(ignore 表,
 * 投递线程无租户上下文,租户归属靠行内快照列)。
 * 上游:NotifyService 经 afterCommit 喂入;下游:OutboundChannel 各实现。
 */
@Component
public class OutboundDispatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboundDispatcher.class);

    /** 队列容量;满即丢弃并告警,与操作日志留痕同一取舍 */
    static final int QUEUE_CAPACITY = 2000;

    /** 投递状态:成功 */
    private static final int STATUS_SUCCESS = 1;

    /** 投递状态:失败 */
    private static final int STATUS_FAILED = 2;

    /** 无可用通道时的兜底通道名:落一行 log 记录,保证每条消息都有投递痕迹 */
    private static final String FALLBACK_CHANNEL = "log";

    /** 通道 SPI 集合;逐个在投递时判 available,不依赖装配期条件 */
    private final ObjectProvider<OutboundChannel> channelProvider;

    /** 投递终态写;ignore 表,线程内显式带租户快照 */
    private final SysNotifyOutboundMapper outboundMapper;

    /** 有界队列;满即丢并告警,不反压业务事务 */
    private final BlockingQueue<DispatchJob> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /** 专用投递线程;daemon 不阻止 JVM 退出,排空由 destroy 负责 */
    private final Thread worker;

    /** 停机开关;volatile 让工作线程立即看到 destroy 的修改 */
    private volatile boolean running = true;

    public OutboundDispatcher(ObjectProvider<OutboundChannel> channelProvider,
                              SysNotifyOutboundMapper outboundMapper) {
        this.channelProvider = channelProvider;
        this.outboundMapper = outboundMapper;
        // 构造即启动:队列为空时按 500ms 轮询空转,避免为无通知的时段维护额外调度器
        this.worker = new Thread(this::drainLoop, "notify-outbound");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** 一次待投递任务:tenantId 是消息归属租户快照,落 outbound 行用 */
    public record DispatchJob(
            /** 消息 ID */
            Long messageId,
            /** 消息归属租户快照 */
            Long tenantId,
            /** 标题 */
            String title,
            /** 正文,纯文本 */
            String content,
            /** 收件目标 */
            List<OutboundTarget> targets) {
    }

    /** 事务提交后再入队;无事务上下文(系统线程直调)时立即入队 */
    public void dispatchAfterCommit(DispatchJob job) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enqueue(job);
                }
            });
        } else {
            enqueue(job);
        }
    }

    /** 非阻塞入队;队满即丢并告警,出站投递是尽力而为 */
    private void enqueue(DispatchJob job) {
        if (!queue.offer(job)) {
            log.warn("通知投递队列已满({}),消息 {} 的出站投递被丢弃", QUEUE_CAPACITY, job.messageId());
        }
    }

    /** 500ms 轮询兼顾停机响应与空转开销;running=false 后先把余量排空再退出 */
    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            DispatchJob job;
            try {
                job = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running) {
                    break;
                }
                continue;
            }
            if (job != null) {
                handle(job);
            }
        }
    }

    /** 通道可用性在投递时判定;全不可用也落一行 log 兜底,保证消息有投递记录 */
    private void handle(DispatchJob job) {
        List<OutboundChannel> channels = channelProvider.stream().filter(OutboundChannel::available).toList();
        if (channels.isEmpty()) {
            record(job, FALLBACK_CHANNEL, "无可用出站通道", STATUS_SUCCESS, 0, null);
            return;
        }
        OutboundMessage message = new OutboundMessage(job.messageId(), job.title(), job.content(), job.targets());
        for (OutboundChannel channel : channels) {
            dispatchWithRetry(job, channel, message);
        }
    }

    /** 最多两次尝试(首发 + 一次重试);retryCount 记实际重试次数,不是尝试次数 */
    private void dispatchWithRetry(DispatchJob job, OutboundChannel channel, OutboundMessage message) {
        String error = null;
        int attempts = 0;
        while (attempts < 2) {
            attempts++;
            try {
                channel.send(message);
                error = null;
                break;
            } catch (Exception e) {
                error = e.getMessage();
                log.warn("通知 {} 经 {} 通道第 {} 次投递失败: {}", job.messageId(), channel.channel(), attempts,
                        StrUtil.subPre(error, 200));
            }
        }
        record(job, channel.channel(), "batch×" + job.targets().size(),
                error == null ? STATUS_SUCCESS : STATUS_FAILED, attempts - 1, error);
    }

    /** 投递终态落库;线程无登录人上下文,审计操作人自然留空;摘要与错误按列宽截断 */
    private void record(DispatchJob job, String channel, String target, int status, int retryCount, String error) {
        SysNotifyOutbound row = new SysNotifyOutbound();
        row.setMessageId(job.messageId());
        row.setTenantId(job.tenantId());
        row.setChannel(channel);
        row.setTarget(StrUtil.subPre(target, 100));
        row.setStatus(status);
        row.setRetryCount(retryCount);
        row.setError(StrUtil.subPre(error, 500));
        row.setSendTime(LocalDateTime.now());
        try {
            outboundMapper.insert(row);
        } catch (Exception e) {
            log.warn("通知 {} 的投递记录落库失败: {}", job.messageId(), StrUtil.subPre(e.getMessage(), 200));
        }
    }

    /** 停机:置开关后最多等 10s 排空,仍未处理完的随进程退出丢弃并告警 */
    @Override
    public void destroy() throws InterruptedException {
        running = false;
        worker.join(10_000);
        if (worker.isAlive()) {
            log.warn("通知投递线程 10s 内未排空队列(余 {} 条),随进程退出丢弃", queue.size());
        }
    }
}
