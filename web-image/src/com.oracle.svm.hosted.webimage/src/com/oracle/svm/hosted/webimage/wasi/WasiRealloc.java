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
package com.oracle.svm.hosted.webimage.wasi;

import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.guest.staging.core.UnmanagedMemoryUtil;
import com.oracle.svm.hosted.webimage.wasm.gc.WasmAllocation;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.webimage.platform.WebImageWasiPlatform;
import com.oracle.svm.webimage.wasi.WasiAllocator;
import com.oracle.svm.webimage.wasi.WasiMemory;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

/**
 * The Canonical ABI allocator export.
 * <p>
 * The host calls {@code cabi_realloc} to allocate memory for lists and strings it passes to the
 * guest. All allocations of {@link WasmAllocation} are 8-byte aligned, which satisfies every
 * alignment the Canonical ABI can request.
 */
public final class WasiRealloc {

    private WasiRealloc() {
    }

    /**
     * Gives runtime code access to the allocator.
     */
    @SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
    public static final class Allocator implements WasiAllocator {
        @Override
        public void free(Pointer pointer) {
            WasmAllocation.doFree(pointer);
        }

        @Override
        public Pointer allocate(int size) {
            return WasmAllocation.doMalloc(Word.unsigned(Math.max(size, 1)));
        }
    }

    @WasmExport(value = "cabi_realloc", comment = "Canonical ABI realloc")
    @Platforms(WebImageWasiPlatform.class)
    public static int cabiRealloc(int oldPtr, int oldSize, @SuppressWarnings("unused") int align, int newSize) {
        // Even a zero-sized allocation must return a non-null pointer.
        Pointer newPtr = WasmAllocation.doMalloc(Word.unsigned(Math.max(newSize, 1)));
        if (newPtr.isNull()) {
            return 0;
        }
        if (oldPtr != 0) {
            Pointer old = WasiMemory.pointer(oldPtr);
            UnmanagedMemoryUtil.copyForward(old, newPtr, Word.unsigned(Math.min(oldSize, newSize)));
            WasmAllocation.doFree(old);
        }
        return WasiMemory.address(newPtr);
    }
}
