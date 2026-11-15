/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.webimage.wasi.threads;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.webimage.api.wit.WitScheduler;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.FunctionPointerHolder;
import com.oracle.svm.core.graal.nodes.WriteStackPointerNode;
import com.oracle.svm.core.graal.snippets.StackOverflowCheckImpl;
import com.oracle.svm.core.stack.StackOverflowCheck;
import com.oracle.svm.core.thread.PlatformThreads;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.option.HostedOptionKey;
import com.oracle.svm.webimage.substitute.system.WasiThreadAccess;
import com.oracle.svm.webimage.wasi.WasiAllocator;
import com.oracle.svm.webimage.wasi.WasiCalls;
import com.oracle.svm.webimage.wasi.WasiClocks;
import com.oracle.svm.webimage.wasi.WasiExit;
import com.oracle.svm.webimage.wasi.WasiImports;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.options.Option;

/**
 * Java threads on WASI: cooperative threads of the Component Model.
 * <p>
 * Each started {@link Thread} is a thread created with {@code thread.new-indirect}. Such threads
 * share the linear memory and never run in parallel: exactly one of them executes, and control
 * moves only where this class switches explicitly ({@code thread.suspend-then-resume}). That
 * happens where a Java thread blocks: {@code LockSupport.park}, {@code Thread.sleep},
 * {@code Object.wait}, {@code Thread.yield}, {@code Thread.join} and the end of a thread. There is
 * no preemption, so a thread that computes without blocking keeps running.
 * <p>
 * What the host does not know about, this class manages itself:
 * <ul>
 * <li>Every thread has its own shadow stack (see {@link WasiThreadStacks}). The stack pointer is
 * a global of the module, so a thread saves it before a switch and restores it when it is
 * resumed. The garbage collector walks the stacks of the suspended threads too.</li>
 * <li>The current {@link Thread} object and the stack overflow boundary are per-thread values kept
 * in VM thread locals, of which there is only one set. They are switched together with the
 * thread.</li>
 * <li>Scheduling: a queue of runnable threads, timers for timed waits, and the wait sets of
 * {@code Object.wait}. When no thread is runnable, the thread that noticed waits for the earliest
 * timer or for the next event of a pending host operation.</li>
 * </ul>
 * Monitors stay no-ops as in the single-threaded runtime: because threads only switch at blocking
 * operations, a {@code synchronized} block that does not block is atomic. A thread that blocks
 * inside a {@code synchronized} block other than through {@code wait} does not keep other threads
 * out.
 * <p>
 * Components that use this import the {@code thread.*} built-ins and need a host with the
 * Component Model threading feature (Wasmtime: {@code -W component-model-threading}).
 */
public final class WasiThreads {

    public static class Options {
        @Option(help = "Support java.lang.Thread on WASI with the cooperative threads of the Component Model. The component then needs a host with the component-model-threading feature.")//
        public static final HostedOptionKey<Boolean> WasiThreads = new HostedOptionKey<>(false);

        @Option(help = "Size in bytes of the shadow stack of a thread started on WASI when the thread does not ask for a size.")//
        public static final HostedOptionKey<Integer> WasiThreadStackSize = new HostedOptionKey<>(512 * 1024);
    }

