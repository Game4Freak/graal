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

/**
 * A WIT {@code stream<T>} value.
 * <p>
 * A stream created with {@link #create} owns both ends: Java writes to it and the readable end is
 * handed to the other side when the stream is passed to a WIT function. A stream received from a
 * WIT function (as a parameter of an export or a result of an import) only has a readable end that
 * Java reads from. Reads and writes are asynchronous and return a {@link WitFuture}; use
 * {@code join()} from synchronous code.
 *
 * @since 26.0
 */
public final class WitStream<T> {

    /** Copy result codes in the low 4 bits of a copy event payload. */
    public static final int COMPLETED = 0;
    public static final int DROPPED = 1;
    public static final int CANCELLED = 2;

    private final WitStreamOps ops;
    private final WitElementCodec<T> codec;
    private int readable;
    private int writable;
    private boolean readEnded;

    private WitStream(WitStreamOps ops, WitElementCodec<T> codec, int readable, int writable) {
        this.ops = ops;
        this.codec = codec;
        this.readable = readable;
        this.writable = writable;
    }

    /**
     * Creates a new stream owned by Java (used by generated factory methods).
     */
    public static <T> WitStream<T> create(WitStreamOps ops, WitElementCodec<T> codec) {
        long pair = ops.streamNew();
        return new WitStream<>(ops, codec, (int) pair, (int) (pair >>> 32));
    }

    /**
     * Wraps a readable end received from the other side (used by generated bindings).
     */
    public static <T> WitStream<T> received(WitStreamOps ops, WitElementCodec<T> codec, int readable) {
        return new WitStream<>(ops, codec, readable, -1);
    }

    /**
     * Gives up the readable end so that it can be passed to the other side (used by generated
     * bindings when lowering).
     */
    public int transferReadable() {
        if (readable < 0) {
            throw new IllegalStateException("The readable end was already transferred");
        }
        int handle = readable;
        readable = -1;
        return handle;
    }

    public boolean canRead() {
        return readable >= 0 && !readEnded;
    }

    public boolean canWrite() {
        return writable >= 0;
    }

    /**
     * Reads up to {@code max} elements.
     *
     * @return the elements read; an empty list means the writer dropped the stream
     */
    public WitFuture<List<T>> read(int max) {
        if (readable < 0) {
            throw new IllegalStateException("The stream has no readable end");
        }
        if (readEnded || max <= 0) {
            return WitFuture.completed(new ArrayList<>());
        }
        int size = Math.max(codec.size(), 1);
        int buffer = WitMemory.allocate(size * max, codec.align());
        WitFuture<List<T>> future = new WitFuture<>();
        int payload = ops.read(readable, buffer, max);
        if (payload == WitScheduler.BLOCKED) {
            WitScheduler.register(readable, (code, eventPayload) -> {
                finishRead(future, buffer, eventPayload);
                return readEnded;
            });
        } else {
            finishRead(future, buffer, payload);
            if (readEnded) {
                // Nothing pending, the handle can go right away
                closeReadable();
            }
        }
        return future;
    }

    /**
     * Reads up to {@code max} bytes of a byte stream.
     *
     * @return the bytes read; an empty array means the writer dropped the stream
     */
    public WitFuture<byte[]> readBytes(int max) {
        if (codec != WitElementCodec.BYTE) {
            throw new IllegalStateException("Not a byte stream");
        }
        if (readable < 0) {
            throw new IllegalStateException("The stream has no readable end");
        }
        if (readEnded || max <= 0) {
            return WitFuture.completed(new byte[0]);
        }
        int buffer = WitMemory.allocate(max, 1);
        WitFuture<byte[]> future = new WitFuture<>();
        int payload = ops.read(readable, buffer, max);
        if (payload == WitScheduler.BLOCKED) {
            WitScheduler.register(readable, (code, eventPayload) -> {
                finishReadBytes(future, buffer, eventPayload);
                return readEnded;
            });
        } else {
            finishReadBytes(future, buffer, payload);
            if (readEnded) {
                closeReadable();
            }
        }
        return future;
    }

