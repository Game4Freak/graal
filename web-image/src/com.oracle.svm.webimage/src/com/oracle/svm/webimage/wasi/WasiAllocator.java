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

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.word.Pointer;

import jdk.graal.compiler.api.replacements.Fold;

/**
 * Frees memory that the host allocated in the guest through {@code cabi_realloc}.
 * <p>
 * The allocator lives in the hosted project, the implementation is registered as an image
 * singleton by the WASI feature.
 */
public interface WasiAllocator {

    @Fold
    static WasiAllocator singleton() {
        return ImageSingletons.lookup(WasiAllocator.class);
    }

    void free(Pointer pointer);

    /**
     * Allocates unmanaged memory in the guest heap (8-byte aligned), or returns a null pointer.
     */
    Pointer allocate(int size);

    /**
     * Frees the buffer at the given Canonical ABI address, if it is not null.
     */
    static void freeAddress(int address) {
        if (address != 0) {
            singleton().free(WasiMemory.pointer(address));
        }
    }
}
