/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
/**
 * Declares a {@code static native} method as a call to a core WebAssembly function imported by
 * the WASI component.
 * <p>
 * The method parameters and return type must be {@code int}, {@code long}, {@code float},
 * {@code double}, or {@code void} and correspond to the Canonical ABI signature of the imported
 * function. Pointers and resource handles are {@code int}.
 * <p>
 * The import module and name follow the naming scheme used by {@code wasm-tools component embed}:
 * the module is the fully qualified interface name (for example {@code wasi:random/random@0.3.0})
 * or {@code $root} for functions imported directly by the world, and the name is the WIT function
 * name, optionally prefixed with a Canonical ABI built-in such as {@code [stream-new-0]}.
 *
 * <pre>
 * &#64;WitImport(module = "wasi:random/random@0.3.0", name = "get-random-u64")
 * static native long getRandomU64();
 * </pre>
 *
 * This is the low-level interface used by generated bindings. It is only available when
 * compiling for the WASI target.
 *
 * @since 26.0
 */
package org.graalvm.webimage.api.wit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Drives asynchronous Component Model operations for the single-threaded runtime.
 * <p>
 * Asynchronous waitables (subtasks, stream and future ends) are joined into the waitable set of
 * the context that started them: code outside a callback-lifted task shares one set, and each
 * callback-lifted task has its own, so that the host resumes the task an event belongs to.
 * {@link #blockOn(WitFuture)} waits for host events with {@code waitable-set.wait} and dispatches
 * them to the registered handlers, which complete the corresponding {@link WitFuture}s.
 * Asynchronous exports lifted with a callback use {@link #beginTask}, {@link #startTask}, and
 * {@link #callback} instead, which never block: the host calls the callback export whenever an
 * event is delivered, and the task exits once its result is returned and no operation it started
 * is pending.
 * <p>
 * Only functional in an image compiled for the WASI target.
 *
 * @since 26.0
 */
public final class WitScheduler {

    private WitScheduler() {
    }

    /** Event codes delivered by {@code waitable-set.wait} and the callback. */
    public static final int EVENT_NONE = 0;
    public static final int EVENT_SUBTASK = 1;
    public static final int EVENT_STREAM_READ = 2;
    public static final int EVENT_STREAM_WRITE = 3;
    public static final int EVENT_FUTURE_READ = 4;
    public static final int EVENT_FUTURE_WRITE = 5;
    public static final int EVENT_TASK_CANCELLED = 6;

    /** Subtask states, the low 4 bits of the status returned by an asynchronously lowered call. */
    public static final int SUBTASK_STARTING = 0;
    public static final int SUBTASK_STARTED = 1;
    public static final int SUBTASK_RETURNED = 2;

    /** Callback codes returned by callback-lifted exports and their callbacks. */
    public static final int CALLBACK_EXIT = 0;
    public static final int CALLBACK_YIELD = 1;
    public static final int CALLBACK_WAIT = 2;

    /** Return value of an asynchronous built-in that did not complete immediately. */
    public static final int BLOCKED = -1;

    @WitImport(module = "$root", name = "[waitable-set-new]")
    private static native int waitableSetNew();

    @WitImport(module = "$root", name = "[waitable-set-wait]")
    private static native int waitableSetWait(int set, int eventPtr);

    @WitImport(module = "$root", name = "[waitable-set-poll]")
    private static native int waitableSetPoll(int set, int eventPtr);

    @WitImport(module = "$root", name = "[waitable-set-drop]")
    private static native void waitableSetDrop(int set);

    @WitImport(module = "$root", name = "[waitable-join]")
    private static native void waitableJoin(int waitable, int set);

    @WitImport(module = "$root", name = "[subtask-drop]")
    private static native void subtaskDrop(int subtask);

    /** Synchronous {@code subtask.cancel}: blocks until the subtask is cancelled or returned. */
    @WitImport(module = "$root", name = "[subtask-cancel]")
    private static native int subtaskCancel(int subtask);

    @WitImport(module = "$root", name = "[context-get-0]")
    private static native int contextGet();

    @WitImport(module = "$root", name = "[context-set-0]")
    private static native void contextSet(int value);

    /**
     * Handles an event for one waitable.
     */
    public interface EventHandler {
        /**
         * @param code the event code
         * @param payload the event payload (a subtask state or a packed copy result)
         * @return true if the waitable is finished and the handler can be removed
         */
        boolean onEvent(int code, int payload);
    }

    /** The waitable set of code running outside a callback-lifted task. */
    private static int rootSet = -1;
    private static int eventBuffer;
    private static final Map<Integer, EventHandler> HANDLERS = new HashMap<>();
    /** The task slot that registered each pending handler (-1 for code outside a task). */
    private static final Map<Integer, Integer> HANDLER_TASKS = new HashMap<>();
    /** The callback-lifted task whose code is running, or -1. */
    private static int currentTask = -1;

    private static int eventBuffer() {
        if (eventBuffer == 0) {
            eventBuffer = WitMemory.allocate(2 * Integer.BYTES, 4);
        }
        return eventBuffer;
    }

    /**
     * The waitable set of the current context: each callback-lifted task waits on its own set so
     * that the host resumes the task that owns the operation an event belongs to.
     */
    private static int currentSet() {
        if (currentTask < 0) {
            if (rootSet < 0) {
                rootSet = waitableSetNew();
            }
            return rootSet;
        }
        return TASKS.get(currentTask).set();
    }

    /**
     * Forgets the handlers of a cancelled task and takes their waitables out of its set (which
     * must be empty to be dropped). The host operations themselves stay pending on their handles.
     */
    private static void abandonWork(int slot) {
        Iterator<Map.Entry<Integer, Integer>> it = HANDLER_TASKS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> entry = it.next();
            if (entry.getValue() == slot) {
                waitableJoin(entry.getKey(), 0);
                HANDLERS.remove(entry.getKey());
                it.remove();
            }
        }
    }

    private static boolean hasPendingWork(int slot) {
        for (int owner : HANDLER_TASKS.values()) {
            if (owner == slot) {
                return true;
            }
        }
        return false;
    }

    /**
     * Joins the waitable into the set of the current context and registers its handler.
     */
    public static void register(int waitable, EventHandler handler) {
        HANDLERS.put(waitable, handler);
        HANDLER_TASKS.put(waitable, currentTask);
        waitableJoin(waitable, currentSet());
    }

    /**
     * Delivers one event to its handler.
     *
     * @return true if an event was dispatched
     */
    public static boolean dispatch(int code, int waitable, int payload) {
        if (code == EVENT_NONE) {
            return false;
        }
        EventHandler handler = HANDLERS.remove(waitable);
        Integer owner = HANDLER_TASKS.remove(waitable);
        if (handler == null) {
            throw new IllegalStateException("No handler for waitable " + waitable + " (event " + code + ")");
        }
        /*
         * The waitable leaves the set before the handler runs: a stream end whose peer is dropped
         * later gets a DROPPED notification even without a pending copy, which must not reach a
         * handler-less waitable. A finished handler may drop its waitable and the host may reuse
         * the index for a waitable registered by a continuation, so the entry is removed before
         * the handler runs and only put back (and joined again) if the waitable is still pending
         * and no continuation registered a new handler for the index. The continuation runs in
         * the context of the owning task so that operations it starts belong to that task.
         */
        waitableJoin(waitable, 0);
        int previous = currentTask;
        currentTask = owner == null ? -1 : owner;
        try {
            if (!handler.onEvent(code, payload) && !HANDLERS.containsKey(waitable)) {
                HANDLERS.put(waitable, handler);
                HANDLER_TASKS.put(waitable, currentTask);
                waitableJoin(waitable, currentSet());
            }
        } finally {
            currentTask = previous;
        }
        return true;
    }

    /**
     * Blocks until the future completes, dispatching host events in the meantime.
     */
    public static void blockOn(WitFuture<?> future) {
        while (!future.isDone()) {
            if (!hasPendingWork(currentTask)) {
                throw new IllegalStateException("Waiting for a future that no pending host operation can complete");
            }
            int buffer = eventBuffer();
            int code = waitableSetWait(currentSet(), buffer);
            dispatch(code, WitMemory.readInt(buffer), WitMemory.readInt(buffer + Integer.BYTES));
        }
    }

    /**
     * Dispatches already delivered events without blocking.
     */
    public static void poll() {
        if (!hasPendingWork(currentTask)) {
            return;
        }
        int buffer = eventBuffer();
        int code = waitableSetPoll(currentSet(), buffer);
        dispatch(code, WitMemory.readInt(buffer), WitMemory.readInt(buffer + Integer.BYTES));
    }

    // Subtasks (asynchronously lowered imports)

    /**
     * Turns the status of an asynchronously lowered call into a future.
     *
     * @param status the value returned by the call ({@code subtask << 4 | state})
     * @param lifter lifts the result once the subtask returned; receives the result address (0 if
     *            the function has no result) and must free any memory it owns
     * @param resultAddress address of the result area (0 if none), freed after lifting
     * @param parameterMemory memory of lowered parameters that must stay alive until the subtask
     *            started
     */
    public static <T> WitFuture<T> subtask(int status, int resultAddress, List<Integer> parameterMemory, IntFunction<T> lifter) {
        int state = status & 0xF;
        int handle = status >>> 4;
        WitFuture<T> future = new WitFuture<>();
        if (state == SUBTASK_RETURNED) {
            WitArena.freeAll(parameterMemory);
            completeFromResult(future, resultAddress, lifter);
            return future;
        }
        future.setCanceller(() -> {
            // Blocks until the callee acknowledged; afterwards the subtask is finished either way
            // If the result arrived before the cancellation took effect, it is dropped with the memory
            HANDLERS.remove(handle);
            HANDLER_TASKS.remove(handle);
            // A waitable in a set cannot be used synchronously
            waitableJoin(handle, 0);
            subtaskCancel(handle);
            WitArena.freeAll(parameterMemory);
            if (resultAddress != 0) {
                WitMemory.free(resultAddress);
            }
            subtaskDrop(handle);
        });
        register(handle, (code, payload) -> {
            if (code != EVENT_SUBTASK) {
                throw new IllegalStateException("Unexpected event " + code + " for subtask " + handle);
            }
            if (payload == SUBTASK_STARTED) {
                WitArena.freeAll(parameterMemory);
                return false;
            }
            if (payload == SUBTASK_RETURNED) {
                if (state == SUBTASK_STARTING) {
                    // STARTED may be skipped when both happen before the next wait
                    WitArena.freeAll(parameterMemory);
                }
                subtaskDrop(handle);
                completeFromResult(future, resultAddress, lifter);
                return true;
            }
            future.completeExceptionally(new IllegalStateException("Subtask cancelled, state " + payload));
            return true;
        });
        return future;
    }

    private static <T> void completeFromResult(WitFuture<T> future, int resultAddress, IntFunction<T> lifter) {
        WitArena.push();
        try {
            T value = lifter.apply(resultAddress);
            future.complete(value);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        } finally {
            WitArena.pop();
            if (resultAddress != 0) {
                WitMemory.free(resultAddress);
            }
        }
    }

    // Tasks (callback-lifted exports)

    private static final class Task<T> {
        WitFuture<T> future;
        Consumer<T> returner;
        /** Whether the result was passed to {@code task.return}. */
        boolean returned;
        private int waitableSet = -1;

        int set() {
            if (waitableSet < 0) {
                waitableSet = waitableSetNew();
            }
            return waitableSet;
        }

        void dropSet() {
            if (waitableSet >= 0) {
                waitableSetDrop(waitableSet);
                waitableSet = -1;
            }
        }

        boolean finishIfDone() {
            if (returned) {
                return true;
            }
            if (future == null || !future.isDone()) {
                return false;
            }
            if (future.isFailed()) {
                System.err.println("Asynchronous export failed: " + failureOf(future));
                System.err.flush();
                throw new IllegalStateException("Asynchronous export failed", failureOf(future));
            }
            returner.accept(future.get());
            returned = true;
            return true;
        }

        private static Throwable failureOf(WitFuture<?> f) {
            try {
                f.get();
                return null;
            } catch (Throwable t) {
                return t;
            }
        }
    }

    private static final List<Task<?>> TASKS = new ArrayList<>();

    /**
     * Begins a callback-lifted export before its Java implementation runs, so that host
     * operations the implementation starts belong to the task.
     *
     * @return the task slot, to be passed to {@link #startTask}
     */
    public static int beginTask() {
        Task<?> task = new Task<>();
        int slot = TASKS.indexOf(null);
        if (slot < 0) {
            slot = TASKS.size();
            TASKS.add(task);
        } else {
            TASKS.set(slot, task);
        }
        contextSet(slot);
        currentTask = slot;
        return slot;
    }

    /**
     * Continues a callback-lifted export once its Java implementation returned a future: the
     * result is passed to {@code task.return} as soon as the future completes, and the task exits
     * once the host operations it started (for example writes to a returned stream) have finished.
     *
     * @param returner calls {@code task.return} with the lowered result
     * @return the callback code to return from the export
     */
    @SuppressWarnings("unchecked")
    public static <T> int startTask(int slot, WitFuture<T> future, Consumer<T> returner) {
        currentTask = -1;
        Task<T> task = (Task<T>) TASKS.get(slot);
        task.future = future;
        task.returner = returner;
        return settle(slot, task);
    }

    private static int settle(int slot, Task<?> task) {
        if (task.finishIfDone() && !hasPendingWork(slot)) {
            task.dropSet();
            TASKS.set(slot, null);
            return CALLBACK_EXIT;
        }
        return CALLBACK_WAIT | (task.set() << 4);
    }

    /**
     * The callback of a callback-lifted export: dispatches the event and finishes the task if
     * its future completed.
     *
     * @param taskCancel calls the {@code task.cancel} built-in of the exported interface
     * @return the callback code to return from the callback
     */
    public static int callback(int code, int waitable, int payload, Runnable taskCancel) {
        int slot = contextGet();
        Task<?> task = TASKS.get(slot);
        if (code == EVENT_TASK_CANCELLED) {
            /*
             * The host cancelled the export: cancel the Java future (which cancels pending
             * subtasks it registered a canceller for), acknowledge with task.cancel (imported per
             * exported interface by the generated adapter), and exit without a result.
             */
            if (task.future != null) {
                task.future.cancel();
            }
            abandonWork(slot);
            task.dropSet();
            TASKS.set(slot, null);
            taskCancel.run();
            return CALLBACK_EXIT;
        }
        dispatch(code, waitable, payload);
        return settle(slot, task);
    }
}
