/*
 * Copyright (c) 2026, 2026, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.webimage.wasi;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.word.Pointer;

import com.oracle.svm.shared.Uninterruptible;

/**
 * Blocking operations on Component Model streams and futures.
 * <p>
 * The runtime is single-threaded and the Java standard library is blocking, so stream and future
 * operations use the asynchronous lowering of the built-ins and, if the operation did not complete
 * immediately, block the current task with {@code waitable-set.wait} until it does.
 */
public final class WasiStreams {

    private WasiStreams() {
    }

    /** Return value of an asynchronous built-in that did not complete immediately. */
    public static final int BLOCKED = -1;

    /** Copy result codes, stored in the low 4 bits of a copy event payload. */
    public static final int COMPLETED = 0;
    public static final int DROPPED = 1;
    public static final int CANCELLED = 2;

    public static int copyResultCode(int payload) {
        return payload & 0xF;
    }

    public static int copyResultCount(int payload) {
        return payload >>> 4;
    }

    /**
     * Blocks until the given waitable (a stream end, future end, or subtask) delivers an event and
     * returns the event payload.
     */
    @Uninterruptible(reason = "Does not allocate.", calleeMustBe = false)
    public static int blockOn(int waitable) {
        int set = WasiCalls.callI(WasiImports.WAITABLE_SET_NEW);
        WasiCalls.callVII(WasiImports.WAITABLE_JOIN, waitable, set);
        Pointer event = StackValue.get(2 * Integer.BYTES);
        WasiCalls.callIII(WasiImports.WAITABLE_SET_WAIT, set, WasiMemory.address(event));
        WasiCalls.callVII(WasiImports.WAITABLE_JOIN, waitable, 0);
        WasiCalls.callVI(WasiImports.WAITABLE_SET_DROP, set);
        // The event consists of the waitable index followed by the payload
        return event.readInt(Integer.BYTES);
    }

    /**
     * Writes all bytes in {@code [address, address + length)} to the writable stream end of the
     * standard output ({@code stderr == false}) or standard error stream.
     *
     * @return the number of bytes written, which is smaller than {@code length} only if the
     *         readable end was dropped.
     */
    @Uninterruptible(reason = "Caller passes untracked pointers.", calleeMustBe = false)
    public static int writeAll(boolean stderr, int writable, int address, int length) {
        int written = 0;
        while (written < length) {
            int payload;
            if (stderr) {
                payload = WasiCalls.callIIII(WasiImports.STDERR_STREAM_WRITE, writable, address + written, length - written);
            } else {
                payload = WasiCalls.callIIII(WasiImports.STDOUT_STREAM_WRITE, writable, address + written, length - written);
            }
            if (payload == BLOCKED) {
                payload = blockOn(writable);
            }
            written += copyResultCount(payload);
            if (copyResultCode(payload) == DROPPED) {
                break;
            }
        }
        return written;
    }

    /**
     * Reads the readable future end into {@code address} (blocking) and drops it.
     */
    @Uninterruptible(reason = "Caller passes untracked pointers.", calleeMustBe = false)
    public static void awaitAndDropFuture(boolean stderr, int future, int address) {
        int payload;
        if (stderr) {
            payload = WasiCalls.callIII(WasiImports.STDERR_FUTURE_READ, future, address);
        } else {
            payload = WasiCalls.callIII(WasiImports.STDOUT_FUTURE_READ, future, address);
        }
        if (payload == BLOCKED) {
            blockOn(future);
        }
        if (stderr) {
            WasiCalls.callVI(WasiImports.STDERR_FUTURE_DROP_READABLE, future);
        } else {
            WasiCalls.callVI(WasiImports.STDOUT_FUTURE_DROP_READABLE, future);
        }
    }
}
