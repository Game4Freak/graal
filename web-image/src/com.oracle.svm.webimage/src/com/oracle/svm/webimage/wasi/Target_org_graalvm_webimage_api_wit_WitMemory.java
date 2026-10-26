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

import org.graalvm.nativeimage.Platforms;
import org.graalvm.webimage.api.wit.WitMemory;
import org.graalvm.word.Pointer;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.webimage.platform.WebImageWasiPlatform;

/**
 * Implementation of {@link WitMemory} for the WASI target.
 */
@TargetClass(WitMemory.class)
@Platforms(WebImageWasiPlatform.class)
final class Target_org_graalvm_webimage_api_wit_WitMemory {

    @Substitute
    public static void ensureInitialized() {
        WasiRuntime.ensureInitialized();
    }

    @Substitute
    public static int allocate(int size, @SuppressWarnings("unused") int align) {
        Pointer p = WasiAllocator.singleton().allocate(size);
        if (p.isNull()) {
            throw new OutOfMemoryError("Could not allocate " + size + " bytes of linear memory");
        }
        return WasiMemory.address(p);
    }

    @Substitute
    public static void free(int address) {
        WasiAllocator.freeAddress(address);
    }

    @Substitute
    public static byte readByte(int address) {
        return WasiMemory.pointer(address).readByte(0);
    }

    @Substitute
    public static short readShort(int address) {
        return WasiMemory.pointer(address).readShort(0);
    }

    @Substitute
    public static int readInt(int address) {
        return WasiMemory.pointer(address).readInt(0);
    }

    @Substitute
    public static long readLong(int address) {
        return WasiMemory.pointer(address).readLong(0);
    }

    @Substitute
    public static float readFloat(int address) {
        return WasiMemory.pointer(address).readFloat(0);
    }

    @Substitute
    public static double readDouble(int address) {
        return WasiMemory.pointer(address).readDouble(0);
    }

    @Substitute
    public static void writeByte(int address, byte value) {
        WasiMemory.pointer(address).writeByte(0, value);
    }

    @Substitute
    public static void writeShort(int address, short value) {
        WasiMemory.pointer(address).writeShort(0, value);
    }

    @Substitute
    public static void writeInt(int address, int value) {
        WasiMemory.pointer(address).writeInt(0, value);
    }

    @Substitute
    public static void writeLong(int address, long value) {
        WasiMemory.pointer(address).writeLong(0, value);
    }

    @Substitute
    public static void writeFloat(int address, float value) {
        WasiMemory.pointer(address).writeFloat(0, value);
    }

    @Substitute
    public static void writeDouble(int address, double value) {
        WasiMemory.pointer(address).writeDouble(0, value);
    }

    @Substitute
    public static void copyToMemory(byte[] source, int sourceOffset, int address, int length) {
        Pointer p = WasiMemory.pointer(address);
        for (int i = 0; i < length; i++) {
            p.writeByte(i, source[sourceOffset + i]);
        }
    }

    @Substitute
    public static void copyFromMemory(int address, byte[] target, int targetOffset, int length) {
        Pointer p = WasiMemory.pointer(address);
        for (int i = 0; i < length; i++) {
            target[targetOffset + i] = p.readByte(i);
        }
    }
}
