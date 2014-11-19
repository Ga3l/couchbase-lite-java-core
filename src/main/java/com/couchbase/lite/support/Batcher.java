package com.couchbase.lite.support;

import com.couchbase.lite.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Utility that queues up objects until the queue fills up or a time interval elapses,
 * then passes objects, in groups of its capacity, to a client-supplied processor block.
 */
public class Batcher<T> {

    private ScheduledExecutorService workExecutor;
    private ScheduledFuture pendingFuture;

    private int capacity;
    private int delayMs;
    private int scheduledDelay;
    private BlockingQueue<T> inbox;
    private BatchProcessor<T> processor;
    private boolean scheduled = false;
    private long lastProcessedTime;

    private Runnable processNowRunnable = new Runnable() {

        @Override
        public void run() {
            try {
                processNow();
            } catch (Exception e) {
                // we don't want this to crash the batcher
                com.couchbase.lite.util.Log.e(Log.TAG_BATCHER, this + ": BatchProcessor throw exception", e);
            }
        }
    };


    /**
     * Initializes a batcher.
     *
     * @param workExecutor the work executor that performs actual work
     * @param capacity The maximum number of objects to batch up. If the queue reaches this size, the queued objects will be sent to the processor immediately.
     * @param delayMs The maximum waiting time in milliseconds to collect objects before processing them. In some circumstances objects will be processed sooner.
     * @param processor The callback/block that will be called to process the objects.
     */
    public Batcher(ScheduledExecutorService workExecutor, int capacity, int delayMs, BatchProcessor<T> processor) {
        this.workExecutor = workExecutor;
        this.capacity = capacity;
        this.delayMs = delayMs;
        this.processor = processor;
        this.inbox = new LinkedBlockingQueue<T>();

    }

    /**
     * Adds multiple objects to the queue.
     */
    public void queueObjects(List<T> objects) {

        Log.v(Log.TAG_BATCHER, "%s: queueObjects called with %d objects. ", this, objects.size());
        if (objects.size() == 0) {
            return;
        }

        Log.v(Log.TAG_BATCHER, "%s: inbox size before adding objects: %d", this, inbox.size());

        inbox.addAll(objects);

        scheduleWithDelay(delayToUse());
    }

    public void waitForPendingFutures() {

        try {
            try {
                Log.d(Log.TAG_BATCHER, "calling future.get() on %s", pendingFuture);
                pendingFuture.get();
                Log.d(Log.TAG_BATCHER, "done calling future.get() on %s", pendingFuture);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (ExecutionException e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            Log.e(Log.TAG_BATCHER, "Exception waiting for pending futures: %s", e);
        }

    }

    /**
     * Adds an object to the queue.
     */
    public void queueObject(T object) {
        List<T> objects = Arrays.asList(object);
        queueObjects(objects);
    }

    /**
     * Sends queued objects to the processor block (up to the capacity).
     */
    public void flush() {
        scheduleWithDelay(delayToUse());
    }


    /**
     * Empties the queue without processing any of the objects in it.
     */
    public void clear() {
        Log.v(Log.TAG_BATCHER, "%s: clear() called, setting inbox to null", this);
        unschedule();
        inbox.clear();
    }

    public int count() {
        synchronized(this) {
            if(inbox == null) {
                return 0;
            }
            return inbox.size();
        }
    }


    private void processNow() {

        Log.v(Log.TAG_BATCHER, this + ": processNow() called");

        scheduled = false;
        List<T> toProcess = new ArrayList<T>();

        if (inbox == null || inbox.size() == 0) {
            Log.v(Log.TAG_BATCHER, this + ": processNow() called, but inbox is empty");
            return;
        } else if (inbox.size() <= capacity) {
            Log.v(Log.TAG_BATCHER, "%s: inbox.size() <= capacity, adding %d items from inbox -> toProcess", this, inbox.size());
            while (inbox.size() > 0) {
                try {
                    T t = inbox.take();
                    toProcess.add(t);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        } else {
            Log.v(Log.TAG_BATCHER, "%s: processNow() called, inbox size: %d", this, inbox.size());
            int i = 0;
            while (inbox.size() > 0 && i < capacity) {
                try {
                    T t = inbox.take();
                    toProcess.add(t);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                i += 1;
            }


            Log.v(Log.TAG_BATCHER, "%s: inbox.size() > capacity, moving %d items from inbox -> toProcess array", this, toProcess.size());

            // There are more objects left, so schedule them Real Soon:
            // TODO: this is bad, because the pendingFuture will change under peoples feet
            pendingFuture = workExecutor.schedule(processNowRunnable, 0, TimeUnit.MILLISECONDS);

        }

        if(toProcess != null && toProcess.size() > 0) {
            Log.v(Log.TAG_BATCHER, "%s: invoking processor with %d items ", this, toProcess.size());
            processor.process(toProcess);
        } else {
            Log.v(Log.TAG_BATCHER, "%s: nothing to process", this);
        }
        lastProcessedTime = System.currentTimeMillis();

        // in case we ignored any schedule requests while processing, if
        // we have more items in inbox, lets schedule another processing attempt
        if (inbox.size() > 0) {
            int delayToUse = delayToUse();
            pendingFuture = workExecutor.schedule(processNowRunnable, delayToUse, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleWithDelay(int suggestedDelay) {

        // if we already have an active pending future, ignore this call
        if (pendingFuture != null && !pendingFuture.isCancelled() && !pendingFuture.isDone()) {
            Log.v(Log.TAG_BATCHER, "%s: scheduleWithDelay already has a pending task: %s. ignoring.", this, pendingFuture);
            return;
        }

        Log.v(Log.TAG_BATCHER, "%s: scheduleWithDelay called with delayMs: %d ms", this, suggestedDelay);
        scheduledDelay = suggestedDelay;
        Log.v(Log.TAG_BATCHER, "workExecutor.schedule() with delayMs: %d ms", suggestedDelay);
        pendingFuture = workExecutor.schedule(processNowRunnable, suggestedDelay, TimeUnit.MILLISECONDS);
        Log.v(Log.TAG_BATCHER, "%s: created future: %s", this, pendingFuture);

    }

    private void unschedule() {
        Log.v(Log.TAG_BATCHER, this + ": unschedule() called");

        try {
            Log.d(Log.TAG_BATCHER, "calling future.cancel() on %s", pendingFuture);
            pendingFuture.cancel(true);
            Log.d(Log.TAG_BATCHER, "done calling future.cancel() on %s", pendingFuture);

        } catch (Exception e) {
            Log.e(Log.TAG_BATCHER, "Exception waiting for pending futures: %s", e);
        }
    }

    /*
     * calculates the delayMs to use when scheduling the next batch of objects to process
     * There is a balance required between clearing down the input queue as fast as possible
     * and not exhausting downstream system resources such as sockets and http response buffers
     * by processing too many batches concurrently.
     */
    private int delayToUse() {

        //initially set the delayMs to the default value for this Batcher
        int delayToUse = delayMs;

        //get the time interval since the last batch completed to the current system time
        long delta = (System.currentTimeMillis() - lastProcessedTime);

        //if the time interval is greater or equal to the default delayMs then set the
        // delayMs so that the next batch gets scheduled to process immediately
        if (delta >= delayMs) {
            delayToUse = 0;
        }

        Log.v(Log.TAG_BATCHER, "%s: delayToUse() delta: %d, delayToUse: %d, delayMs: %d", this, delta, delayToUse, delta);

        return delayToUse;
    }
}
