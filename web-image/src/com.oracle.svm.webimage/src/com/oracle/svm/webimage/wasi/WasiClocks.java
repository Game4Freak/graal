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
 * Access to {@code wasi:clocks}.
 */
public final class WasiClocks {

    private WasiClocks() {
    }

    /**
     * Wall clock time in milliseconds since the epoch.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long currentTimeMillis() {
        // record instant { seconds: s64, nanoseconds: u32 }
        Pointer instant = StackValue.get(Long.BYTES + Integer.BYTES);
        WasiCalls.callVI(WasiImports.SYSTEM_CLOCK_NOW, WasiMemory.address(instant));
        long seconds = instant.readLong(0);
        int nanoseconds = instant.readInt(Long.BYTES);
        return seconds * 1_000L + (nanoseconds & 0xFFFF_FFFFL) / 1_000_000L;
    }

    /**
     * Blocks the current task for the given duration using {@code monotonic-clock.wait-for}.
     */
    public static void sleepNanos(long nanos) {
        if (nanos > 0) {
            WasiCalls.callVL(WasiImports.MONOTONIC_CLOCK_WAIT_FOR, nanos);
        }
    }

    /**
     * Monotonic clock in nanoseconds.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long nanoTime() {
        return WasiCalls.callL(WasiImports.MONOTONIC_CLOCK_NOW);
    }
}
