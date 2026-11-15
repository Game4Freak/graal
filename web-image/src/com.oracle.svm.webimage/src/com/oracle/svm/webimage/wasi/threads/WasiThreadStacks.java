/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.webimage.wasi.threads;

import com.oracle.svm.shared.Uninterruptible;

/**
 * The shadow stacks of all threads, in a form the garbage collector and the stack walker can read
 * without allocating: parallel arrays indexed by a thread's slot.
 * <p>
 * Slot 0 is the thread that started the program; it runs on the shadow stack laid out at build
 * time and has no entry in {@link #bases} (0 stands for "the default stack base"). Every other
 * thread has a stack allocated from the unmanaged heap.
 *
 * @see WasiThreads
 */
public final class WasiThreadStacks {

    private WasiThreadStacks() {
    }

    /**
     * Stack pointer at which a suspended thread can be walked, 0 if the thread is running, has
     * not run yet or has ended.
     */
    private static long[] savedStackPointers = new long[16];
    /** Address at which a walk of the thread's stack ends, 0 for the default stack. */
    private static long[] bases = new long[16];
    private static int count = 1;
    private static int current;

    static int allocateSlot(long base) {
        for (int i = 1; i < count; i++) {
            if (bases[i] == 0) {
                bases[i] = base;
                savedStackPointers[i] = 0;
                return i;
            }
        }
        if (count == bases.length) {
            long[] newBases = new long[count * 2];
            long[] newSaved = new long[count * 2];
            System.arraycopy(bases, 0, newBases, 0, count);
            System.arraycopy(savedStackPointers, 0, newSaved, 0, count);
            // The collector may run between the two assignments; both arrays cover all slots
            savedStackPointers = newSaved;
            bases = newBases;
        }
        bases[count] = base;
        savedStackPointers[count] = 0;
        return count++;
    }

    static void releaseSlot(int slot) {
        savedStackPointers[slot] = 0;
        bases[slot] = 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void setSaved(int slot, long stackPointer) {
        savedStackPointers[slot] = stackPointer;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static void setCurrent(int slot) {
        current = slot;
    }

    /** Number of slots, including unused ones. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int count() {
        return count;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int current() {
        return current;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long savedStackPointer(int slot) {
        return savedStackPointers[slot];
    }

    /** Where a walk of the slot's stack ends; 0 means the default stack base. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long base(int slot) {
        return bases[slot];
    }
}
