package com.nihilx.tfs.core.io;

import java.util.concurrent.*;

public class TransferThreadPool extends ThreadPoolExecutor {
    private static final int CORE_POOL_SIZE = 3;
    private static final int MAXIMUM_POOL_SIZE = 3;
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

    public TransferThreadPool() {
        super(TransferThreadPool.CORE_POOL_SIZE, TransferThreadPool.MAXIMUM_POOL_SIZE, TransferThreadPool.KEEP_ALIVE_TIME,
                TransferThreadPool.TIME_UNIT, TransferThreadPool.BLOCKING_QUEUE, TransferThreadPool.REJECTED_EXECUTION_HANDLER);
    }
}
