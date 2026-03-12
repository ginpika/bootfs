package com.nihilx.tfs.core.io;

import java.util.concurrent.*;

public class JsonIoThreadPool extends ThreadPoolExecutor {
    private static final int CORE_POOL_SIZE = 4;
    private static final int MAXIMUM_POOL_SIZE = 11;
    private static final int KEEP_ALIVE_TIME = 300;
    private static final TimeUnit TIME_UNIT = TimeUnit.SECONDS;
    private static final BlockingQueue<Runnable> BLOCKING_QUEUE = new LinkedBlockingQueue<>(32768);
    private static final RejectedExecutionHandler REJECTED_EXECUTION_HANDLER = (r, executor) -> {
        try {
            executor.getQueue().put(r);
        } catch (InterruptedException e) {
            try {
                Thread.sleep(1000);
                executor.getQueue().put(r);
            } catch (InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
    };

    public JsonIoThreadPool() {
        super(JsonIoThreadPool.CORE_POOL_SIZE, JsonIoThreadPool.MAXIMUM_POOL_SIZE, JsonIoThreadPool.KEEP_ALIVE_TIME,
                JsonIoThreadPool.TIME_UNIT, JsonIoThreadPool.BLOCKING_QUEUE, JsonIoThreadPool.REJECTED_EXECUTION_HANDLER);
    }
}
