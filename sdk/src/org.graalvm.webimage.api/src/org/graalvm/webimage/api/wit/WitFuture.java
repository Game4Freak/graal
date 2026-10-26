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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A value that becomes available later, the Java representation of asynchronous WIT functions and
 * of {@code future<T>} values.
 * <p>
 * The runtime is single-threaded: continuations run on the thread that completes the future, and
 * {@link #join()} blocks by letting the {@link WitScheduler} wait for host events. A future that
 * is already complete can be inspected with {@link #isDone()} and {@link #get()}.
 *
 * @param <T> the result type ({@link Void} for functions without result)
 * @since 26.0
 */
public final class WitFuture<T> {

    private boolean done;
    private T value;
    private Throwable failure;
    private List<BiConsumer<? super T, ? super Throwable>> continuations;

    public WitFuture() {
    }

    public static <T> WitFuture<T> completed(T value) {
        WitFuture<T> f = new WitFuture<>();
        f.complete(value);
        return f;
    }

    public static <T> WitFuture<T> failed(Throwable failure) {
        WitFuture<T> f = new WitFuture<>();
        f.completeExceptionally(failure);
        return f;
    }

    public boolean isDone() {
        return done;
    }

    public boolean isFailed() {
        return done && failure != null;
    }

    /**
     * Completes the future and runs the registered continuations.
     *
     * @return false if the future was already complete
     */
    public boolean complete(T result) {
        return finish(result, null);
    }

    public boolean completeExceptionally(Throwable cause) {
        return finish(null, cause);
    }

    private boolean finish(T result, Throwable cause) {
        if (done) {
            return false;
        }
        done = true;
        value = result;
        failure = cause;
        List<BiConsumer<? super T, ? super Throwable>> toRun = continuations;
        continuations = null;
        if (toRun != null) {
            for (BiConsumer<? super T, ? super Throwable> c : toRun) {
                c.accept(result, cause);
            }
        }
        return true;
    }

    /**
     * The result of a completed future.
     *
     * @throws NoSuchElementException if the future is not complete
     * @throws RuntimeException the failure, if the future failed
     */
    public T get() {
        if (!done) {
            throw new NoSuchElementException("Future is not complete");
        }
        if (failure != null) {
            if (failure instanceof RuntimeException r) {
                throw r;
            }
            if (failure instanceof Error e) {
                throw e;
            }
            throw new RuntimeException(failure);
        }
        return value;
    }

    /**
     * Waits for the future to complete by processing host events, then returns its result.
     * <p>
     * Only allowed from code that may block: synchronous exports and code running under the main
     * entry point. Callback-driven asynchronous exports must use continuations instead.
     */
    public T join() {
        if (!done) {
            WitScheduler.blockOn(this);
        }
        return get();
    }

    /**
     * Registers a continuation that runs when the future completes (immediately if it already
     * has).
     */
    public WitFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> continuation) {
        if (done) {
            continuation.accept(value, failure);
        } else {
            if (continuations == null) {
                continuations = new ArrayList<>(2);
            }
            continuations.add(continuation);
        }
        return this;
    }

    public WitFuture<Void> thenAccept(Consumer<? super T> consumer) {
        return thenApply(v -> {
            consumer.accept(v);
            return null;
        });
    }

    public <R> WitFuture<R> thenApply(Function<? super T, ? extends R> function) {
        WitFuture<R> result = new WitFuture<>();
        whenComplete((v, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                result.complete(function.apply(v));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    public <R> WitFuture<R> thenCompose(Function<? super T, ? extends WitFuture<R>> function) {
        WitFuture<R> result = new WitFuture<>();
        whenComplete((v, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                function.apply(v).whenComplete((r, f2) -> {
                    if (f2 != null) {
                        result.completeExceptionally(f2);
                    } else {
                        result.complete(r);
                    }
                });
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }
}
