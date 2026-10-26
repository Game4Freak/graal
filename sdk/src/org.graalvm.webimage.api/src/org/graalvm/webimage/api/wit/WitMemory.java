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
package org.graalvm.webimage.api.wit;

/**
 * Access to the linear memory of the WASI component for lowering and lifting Canonical ABI
 * values.
 * <p>
 * Addresses are 32-bit and represented as {@code int}. The methods are only functional in an
 * image compiled for the WASI target; on other platforms they throw
 * {@link UnsupportedOperationException}.
 *
 * @since 26.0
 */
public final class WitMemory {

    private WitMemory() {
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Only available in an image compiled for the WASI target");
    }

    /**
     * Makes sure the Java runtime is initialized. Must be called at the start of exported
     * functions that may run before the main method.
     */
    public static void ensureInitialized() {
        throw unsupported();
    }

    /**
     * Allocates {@code size} bytes in linear memory, aligned to at least 8 bytes.
     *
     * @return the address of the allocation
     */
    public static int allocate(@SuppressWarnings("unused") int size, @SuppressWarnings("unused") int align) {
        throw unsupported();
    }

    /**
     * Frees memory allocated with {@link #allocate(int, int)} or by the host through
     * {@code cabi_realloc}.
     */
    public static void free(@SuppressWarnings("unused") int address) {
        throw unsupported();
    }

    public static byte readByte(@SuppressWarnings("unused") int address) {
        throw unsupported();
    }

    public static short readShort(@SuppressWarnings("unused") int address) {
        throw unsupported();
    }

    public static int readInt(@SuppressWarnings("unused") int address) {
        throw unsupported();
    }

    public static long readLong(@SuppressWarnings("unused") int address) {
        throw unsupported();
    }

    public static float readFloat(@SuppressWarnings("unused") int address) {
        throw unsupported();
    }

    public static double readDouble(@SuppressWarnings("unused") int address) {
        throw unsupported();
    }

    public static void writeByte(@SuppressWarnings("unused") int address, @SuppressWarnings("unused") byte value) {
        throw unsupported();
    }

    public static void writeShort(@SuppressWarnings("unused") int address, @SuppressWarnings("unused") short value) {
        throw unsupported();
    }

    public static void writeInt(@SuppressWarnings("unused") int address, @SuppressWarnings("unused") int value) {
        throw unsupported();
    }

    public static void writeLong(@SuppressWarnings("unused") int address, @SuppressWarnings("unused") long value) {
        throw unsupported();
    }

    public static void writeFloat(@SuppressWarnings("unused") int address, @SuppressWarnings("unused") float value) {
        throw unsupported();
    }

    public static void writeDouble(@SuppressWarnings("unused") int address, @SuppressWarnings("unused") double value) {
        throw unsupported();
    }

    /**
     * Copies bytes from a Java array into linear memory.
     */
    public static void copyToMemory(@SuppressWarnings("unused") byte[] source, @SuppressWarnings("unused") int sourceOffset, @SuppressWarnings("unused") int address,
                    @SuppressWarnings("unused") int length) {
        throw unsupported();
    }

    /**
     * Copies bytes from linear memory into a Java array.
     */
    public static void copyFromMemory(@SuppressWarnings("unused") int address, @SuppressWarnings("unused") byte[] target, @SuppressWarnings("unused") int targetOffset,
                    @SuppressWarnings("unused") int length) {
        throw unsupported();
    }
}