    /** Whether threads are supported in this image. */
    public static final class Enabled implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return Options.WasiThreads.getValue();
        }
    }

    @Fold
    public static boolean enabled() {
        return Options.WasiThreads.getValue();
    }

    @Fold
    static int defaultStackSize() {
        return Options.WasiThreadStackSize.getValue();
    }

    private static final int NEW = 0;
    private static final int RUNNABLE = 1;
    private static final int RUNNING = 2;
    private static final int PARKED = 3;
    private static final int SLEEPING = 4;
    private static final int WAITING = 5;
    /** The initial thread after {@code main} returned, waiting for the other threads to end. */
    private static final int AWAITING_EXIT = 6;
    private static final int TERMINATED = 7;

    private static final long NO_DEADLINE = Long.MAX_VALUE;
    /** Space kept above the first frame of a thread: the entry function's own frame refers to it. */
    private static final int ENTRY_RESERVE = 1024;
    private static final int MIN_STACK_SIZE = 64 * 1024;

    /** The scheduling state of a Java thread. */
    public static final class Fiber {
        final Thread thread;
        /** Slot in {@link WasiThreadStacks}. */
        int slot;
        /** Index of the Component Model thread. */
        int index = -1;
        /** Start of the allocated stack memory, 0 for the default stack. */
        long stackMemory;
        /** Lowest stack pointer before a stack overflow is reported. */
        long stackBoundary;
        int state = NEW;
        /** The permit of {@code LockSupport}. */
        boolean permit;
        boolean interrupted;
        /** {@code System.nanoTime} value at which a timed wait ends. */
        long deadline = NO_DEADLINE;
        /** The object whose wait set the thread is in. */
        Object waitingOn;

        Fiber(Thread thread) {
            this.thread = thread;
        }
    }

    /** The table index of {@link #threadEntry}, set by the image builder. */
    private static FunctionPointerHolder entryPoint;

    private static Fiber current;
    /** Fibers by slot, for the entry function. */
    private static Fiber[] fibers = new Fiber[16];
    private static final ArrayDeque<Fiber> RUN_QUEUE = new ArrayDeque<>();
    /** Started fibers that are neither running nor runnable. */
    private static final ArrayList<Fiber> BLOCKED = new ArrayList<>();
    /** Fibers that ended and whose stack is freed once another thread runs. */
    private static final ArrayList<Fiber> DEAD = new ArrayList<>();
    /** Number of started threads that have not ended and are not daemons. */
    private static int liveNonDaemon;

    private WasiThreads() {
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public static void setEntryPoint(FunctionPointerHolder holder) {
        entryPoint = holder;
    }

    // Starting and ending threads

    /** The fiber of the running thread; the thread that started the program gets one on demand. */
    private static Fiber currentFiber() {
        if (current == null) {
            Fiber main = new Fiber(Thread.currentThread());
            main.slot = 0;
            main.index = WasiCalls.callI(WasiImports.THREAD_INDEX);
            main.stackBoundary = StackOverflowCheckImpl.stackBoundaryTL.get().rawValue();
            main.state = RUNNING;
            fibers[0] = main;
            WasiThreadAccess.setFiber(main.thread, main);
            current = main;
        }
        return current;
    }

    /** Implements {@code Thread.start0}. */
    public static void start(Thread thread, long requestedStackSize) {
        if (WasiThreadAccess.getFiber(thread) != null) {
            throw new IllegalThreadStateException();
        }
        currentFiber();
        freeDeadStacks();
        int size = requestedStackSize > 0 && requestedStackSize < Integer.MAX_VALUE ? Math.max((int) requestedStackSize, MIN_STACK_SIZE) : defaultStackSize();
        Pointer memory = WasiAllocator.singleton().allocate(size);
        if (memory.isNull()) {
            throw new OutOfMemoryError("Cannot allocate the stack of thread " + thread.getName());
        }
        Fiber fiber = new Fiber(thread);
        fiber.stackMemory = memory.rawValue();
        fiber.stackBoundary = fiber.stackMemory + StackOverflowCheck.singleton().yellowAndRedZoneSize();
        long base = (fiber.stackMemory + size - ENTRY_RESERVE) & ~15L;
        fiber.slot = WasiThreadStacks.allocateSlot(base);
        if (fiber.slot >= fibers.length) {
            Fiber[] grown = new Fiber[fibers.length * 2];
            System.arraycopy(fibers, 0, grown, 0, fibers.length);
            fibers = grown;
        }
        fibers[fiber.slot] = fiber;
        fiber.index = WasiCalls.callIII(WasiImports.THREAD_NEW_INDIRECT, (int) entryPoint.functionPointer.rawValue(), fiber.slot);
        if (fiber.index < 0) {
            fibers[fiber.slot] = null;
            WasiThreadStacks.releaseSlot(fiber.slot);
            WasiAllocator.singleton().free(memory);
            throw new OutOfMemoryError("The host cannot create another thread");
        }
        WasiThreadAccess.setFiber(thread, fiber);
        if (!thread.isDaemon()) {
            liveNonDaemon++;
        }
        fiber.state = RUNNABLE;
        RUN_QUEUE.add(fiber);
    }

    /**
     * The function a new Component Model thread starts in. It runs on whatever stack pointer the
     * thread that switched to it left in the global, so the first thing is to move to the
     * thread's own stack; until then nothing may depend on the stack (no stack overflow check, no
     * values kept in the frame).
     */
    @Uninterruptible(reason = "Runs before the thread is on its own stack.", calleeMustBe = false)
    private static void threadEntry(int slot) {
        WriteStackPointerNode.write(Word.pointer(WasiThreadStacks.base(slot)));
        run(slot);
    }

    @NeverInline("The first frame on the stack of a new thread.")
    private static void run(int slot) {
        Fiber me = fibers[slot];
        try {
            me.thread.run();
        } catch (Throwable t) {
            try {
                me.thread.getUncaughtExceptionHandler().uncaughtException(me.thread, t);
            } catch (Throwable ignored) {
                // Like the JVM, exceptions of the handler are dropped
            }
        }
        exit(me);
    }

    /** Ends the running thread: wakes who waits for it and hands the processor to the next thread. */
    private static void exit(Fiber me) {
        me.state = TERMINATED;
        if (!me.thread.isDaemon()) {
            liveNonDaemon--;
        }
        // Thread.join waits on the thread object
        wakeWaiters(me.thread, true);
        for (int i = BLOCKED.size() - 1; i >= 0; i--) {
            if (BLOCKED.get(i).state == AWAITING_EXIT) {
                wake(BLOCKED.get(i));
            }
        }
        DEAD.add(me);
        Fiber next = awaitRunnable();
        handOver(me.slot, next);
    }

    /** Last step of a thread: nothing of it may run on its stack after the next thread is set up. */
    @Uninterruptible(reason = "The thread-specific state belongs to the next thread already.", calleeMustBe = false)
    private static void handOver(int slot, Fiber next) {
        activate(next);
        WasiThreadStacks.setSaved(slot, 0);
        WasiCalls.callVI(WasiImports.THREAD_RESUME_LATER, next.index);
    }

    private static void freeDeadStacks() {
        for (int i = DEAD.size() - 1; i >= 0; i--) {
            Fiber dead = DEAD.get(i);
            if (dead != current) {
                DEAD.remove(i);
                fibers[dead.slot] = null;
                WasiThreadStacks.releaseSlot(dead.slot);
                WasiAllocator.singleton().free(Word.pointer(dead.stackMemory));
            }
        }
    }

    /**
     * Called when {@code main} has returned: like the JVM, the program goes on until all threads
     * that are not daemons have ended.
     */
    public static void awaitNonDaemonThreads() {
        if (current == null) {
            return;
        }
        Fiber me = current;
        while (liveNonDaemon > 0) {
            me.state = AWAITING_EXIT;
            me.deadline = NO_DEADLINE;
            block(me);
        }
    }

    /** Whether daemon threads are left when the program ends; they cannot be unwound. */
    public static boolean hasLiveThreads() {
        return !RUN_QUEUE.isEmpty() || !BLOCKED.isEmpty();
    }

    // Calls from the host

    /**
     * The host makes every call of an export (and of a callback) on a new thread of its own. Java
     * code sees the calls of the host as one thread, the one that started the program: its fiber
     * adopts the host's thread, so that the other threads can switch back to it.
     */
    public static void onHostEntry() {
        if (current != null) {
            current.index = WasiCalls.callI(WasiImports.THREAD_INDEX);
        }
    }

    /**
     * Lets the other threads run until all of them are blocked. Threads only run while the
     * component executes, so work that is ready has to happen before the component returns to
     * the host.
     */
    public static void drainRunnable() {
        if (current == null) {
            return;
        }
        while (!RUN_QUEUE.isEmpty()) {
            yieldCurrent();
        }
    }

    // Scheduling

    /** Makes a blocked fiber runnable. */
    private static void wake(Fiber fiber) {
        BLOCKED.remove(fiber);
        fiber.deadline = NO_DEADLINE;
        fiber.waitingOn = null;
        fiber.state = RUNNABLE;
        RUN_QUEUE.add(fiber);
    }

    /**
     * Suspends the running fiber, whose state the caller has set to a blocked state, until it is
     * woken.
     */
    private static void block(Fiber me) {
        BLOCKED.add(me);
        Fiber next = awaitRunnable();
        if (next == me) {
            me.state = RUNNING;
        } else {
            switchTo(me.slot, next);
        }
        freeDeadStacks();
    }

    /**
     * Takes the next runnable fiber off the queue. If there is none, waits for what can make one
     * runnable: the earliest timer, or an event of a pending host operation.
     */
    private static Fiber awaitRunnable() {
        while (true) {
            Fiber next = RUN_QUEUE.poll();
            if (next != null) {
                return next;
            }
            long now = System.nanoTime();
            long earliest = NO_DEADLINE;
            for (int i = BLOCKED.size() - 1; i >= 0; i--) {
                Fiber blocked = BLOCKED.get(i);
                if (blocked.deadline <= now) {
                    wake(blocked);
                } else if (blocked.deadline < earliest) {
                    earliest = blocked.deadline;
                }
            }
            if (!RUN_QUEUE.isEmpty()) {
                continue;
            }
            if (earliest == NO_DEADLINE) {
                // Only a host event can wake a thread
                if (!WitScheduler.idle(true)) {
                    deadlock();
                }
            } else if (WitScheduler.idle(false)) {
                // An event was handled; it may have woken a thread
                continue;
            } else {
                // Sleep in short steps while host operations are pending, their events have no timeout
                long nanos = earliest - now;
                WasiClocks.sleepNanos(WitScheduler.hasPendingRootWork() ? Math.min(nanos, 1_000_000L) : nanos);
            }
        }
    }

    private static void deadlock() {
        System.err.println("All threads are blocked and nothing can wake them: the program cannot make progress.");
        for (Fiber blocked : BLOCKED) {
            System.err.println("  " + blocked.thread.getName() + " (" + stateOf(blocked) + ")");
        }
        System.err.flush();
        WasiExit.exitWithCode(1);
    }

    /** Sets the thread-specific VM state to that of the fiber that runs next. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void activate(Fiber next) {
        next.state = RUNNING;
        current = next;
        WasiThreadStacks.setCurrent(next.slot);
        PlatformThreads.switchCurrentThreadObject(next.thread);
        StackOverflowCheckImpl.stackBoundaryTL.set(Word.unsigned(next.stackBoundary));
    }

    /**
     * Switches to another fiber and returns when this one is resumed. The stack pointer is a
     * global that the other threads change, so it is kept in a local and written back first thing
     * after the switch; nothing in between may touch the stack.
     */
    @NeverInline("The frame at which a suspended thread is walked.")
    @Uninterruptible(reason = "The stack pointer is not this thread's between the switch and its restoration.", calleeMustBe = false)
    private static void switchTo(int mySlot, Fiber next) {
        int index = next.index;
        activate(next);
        long stackPointer = KnownIntrinsics.readStackPointer().rawValue();
        WasiThreadStacks.setSaved(mySlot, stackPointer);
        WasiCalls.callII(WasiImports.THREAD_SUSPEND_THEN_RESUME, index);
        WriteStackPointerNode.write(Word.pointer(stackPointer));
        WasiThreadStacks.setSaved(mySlot, 0);
    }

    // Blocking operations

    /** Implements {@code Unsafe.park}. */
    public static void park(boolean isAbsolute, long time) {
        Fiber me = currentFiber();
        if (me.permit) {
            me.permit = false;
            return;
        }
        if (me.interrupted) {
            return;
        }
        long deadline = NO_DEADLINE;
        if (isAbsolute) {
            deadline = System.nanoTime() + (time - System.currentTimeMillis()) * 1_000_000L;
        } else if (time != 0) {
            deadline = System.nanoTime() + time;
        }
        if (deadline <= System.nanoTime()) {
            return;
        }
        me.deadline = deadline;
        me.state = PARKED;
        block(me);
        me.permit = false;
    }

    /** Implements {@code Unsafe.unpark}. */
    public static void unpark(Thread thread) {
        if (thread == null) {
            return;
        }
        currentFiber();
        Fiber fiber = (Fiber) WasiThreadAccess.getFiber(thread);
        if (fiber == null) {
            // Not started yet: there is nobody to give a permit to
            return;
        }
        if (fiber.state == PARKED) {
            wake(fiber);
        } else {
            fiber.permit = true;
        }
    }

    /** Implements {@code Thread.sleep}. */
    public static void sleep(long nanos) throws InterruptedException {
        Fiber me = currentFiber();
        if (me.interrupted) {
            me.interrupted = false;
            throw new InterruptedException();
        }
        if (nanos <= 0) {
            yieldCurrent();
            return;
        }
        long now = System.nanoTime();
        me.deadline = nanos > NO_DEADLINE - now - 1 ? NO_DEADLINE - 1 : now + nanos;
        me.state = SLEEPING;
        block(me);
        if (me.interrupted) {
            me.interrupted = false;
            throw new InterruptedException();
        }
    }

    /** Implements {@code Thread.yield}. */
    public static void yieldCurrent() {
        Fiber me = currentFiber();
        if (RUN_QUEUE.isEmpty()) {
            return;
        }
        me.state = RUNNABLE;
        RUN_QUEUE.add(me);
        Fiber next = RUN_QUEUE.poll();
        if (next == me) {
            me.state = RUNNING;
        } else {
            switchTo(me.slot, next);
        }
        freeDeadStacks();
    }

    /** Implements {@code Object.wait}. */
    public static void await(Object object, long timeoutMillis) throws InterruptedException {
        Fiber me = currentFiber();
        if (me.interrupted) {
            me.interrupted = false;
            throw new InterruptedException();
        }
        me.waitingOn = object;
        me.deadline = timeoutMillis <= 0 ? NO_DEADLINE : System.nanoTime() + timeoutMillis * 1_000_000L;
        me.state = WAITING;
        block(me);
        if (me.interrupted) {
            me.interrupted = false;
            throw new InterruptedException();
        }
    }

    /** Implements {@code Object.notify} and {@code Object.notifyAll}. */
    public static void wakeWaiters(Object object, boolean all) {
        // In the order the threads started to wait
        int i = 0;
        while (i < BLOCKED.size()) {
            Fiber blocked = BLOCKED.get(i);
            if (blocked.state == WAITING && blocked.waitingOn == object) {
                // Waking removes the fiber from the list, the next one moves to this index
                wake(blocked);
                if (!all) {
                    return;
                }
            } else {
                i++;
            }
        }
    }

    // Thread state

    public static void interrupt(Thread thread) {
        Fiber fiber = thread == Thread.currentThread() ? currentFiber() : (Fiber) WasiThreadAccess.getFiber(thread);
        if (fiber == null) {
            return;
        }
        fiber.interrupted = true;
        if (fiber.state == PARKED || fiber.state == SLEEPING || fiber.state == WAITING) {
            wake(fiber);
        }
    }

    public static boolean isInterrupted(Thread thread, boolean clear) {
        Fiber fiber = (Fiber) WasiThreadAccess.getFiber(thread);
        if (fiber == null) {
            return false;
        }
        boolean interrupted = fiber.interrupted;
        if (clear) {
            fiber.interrupted = false;
        }
        return interrupted;
    }

    public static boolean isAlive(Thread thread) {
        Fiber fiber = (Fiber) WasiThreadAccess.getFiber(thread);
        if (fiber == null) {
            // The thread that started the program before it used any thread operation
            return current == null && thread == Thread.currentThread();
        }
        return fiber.state != TERMINATED;
    }

    public static Thread.State getState(Thread thread) {
        Fiber fiber = (Fiber) WasiThreadAccess.getFiber(thread);
        if (fiber == null) {
            return thread == Thread.currentThread() ? Thread.State.RUNNABLE : Thread.State.NEW;
        }
        return stateOf(fiber);
    }

    private static Thread.State stateOf(Fiber fiber) {
        switch (fiber.state) {
            case NEW:
                return Thread.State.NEW;
            case PARKED:
            case WAITING:
            case AWAITING_EXIT:
                return fiber.deadline == NO_DEADLINE ? Thread.State.WAITING : Thread.State.TIMED_WAITING;
            case SLEEPING:
                return Thread.State.TIMED_WAITING;
            case TERMINATED:
                return Thread.State.TERMINATED;
            default:
                return Thread.State.RUNNABLE;
        }
    }
}
