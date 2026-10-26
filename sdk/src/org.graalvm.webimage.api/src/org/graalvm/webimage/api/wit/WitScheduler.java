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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Drives asynchronous Component Model operations for the single-threaded runtime.
 * <p>
 * All asynchronous waitables (subtasks, stream and future ends) are joined into one waitable set.
 * {@link #blockOn(WitFuture)} waits for host events with {@code waitable-set.wait} and dispatches
 * them to the registered handlers, which complete the corresponding {@link WitFuture}s.
 * Asynchronous exports lifted with a callback use {@link #startTask} and {@link #callback}
 * instead, which never block: the host calls the callback export whenever an event is delivered.
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

    @WitImport(module = "$root", name = "[waitable-join]")
    private static native void waitableJoin(int waitable, int set);

    @WitImport(module = "$root", name = "[subtask-drop]")
    private static native void subtaskDrop(int subtask);

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

    private static int set = -1;
    private static int eventBuffer;
    private static final Map<Integer, EventHandler> HANDLERS = new HashMap<>();

    private static int set() {
        if (set < 0) {
            set = waitableSetNew();
            eventBuffer = WitMemory.allocate(2 * Integer.BYTES, 4);
        }
        return set;
    }

    /**
     * Joins the waitable into the scheduler's set and registers its handler.
     */
    public static void register(int waitable, EventHandler handler) {
        HANDLERS.put(waitable, handler);
        waitableJoin(waitable, set());
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
        if (handler == null) {
            throw new IllegalStateException("No handler for waitable " + waitable + " (event " + code + ")");
        }
        /*
         * A finished handler drops its waitable (which also leaves the set) and the host may reuse
         * the index for a waitable registered by a continuation, so the entry is removed before the
         * handler runs and only put back if the waitable is still pending.
         */
        if (!handler.onEvent(code, payload)) {
            HANDLERS.put(waitable, handler);
        }
        return true;
    }

    /**
     * Blocks until the future completes, dispatching host events in the meantime.
     */
    public static void blockOn(WitFuture<?> future) {
        while (!future.isDone()) {
            if (HANDLERS.isEmpty()) {
                throw new IllegalStateException("Waiting for a future that no pending host operation can complete");
            }
            int code = waitableSetWait(set(), eventBuffer);
            dispatch(code, WitMemory.readInt(eventBuffer), WitMemory.readInt(eventBuffer + Integer.BYTES));
        }
    }

    /**
     * Dispatches already delivered events without blocking.
     */
    public static void poll() {
        if (HANDLERS.isEmpty()) {
            return;
        }
        int code = waitableSetPoll(set(), eventBuffer);
        dispatch(code, WitMemory.readInt(eventBuffer), WitMemory.readInt(eventBuffer + Integer.BYTES));
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
        final WitFuture<T> future;
        final Consumer<T> returner;

        Task(WitFuture<T> future, Consumer<T> returner) {
            this.future = future;
            this.returner = returner;
        }

        boolean finishIfDone() {
            if (!future.isDone()) {
                return false;
            }
            returner.accept(future.get());
            return true;
        }
    }

    private static final List<Task<?>> TASKS = new ArrayList<>();

    private static int waitCode() {
        return CALLBACK_WAIT | (set() << 4);
    }

    /**
     * Starts a callback-lifted export: returns the result through {@code task.return} right away
     * if the future is complete, otherwise suspends the task until it is.
     *
     * @param returner calls {@code task.return} with the lowered result
     * @return the callback code to return from the export
     */
    public static <T> int startTask(WitFuture<T> future, Consumer<T> returner) {
        Task<T> task = new Task<>(future, returner);
        if (task.finishIfDone()) {
            return CALLBACK_EXIT;
        }
        int slot = TASKS.indexOf(null);
        if (slot < 0) {
            slot = TASKS.size();
            TASKS.add(task);
        } else {
            TASKS.set(slot, task);
        }
        contextSet(slot);
        return waitCode();
    }

    /**
     * The callback of a callback-lifted export: dispatches the event and finishes the task if
     * its future completed.
     *
     * @return the callback code to return from the callback
     */
    public static int callback(int code, int waitable, int payload) {
        if (code == EVENT_TASK_CANCELLED) {
            throw new IllegalStateException("Task cancellation is not supported yet");
        }
        dispatch(code, waitable, payload);
        int slot = contextGet();
        Task<?> task = TASKS.get(slot);
        if (task.finishIfDone()) {
            TASKS.set(slot, null);
            return CALLBACK_EXIT;
        }
        return waitCode();
    }
}