    private void finishRead(WitFuture<List<T>> future, int buffer, int payload) {
        int count = payload >>> 4;
        List<T> items = new ArrayList<>(count);
        WitArena.push();
        try {
            for (int i = 0; i < count; i++) {
                items.add(codec.lift(buffer + i * Math.max(codec.size(), 1)));
            }
        } finally {
            WitArena.pop();
            WitMemory.free(buffer);
        }
        if ((payload & 0xF) == DROPPED) {
            readEnded = true;
        }
        future.complete(items);
    }

    private void finishReadBytes(WitFuture<byte[]> future, int buffer, int payload) {
        int count = payload >>> 4;
        byte[] bytes = new byte[count];
        WitMemory.copyFromMemory(buffer, bytes, 0, count);
        WitMemory.free(buffer);
        if ((payload & 0xF) == DROPPED) {
            readEnded = true;
        }
        future.complete(bytes);
    }

    private void closeReadable() {
        if (readable >= 0) {
            ops.dropReadable(readable);
            readable = -1;
        }
    }

    /**
     * Writes all elements.
     *
     * @return the number of elements accepted; fewer than offered means the reader dropped the
     *         stream
     */
    public WitFuture<Integer> write(List<T> items) {
        if (writable < 0) {
            throw new IllegalStateException("The stream has no writable end");
        }
        int size = Math.max(codec.size(), 1);
        int buffer = WitMemory.allocate(size * Math.max(items.size(), 1), codec.align());
        WitArena.push();
        for (int i = 0; i < items.size(); i++) {
            codec.lower(items.get(i), buffer + i * size);
        }
        List<Integer> owned = WitArena.detach();
        return writeBuffer(buffer, items.size(), owned);
    }

    /**
     * Writes all bytes of a byte stream.
     */
    public WitFuture<Integer> writeBytes(byte[] bytes) {
        if (codec != WitElementCodec.BYTE) {
            throw new IllegalStateException("Not a byte stream");
        }
        if (writable < 0) {
            throw new IllegalStateException("The stream has no writable end");
        }
        int buffer = WitMemory.allocate(Math.max(bytes.length, 1), 1);
        WitMemory.copyToMemory(bytes, 0, buffer, bytes.length);
        return writeBuffer(buffer, bytes.length, new ArrayList<>());
    }

    private WitFuture<Integer> writeBuffer(int buffer, int count, List<Integer> owned) {
        WitFuture<Integer> future = new WitFuture<>();
        int size = Math.max(codec.size(), 1);
        int[] written = {0};
        WitScheduler.EventHandler handler = new WitScheduler.EventHandler() {
            @Override
            public boolean onEvent(int code, int payload) {
                return progress(payload);
            }

            boolean progress(int payload) {
                written[0] += payload >>> 4;
                if ((payload & 0xF) == DROPPED || written[0] >= count) {
                    WitMemory.free(buffer);
                    WitArena.freeAll(owned);
                    future.complete(written[0]);
                    return true;
                }
                int next = ops.write(writable, buffer + written[0] * size, count - written[0]);
                if (next == WitScheduler.BLOCKED) {
                    WitScheduler.register(writable, this);
                    return true;
                }
                return progress(next);
            }
        };
        int payload = ops.write(writable, buffer, count);
        if (payload == WitScheduler.BLOCKED) {
            WitScheduler.register(writable, handler);
        } else {
            handler.onEvent(WitScheduler.EVENT_STREAM_WRITE, payload);
        }
        return future;
    }

    /**
     * Drops the ends still owned by this object. Writers must close to signal the end of the
     * stream.
     */
    public void close() {
        if (writable >= 0) {
            ops.dropWritable(writable);
            writable = -1;
        }
        closeReadable();
    }
}
