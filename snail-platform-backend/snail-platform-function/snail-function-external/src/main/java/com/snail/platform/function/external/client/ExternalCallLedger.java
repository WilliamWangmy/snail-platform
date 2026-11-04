package com.snail.platform.function.external.client;

import com.snail.platform.function.external.entity.SysExternalCallLog;
import com.snail.platform.function.external.mapper.SysExternalCallLogMapper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * 职责:第三方调用流水的异步落账单点——有界队列 + 专用落账线程,行在调用线程组装、入队即返回,
 * 队满丢弃并告警(流水是尽力而为,绝不反压调用方事务);停机时排空队列再退出。
 * 形态镜像 notify 的 OutboundDispatcher 与 OperLogAspect:同一套"留痕不反压"取舍,第三次复用。
 * 上游:AbstractExternalClient.invoke 入队;下游:SysExternalCallLogMapper。
 */
@Component
public class ExternalCallLedger implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallLedger.class);

    /** 队列容量;满即丢弃并告警,与操作日志留痕同一取舍 */
    static final int QUEUE_CAPACITY = 2000;

    /** 只增流水表的数据访问 */
    private final SysExternalCallLogMapper callLogMapper;

    /** 有界队列;满即丢并告警,不反压调用线程 */
    private final BlockingQueue<SysExternalCallLog> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

    /** 专用落账线程;daemon 不阻止 JVM 退出,排空由 destroy 负责 */
    private final Thread worker;

    /** 停机开关;volatile 让工作线程立即看到 destroy 的修改 */
    private volatile boolean running = true;

    public ExternalCallLedger(SysExternalCallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
        // 构造即启动:队列为空时按 500ms 轮询空转,与其他异步留痕单点同款
        this.worker = new Thread(this::drainLoop, "external-ledger");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** 非阻塞入队;队满即丢并告警,流水落账是尽力而为 */
    public void record(SysExternalCallLog row) {
        if (!queue.offer(row)) {
            log.warn("第三方调用流水队列已满({}),{} 的调用记录被丢弃", QUEUE_CAPACITY, row.getVendor());
        }
    }

    /** 500ms 轮询兼顾停机响应与空转开销;running=false 后先把余量排空再退出 */
    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            SysExternalCallLog row;
            try {
                row = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running) {
                    break;
                }
                continue;
            }
            if (row != null) {
                write(row);
            }
        }
    }

    /** 流水落库;库抖动只 warn——流水已经比调用本身次要,不能因为记不上而让线程退出 */
    private void write(SysExternalCallLog row) {
        try {
            callLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("第三方调用 {} / {} 的流水落库失败: {}",
                    row.getVendor(), row.getAction(), e.getMessage());
        }
    }

    /** 停机:置开关后最多等 10s 排空,仍未处理完的随进程退出丢弃并告警 */
    @Override
    public void destroy() throws InterruptedException {
        running = false;
        worker.join(10_000);
        if (worker.isAlive()) {
            log.warn("第三方流水落账线程 10s 内未排空队列(余 {} 条),随进程退出丢弃", queue.size());
        }
    }
}
