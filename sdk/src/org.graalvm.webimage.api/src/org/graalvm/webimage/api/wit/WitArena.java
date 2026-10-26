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

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks linear memory allocations made while lowering and lifting Canonical ABI values so that
 * they can be freed together.
 * <p>
 * Generated bindings open a scope with {@link #push()} before a call, allocate parameter buffers
 * with {@link #alloc(int, int)}, register host-allocated buffers with {@link #deferFree(int)}
 * while lifting, and free everything with {@link #pop()}. Scopes nest; the runtime is
 * single-threaded.
 *
 * @since 26.0
 */
public final class WitArena {

    private WitArena() {
    }

    private static final List<List<Integer>> SCOPES = new ArrayList<>();

    /**
     * Opens a new scope.
     */
    public static void push() {
        SCOPES.add(new ArrayList<>());
    }

    /**
     * Frees all allocations registered in the innermost scope and closes it.
     */
    public static void pop() {
        List<Integer> scope = SCOPES.remove(SCOPES.size() - 1);
        for (int i = scope.size() - 1; i >= 0; i--) {
            WitMemory.free(scope.get(i));
        }
    }

    private static List<Integer> current() {
        if (SCOPES.isEmpty()) {
            throw new IllegalStateException("No WitArena scope is open");
        }
        return SCOPES.get(SCOPES.size() - 1);
    }

    /**
     * Allocates memory that is freed when the innermost scope is popped.
     */
    public static int alloc(int size, int align) {
        int address = WitMemory.allocate(size, align);
        current().add(address);
        return address;
    }

    /**
     * Registers memory (typically allocated by the host through {@code cabi_realloc}) to be freed
     * when the innermost scope is popped.
     */
    public static void deferFree(int address) {
        if (address != 0) {
            current().add(address);
        }
    }

    /**
     * Encodes a string as UTF-8 into memory of the innermost scope.
     *
     * @return the address, the length is {@link #utf8Length(String)}
     */
    public static int lowerString(String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int address = alloc(Math.max(bytes.length, 1), 1);
        WitMemory.copyToMemory(bytes, 0, address, bytes.length);
        return address;
    }

    public static int utf8Length(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * Decodes a UTF-8 string from memory and registers the memory to be freed.
     */
    public static String liftString(int address, int length) {
        byte[] bytes = new byte[length];
        WitMemory.copyFromMemory(address, bytes, 0, length);
        deferFree(address);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
