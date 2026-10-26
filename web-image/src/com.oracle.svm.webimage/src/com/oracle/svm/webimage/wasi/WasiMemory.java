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

import static com.oracle.svm.shared.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

import java.nio.charset.StandardCharsets;

import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.impl.Word;

import com.oracle.svm.core.hub.DynamicHub;
import com.oracle.svm.core.hub.DynamicHubIntrinsics;
import com.oracle.svm.core.hub.LayoutEncoding;
import com.oracle.svm.shared.Uninterruptible;

/**
 * Helpers to access raw linear memory with the 32-bit addresses used by the Canonical ABI.
 */
public final class WasiMemory {

    private WasiMemory() {
    }

    /** Converts a Canonical ABI address into a pointer. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static Pointer pointer(int address) {
        return Word.pointer(address & 0xFFFF_FFFFL);
    }

    /** Converts a pointer into a Canonical ABI address. */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static int address(PointerBase pointer) {
        return (int) pointer.rawValue();
    }

    /**
     * Address of the first element of the given byte array.
     * <p>
     * The linear memory backend uses a non-moving collector. The array must stay reachable while
     * the address is in use.
     */
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public static int arrayDataAddress(byte[] array) {
        DynamicHub hub = DynamicHubIntrinsics.readHub(array);
        UnsignedWord baseOffset = LayoutEncoding.getArrayBaseOffset(hub.getLayoutEncoding());
        return address(Word.objectToUntrackedPointer(array).add(baseOffset));
    }

    public static int readInt(int address, int offset) {
        return pointer(address).readInt(offset);
    }

    public static long readLong(int address, int offset) {
        return pointer(address).readLong(offset);
    }

    /**
     * Copies {@code length} bytes starting at {@code address} into a new byte array.
     */
    public static byte[] readBytes(int address, int length) {
        byte[] bytes = new byte[length];
        Pointer p = pointer(address);
        for (int i = 0; i < length; i++) {
            bytes[i] = p.readByte(i);
        }
        return bytes;
    }

    /**
     * Decodes a Canonical ABI {@code string} (UTF-8 encoded) stored at the given address.
     */
    public static String readString(int address, int codeUnits) {
        return new String(readBytes(address, codeUnits), StandardCharsets.UTF_8);
    }

    /**
     * Decodes the {@code string} whose (pointer, length) pair is stored at {@code address}.
     */
    public static String readStringAt(int address) {
        return readString(readInt(address, 0), readInt(address, 4));
    }
}
