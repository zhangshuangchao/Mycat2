package io.mycat;


import io.mycat.config.ThreadPoolExecutorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class MycatWorkerProcessor {
    private final static Logger LOGGER = LoggerFactory.getLogger(MycatWorkerProcessor.class);
    private NameableExecutor mycatWorker;
    private NameableExecutor timeWorker;

    public MycatWorkerProcessor(NameableExecutor mycatWorker, NameableExecutor timeWorker) {
        this.mycatWorker = mycatWorker;
        this.timeWorker = timeWorker;
    }

    /**
     * 不支持热更新
     *
     * @param workerConfig
     */
    public MycatWorkerProcessor(ThreadPoolExecutorConfig workerConfig,
                                ThreadPoolExecutorConfig timeConfig) {
        this(init("MYCAT_WORKER", workerConfig), init("MYCAT_TIME_WORKER", timeConfig));
    }

    private static NameableExecutor init(String name, ThreadPoolExecutorConfig workerConfig) {
        int corePoolSize = workerConfig.getCorePoolSize();
        int maximumPoolSize = workerConfig.getMaxPoolSize();
        long keepAliveTime = workerConfig.getKeepAliveTime();
        String timeUnit = workerConfig.getTimeUnit();
        int maxPengdingLimit = workerConfig.getMaxPendingLimit();//不支持
        return ExecutorUtil.create(name,
                corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                TimeUnit.valueOf(timeUnit));
    }

    public NameableExecutor getMycatWorker() {
        if (mycatWorker == null) {
            synchronized (this) {
                if (mycatWorker == null) {
                    LOGGER.info("Mycat WorkerProcessor init by:default");
                    mycatWorker = ExecutorUtil.create("DEFAULT_MYCAT_WORKER", 1);
                }
            }
        }
        return Objects.requireNonNull(mycatWorker, "mycatWorker does not init");
    }

    public NameableExecutor getTimeWorker() {
        if (timeWorker == null) {
            synchronized (this) {
                if (timeWorker == null) {
                    LOGGER.info("MYCAT TIME WorkerProcessor init by:default");
                    timeWorker = ExecutorUtil.create("DEFAULT_TIME_MYCAT_WORKER", 1);
                }
            }
        }
        return Objects.requireNonNull(timeWorker, "mycat time worker does not init");
    }
}