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

/**
 * Access to {@code wasi:random/random}.
 */
public final class WasiRandom {

    private WasiRandom() {
    }

    /**
     * Fills the array with cryptographically secure random bytes.
     */
    public static void nextBytes(byte[] bytes) {
        int offset = 0;
        while (offset < bytes.length) {
            // list<u8>: pointer and length, allocated by the host through cabi_realloc
            Pointer result = StackValue.get(2 * Integer.BYTES);
            WasiCalls.callVLI(WasiImports.GET_RANDOM_BYTES, bytes.length - offset, WasiMemory.address(result));
            int address = result.readInt(0);
            int length = result.readInt(Integer.BYTES);
            if (length == 0) {
                WasiAllocator.freeAddress(address);
                break;
            }
            Pointer data = WasiMemory.pointer(address);
            for (int i = 0; i < length; i++) {
                bytes[offset + i] = data.readByte(i);
            }
            offset += length;
            WasiAllocator.freeAddress(address);
        }
    }

    public static long nextLong() {
        return WasiCalls.callL(WasiImports.GET_RANDOM_U64);
    }

    /**
     * A uniformly distributed double in {@code [0, 1)}.
     */
    public static double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }
}
