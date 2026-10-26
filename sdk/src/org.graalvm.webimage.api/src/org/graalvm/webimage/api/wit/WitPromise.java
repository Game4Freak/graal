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

/**
 * A WIT {@code future<T>} value: a single value delivered once.
 * <p>
 * A promise created with {@link #create} is fulfilled by Java with {@link #write} and its readable
 * end is handed to the other side when passed to a WIT function. A promise received from a WIT
 * function is read with {@link #read()}.
 *
 * @since 26.0
 */
public final class WitPromise<T> {

    private final WitFutureOps ops;
    private final WitElementCodec<T> codec;
    private int readable;
    private int writable;

    private WitPromise(WitFutureOps ops, WitElementCodec<T> codec, int readable, int writable) {
        this.ops = ops;
        this.codec = codec;
        this.readable = readable;
        this.writable = writable;
    }

    public static <T> WitPromise<T> create(WitFutureOps ops, WitElementCodec<T> codec) {
        long pair = ops.futureNew();
        return new WitPromise<>(ops, codec, (int) pair, (int) (pair >>> 32));
    }

    public static <T> WitPromise<T> received(WitFutureOps ops, WitElementCodec<T> codec, int readable) {
        return new WitPromise<>(ops, codec, readable, -1);
    }

    public int transferReadable() {
        if (readable < 0) {
            throw new IllegalStateException("The readable end was already transferred");
        }
        int handle = readable;
        readable = -1;
        return handle;
    }

    /**
     * Reads the value; the readable end is dropped afterwards.
     *
     * @return a future that fails if the writer dropped the promise without a value
     */
    public WitFuture<T> read() {
        if (readable < 0) {
            throw new IllegalStateException("The promise has no readable end");
        }
        int buffer = WitMemory.allocate(Math.max(codec.size(), 1), codec.align());
        WitFuture<T> future = new WitFuture<>();
        int payload = ops.read(readable, buffer);
        if (payload == WitScheduler.BLOCKED) {
            WitScheduler.register(readable, (code, eventPayload) -> {
                finishRead(future, buffer, eventPayload);
                return true;
            });
        } else {
            finishRead(future, buffer, payload);
        }
        return future;
    }

    private void finishRead(WitFuture<T> future, int buffer, int payload) {
        try {
            if ((payload & 0xF) == WitStream.COMPLETED) {
                WitArena.push();
                try {
                    future.complete(codec.lift(buffer));
                } finally {
                    WitArena.pop();
                }
            } else {
                future.completeExceptionally(new IllegalStateException("The promise was dropped without a value"));
            }
        } finally {
            WitMemory.free(buffer);
            ops.dropReadable(readable);
            readable = -1;
        }
    }

    /**
     * Delivers the value; the writable end is dropped afterwards.
     *
     * @return true if the reader received the value, false if it dropped its end
     */
    public WitFuture<Boolean> write(T value) {
        if (writable < 0) {
            throw new IllegalStateException("The promise has no writable end");
        }
        int buffer = WitMemory.allocate(Math.max(codec.size(), 1), codec.align());
        WitArena.push();
        codec.lower(value, buffer);
        java.util.List<Integer> owned = WitArena.detach();
        WitFuture<Boolean> future = new WitFuture<>();
        int payload = ops.write(writable, buffer);
        if (payload == WitScheduler.BLOCKED) {
            WitScheduler.register(writable, (code, eventPayload) -> {
                finishWrite(future, buffer, owned, eventPayload);
                return true;
            });
        } else {
            finishWrite(future, buffer, owned, payload);
        }
        return future;
    }

    private void finishWrite(WitFuture<Boolean> future, int buffer, java.util.List<Integer> owned, int payload) {
        WitMemory.free(buffer);
        WitArena.freeAll(owned);
        ops.dropWritable(writable);
        writable = -1;
        future.complete((payload & 0xF) == WitStream.COMPLETED);
    }

    /**
     * Drops the ends still owned by this object.
     */
    public void close() {
        if (writable >= 0) {
            ops.dropWritable(writable);
            writable = -1;
        }
        if (readable >= 0) {
            ops.dropReadable(readable);
            readable = -1;
        }
    }
}
