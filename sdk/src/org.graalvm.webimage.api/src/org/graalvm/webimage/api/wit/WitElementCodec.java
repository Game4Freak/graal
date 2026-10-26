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
/**
 * Declares a {@code static native} method as a call to a core WebAssembly function imported by
 * the WASI component.
 * <p>
 * The method parameters and return type must be {@code int}, {@code long}, {@code float},
 * {@code double}, or {@code void} and correspond to the Canonical ABI signature of the imported
 * function. Pointers and resource handles are {@code int}.
 * <p>
 * The import module and name follow the naming scheme used by {@code wasm-tools component embed}:
 * the module is the fully qualified interface name (for example {@code wasi:random/random@0.3.0})
 * or {@code $root} for functions imported directly by the world, and the name is the WIT function
 * name, optionally prefixed with a Canonical ABI built-in such as {@code [stream-new-0]}.
 *
 * <pre>
 * &#64;WitImport(module = "wasi:random/random@0.3.0", name = "get-random-u64")
 * static native long getRandomU64();
 * </pre>
 *
 * This is the low-level interface used by generated bindings. It is only available when
 * compiling for the WASI target.
 *
 * @since 26.0
 */
package org.graalvm.webimage.api.wit;

/**
 * Lowers and lifts single elements of a {@code stream<T>} or the payload of a {@code future<T>}.
 * Implemented by generated bindings.
 *
 * @since 26.0
 */
public interface WitElementCodec<T> {

    int size();

    int align();

    void lower(T value, int address);

    T lift(int address);

    /**
     * Codec for {@code stream} and {@code future} without payload.
     */
    WitElementCodec<Void> UNIT = new WitElementCodec<>() {
        @Override
        public int size() {
            return 0;
        }

        @Override
        public int align() {
            return 1;
        }

        @Override
        public void lower(Void value, int address) {
        }

        @Override
        public Void lift(int address) {
            return null;
        }
    };

    /**
     * Codec for {@code u8} and {@code s8} elements, which lets byte streams use byte arrays.
     */
    WitElementCodec<Byte> BYTE = new WitElementCodec<>() {
        @Override
        public int size() {
            return 1;
        }

        @Override
        public int align() {
            return 1;
        }

        @Override
        public void lower(Byte value, int address) {
            WitMemory.writeByte(address, value);
        }

        @Override
        public Byte lift(int address) {
            return WitMemory.readByte(address);
        }
    };
}
