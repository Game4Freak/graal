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
 * Access to {@code wasi:cli/environment}.
 * <p>
 * The host allocates the returned lists and strings through {@code cabi_realloc} in the guest's
 * memory; they are freed after decoding.
 */
public final class WasiEnvironment {

    private WasiEnvironment() {
    }

    /** Size of a lowered {@code string} or {@code list} (pointer and length). */
    private static final int LIST_SIZE = 2 * Integer.BYTES;

    public static String[] getArguments() {
        Pointer result = StackValue.get(LIST_SIZE);
        WasiCalls.callVI(WasiImports.GET_ARGUMENTS, WasiMemory.address(result));
        int base = result.readInt(0);
        int count = result.readInt(Integer.BYTES);
        String[] args = new String[count];
        for (int i = 0; i < count; i++) {
            args[i] = readAndFreeString(base + i * LIST_SIZE);
        }
        WasiAllocator.freeAddress(base);
        return args;
    }

    /**
     * @return the environment as alternating names and values.
     */
    public static String[] getEnvironment() {
        Pointer result = StackValue.get(LIST_SIZE);
        WasiCalls.callVI(WasiImports.GET_ENVIRONMENT, WasiMemory.address(result));
        int base = result.readInt(0);
        int count = result.readInt(Integer.BYTES);
        String[] env = new String[2 * count];
        for (int i = 0; i < count; i++) {
            int tuple = base + i * 2 * LIST_SIZE;
            env[2 * i] = readAndFreeString(tuple);
            env[2 * i + 1] = readAndFreeString(tuple + LIST_SIZE);
        }
        WasiAllocator.freeAddress(base);
        return env;
    }

    /**
     * @return the initial working directory or {@code null} if the host did not provide one.
     */
    public static String getInitialCwd() {
        // option<string>: discriminant followed by the string at the string's alignment
        Pointer result = StackValue.get(Integer.BYTES + LIST_SIZE);
        WasiCalls.callVI(WasiImports.GET_INITIAL_CWD, WasiMemory.address(result));
        if (result.readInt(0) == 0) {
            return null;
        }
        return readAndFreeString(WasiMemory.address(result) + Integer.BYTES);
    }

    /**
     * Decodes the string whose (pointer, length) pair is stored at {@code address} and frees the
     * host-allocated string data.
     */
    private static String readAndFreeString(int address) {
        int data = WasiMemory.readInt(address, 0);
        String s = WasiMemory.readString(data, WasiMemory.readInt(address, Integer.BYTES));
        WasiAllocator.freeAddress(data);
        return s;
    }

    public static String getWorkingDirectory() {
        String cwd = getInitialCwd();
        return cwd == null ? "/" : cwd;
    }
}
