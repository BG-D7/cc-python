package work.diffdev.kik.python;

import dan200.computercraft.api.lua.MethodResult;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public class PythonExecutionBridge {
    private final SynchronousQueue<YieldRequest> toCC = new SynchronousQueue<>();
    private final SynchronousQueue<Object[]> toPy = new SynchronousQueue<>();
    private volatile boolean finished = false;
    private volatile boolean failed = false;
    private volatile String errorMessage = null;
    private volatile Thread executionThread = null;

    public static class YieldRequest {
        public final String filter;
        public final Object[] yieldArgs;
        public final boolean isPullEvent;

        public YieldRequest(String filter) {
            this.filter = filter;
            this.yieldArgs = null;
            this.isPullEvent = true;
        }

        public YieldRequest(Object[] yieldArgs) {
            this.filter = null;
            this.yieldArgs = yieldArgs;
            this.isPullEvent = false;
        }

        public static final YieldRequest FINISH = new YieldRequest((String) null);
    }

    public void setExecutionThread(Thread thread) {
        this.executionThread = thread;
    }

    public void signalFinished(boolean success, String error) {
        this.finished = true;
        this.failed = !success;
        this.errorMessage = error;
        try {
            toCC.put(YieldRequest.FINISH);
        } catch (InterruptedException ignored) {}
    }

    public Object[] yieldForEvent(String filter) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Execution interrupted");
        }
        toCC.put(new YieldRequest(filter));
        return toPy.take();
    }

    public Object[] yieldCustom(Object[] args) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Execution interrupted");
        }
        toCC.put(new YieldRequest(args));
        return toPy.take();
    }

    public MethodResult handleInitialStep() {
        try {
            // Allow up to 45 seconds for first-run GraalPy initialization & warmup
            YieldRequest req = toCC.poll(45, TimeUnit.SECONDS);
            if (req == null) {
                abort();
                return MethodResult.of(false, "Timeout waiting for Python startup");
            }
            if (finished || req == YieldRequest.FINISH) {
                return finishResult();
            }
            return createResumeResult(req);
        } catch (InterruptedException e) {
            abort();
            return MethodResult.of(false, "Interrupted");
        }
    }

    private MethodResult createResumeResult(YieldRequest req) {
        if (req.isPullEvent) {
            return MethodResult.pullEvent(req.filter, eventArgs -> {
                if (finished) return finishResult();
                try {
                    toPy.put(eventArgs != null ? eventArgs : new Object[0]);
                    YieldRequest next = toCC.take();
                    if (finished || next == YieldRequest.FINISH) {
                        return finishResult();
                    }
                    return createResumeResult(next);
                } catch (InterruptedException e) {
                    abort();
                    return MethodResult.of(false, "Interrupted");
                }
            });
        } else {
            return MethodResult.yield(req.yieldArgs != null ? req.yieldArgs : new Object[0], eventArgs -> {
                if (finished) return finishResult();
                try {
                    toPy.put(eventArgs != null ? eventArgs : new Object[0]);
                    YieldRequest next = toCC.take();
                    if (finished || next == YieldRequest.FINISH) {
                        return finishResult();
                    }
                    return createResumeResult(next);
                } catch (InterruptedException e) {
                    abort();
                    return MethodResult.of(false, "Interrupted");
                }
            });
        }
    }

    private MethodResult finishResult() {
        if (failed) {
            return MethodResult.of(false, errorMessage != null ? errorMessage : "Execution failed");
        }
        return MethodResult.of(true);
    }

    public void abort() {
        finished = true;
        if (executionThread != null && executionThread.isAlive()) {
            executionThread.interrupt();
        }
    }
}
