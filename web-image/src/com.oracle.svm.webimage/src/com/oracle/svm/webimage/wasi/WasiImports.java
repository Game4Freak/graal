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

import com.oracle.svm.webimage.wasm.WasmImportCallDescriptor;

/**
 * Descriptors for the WASI 0.3 imports and Component Model built-ins used by the runtime.
 * <p>
 * Import module and function names follow the Canonical ABI naming scheme understood by
 * {@code wasm-tools component embed} (the "legacy" mangling). Interface functions are imported
 * from a module named after the interface (for example {@code wasi:cli/stdout@0.3.0}), built-ins
 * that belong to a function are prefixed with their kind and the index of the stream or future
 * type in the function signature (for example {@code [stream-new-0]write-via-stream}), and
 * built-ins without a function live in the {@code $root} module.
 * <p>
 * Pointers and handles are passed as {@code int} ({@code i32}), 64-bit integers as {@code long}.
 */
public final class WasiImports {

    private WasiImports() {
    }

    public static final String ROOT = "$root";
    public static final String CLI_ENVIRONMENT = "wasi:cli/environment@0.3.0";
    public static final String CLI_EXIT = "wasi:cli/exit@0.3.0";
    public static final String CLI_STDOUT = "wasi:cli/stdout@0.3.0";
    public static final String CLI_STDERR = "wasi:cli/stderr@0.3.0";
    public static final String CLI_STDIN = "wasi:cli/stdin@0.3.0";
    public static final String CLOCKS_SYSTEM = "wasi:clocks/system-clock@0.3.0";
    public static final String CLOCKS_MONOTONIC = "wasi:clocks/monotonic-clock@0.3.0";
    public static final String RANDOM = "wasi:random/random@0.3.0";

    /** Prefix that selects the asynchronous lowering of a built-in or import. */
    public static final String ASYNC_LOWER = "[async-lower]";

    private static final Class<?>[] NONE = new Class<?>[0];
    private static final Class<?>[] I = new Class<?>[]{int.class};
    private static final Class<?>[] II = new Class<?>[]{int.class, int.class};
    private static final Class<?>[] III = new Class<?>[]{int.class, int.class, int.class};
    private static final Class<?>[] L = new Class<?>[]{long.class};
    private static final Class<?>[] LI = new Class<?>[]{long.class, int.class};

    // wasi:cli/environment

    /** {@code get-arguments: func() -> list<string>}, result written to the pointer argument. */
    public static final WasmImportCallDescriptor GET_ARGUMENTS = new WasmImportCallDescriptor(CLI_ENVIRONMENT, "get-arguments", void.class, I, "wasi:cli/environment.get-arguments");
    /** {@code get-environment: func() -> list<tuple<string, string>>}. */
    public static final WasmImportCallDescriptor GET_ENVIRONMENT = new WasmImportCallDescriptor(CLI_ENVIRONMENT, "get-environment", void.class, I, "wasi:cli/environment.get-environment");
    /** {@code get-initial-cwd: func() -> option<string>}. */
    public static final WasmImportCallDescriptor GET_INITIAL_CWD = new WasmImportCallDescriptor(CLI_ENVIRONMENT, "get-initial-cwd", void.class, I, "wasi:cli/environment.get-initial-cwd");

    // wasi:cli/exit

    /** {@code exit: func(status: result)}. */
    public static final WasmImportCallDescriptor EXIT = new WasmImportCallDescriptor(CLI_EXIT, "exit", void.class, I, "wasi:cli/exit.exit");
    /** {@code exit-with-code: func(status-code: u8)}. */
    public static final WasmImportCallDescriptor EXIT_WITH_CODE = new WasmImportCallDescriptor(CLI_EXIT, "exit-with-code", void.class, I, "wasi:cli/exit.exit-with-code");

    // wasi:clocks

    /** {@code system-clock.now: func() -> instant}, the record is written to the pointer argument. */
    public static final WasmImportCallDescriptor SYSTEM_CLOCK_NOW = new WasmImportCallDescriptor(CLOCKS_SYSTEM, "now", void.class, I, "wasi:clocks/system-clock.now");
    /** {@code monotonic-clock.now: func() -> mark} in nanoseconds. */
    public static final WasmImportCallDescriptor MONOTONIC_CLOCK_NOW = new WasmImportCallDescriptor(CLOCKS_MONOTONIC, "now", long.class, NONE, "wasi:clocks/monotonic-clock.now");
    /** {@code monotonic-clock.wait-for: async func(how-long: duration)}, lowered synchronously (blocks). */
    public static final WasmImportCallDescriptor MONOTONIC_CLOCK_WAIT_FOR = new WasmImportCallDescriptor(CLOCKS_MONOTONIC, "wait-for", void.class, L, "wasi:clocks/monotonic-clock.wait-for");

    // wasi:random

    /** {@code get-random-bytes: func(max-len: u64) -> list<u8>}. */
    public static final WasmImportCallDescriptor GET_RANDOM_BYTES = new WasmImportCallDescriptor(RANDOM, "get-random-bytes", void.class, LI, "wasi:random/random.get-random-bytes");
    /** {@code get-random-u64: func() -> u64}. */
    public static final WasmImportCallDescriptor GET_RANDOM_U64 = new WasmImportCallDescriptor(RANDOM, "get-random-u64", long.class, NONE, "wasi:random/random.get-random-u64");

    // Component Model built-ins in the root module

    /** {@code waitable-set.new: [] -> [i32]}. */
    public static final WasmImportCallDescriptor WAITABLE_SET_NEW = new WasmImportCallDescriptor(ROOT, "[waitable-set-new]", int.class, NONE, "canon waitable-set.new");
    /** {@code waitable-set.wait: [set, ptr] -> [event-code]}, the event is written to ptr. */
    public static final WasmImportCallDescriptor WAITABLE_SET_WAIT = new WasmImportCallDescriptor(ROOT, "[waitable-set-wait]", int.class, II, "canon waitable-set.wait");
    /** {@code waitable-set.drop: [set] -> []}. */
    public static final WasmImportCallDescriptor WAITABLE_SET_DROP = new WasmImportCallDescriptor(ROOT, "[waitable-set-drop]", void.class, I, "canon waitable-set.drop");
    /** {@code waitable.join: [waitable, set] -> []}, a set of 0 removes the waitable from its set. */
    public static final WasmImportCallDescriptor WAITABLE_JOIN = new WasmImportCallDescriptor(ROOT, "[waitable-join]", void.class, II, "canon waitable.join");

    /*
     * Built-ins for the functions of the form {@code write-via-stream: func(data: stream<u8>) ->
     * future<result<_, error-code>>} in {@code wasi:cli/stdout} and {@code wasi:cli/stderr}.
     *
     * Index 0 refers to the {@code stream<u8>} parameter, index 1 to the {@code future} result.
     *
     * Each descriptor is a separate constant because node intrinsics require the descriptor
     * argument to be a compile-time constant at the call site.
     */
    private static final String WRITE_VIA_STREAM = "write-via-stream";

    public static final WasmImportCallDescriptor STDOUT_WRITE_VIA_STREAM = writeViaStream(CLI_STDOUT);
    public static final WasmImportCallDescriptor STDOUT_STREAM_NEW = streamNew(CLI_STDOUT);
    public static final WasmImportCallDescriptor STDOUT_STREAM_WRITE = streamWrite(CLI_STDOUT);
    public static final WasmImportCallDescriptor STDOUT_STREAM_DROP_WRITABLE = streamDropWritable(CLI_STDOUT);
    public static final WasmImportCallDescriptor STDOUT_FUTURE_READ = futureRead(CLI_STDOUT);
    public static final WasmImportCallDescriptor STDOUT_FUTURE_DROP_READABLE = futureDropReadable(CLI_STDOUT);

    public static final WasmImportCallDescriptor STDERR_WRITE_VIA_STREAM = writeViaStream(CLI_STDERR);
    public static final WasmImportCallDescriptor STDERR_STREAM_NEW = streamNew(CLI_STDERR);
    public static final WasmImportCallDescriptor STDERR_STREAM_WRITE = streamWrite(CLI_STDERR);
    public static final WasmImportCallDescriptor STDERR_STREAM_DROP_WRITABLE = streamDropWritable(CLI_STDERR);
    public static final WasmImportCallDescriptor STDERR_FUTURE_READ = futureRead(CLI_STDERR);
    public static final WasmImportCallDescriptor STDERR_FUTURE_DROP_READABLE = futureDropReadable(CLI_STDERR);

    /*
     * Built-ins for {@code wasi:cli/stdin}: {@code read-via-stream: func() -> tuple<stream<u8>,
     * future<result<_, error-code>>>}. Index 0 is the stream, index 1 the future.
     */
    private static final String READ_VIA_STREAM = "read-via-stream";

    public static final WasmImportCallDescriptor STDIN_READ_VIA_STREAM = new WasmImportCallDescriptor(CLI_STDIN, READ_VIA_STREAM, void.class, I, CLI_STDIN + "." + READ_VIA_STREAM);
    public static final WasmImportCallDescriptor STDIN_STREAM_READ = new WasmImportCallDescriptor(CLI_STDIN, ASYNC_LOWER + "[stream-read-0]" + READ_VIA_STREAM, int.class, III,
                    "canon stream.read (async) for " + CLI_STDIN + "." + READ_VIA_STREAM);
    public static final WasmImportCallDescriptor STDIN_STREAM_DROP_READABLE = new WasmImportCallDescriptor(CLI_STDIN, "[stream-drop-readable-0]" + READ_VIA_STREAM, void.class, I,
                    "canon stream.drop-readable for " + CLI_STDIN + "." + READ_VIA_STREAM);
    public static final WasmImportCallDescriptor STDIN_FUTURE_DROP_READABLE = new WasmImportCallDescriptor(CLI_STDIN, "[future-drop-readable-1]" + READ_VIA_STREAM, void.class, I,
                    "canon future.drop-readable for " + CLI_STDIN + "." + READ_VIA_STREAM);

    private static WasmImportCallDescriptor writeViaStream(String module) {
        return new WasmImportCallDescriptor(module, WRITE_VIA_STREAM, int.class, I, module + "." + WRITE_VIA_STREAM);
    }

    private static WasmImportCallDescriptor streamNew(String module) {
        return new WasmImportCallDescriptor(module, "[stream-new-0]" + WRITE_VIA_STREAM, long.class, NONE, "canon stream.new for " + module + "." + WRITE_VIA_STREAM);
    }

    private static WasmImportCallDescriptor streamWrite(String module) {
        return new WasmImportCallDescriptor(module, ASYNC_LOWER + "[stream-write-0]" + WRITE_VIA_STREAM, int.class, III, "canon stream.write (async) for " + module + "." + WRITE_VIA_STREAM);
    }

    private static WasmImportCallDescriptor streamDropWritable(String module) {
        return new WasmImportCallDescriptor(module, "[stream-drop-writable-0]" + WRITE_VIA_STREAM, void.class, I, "canon stream.drop-writable for " + module + "." + WRITE_VIA_STREAM);
    }

    private static WasmImportCallDescriptor futureRead(String module) {
        return new WasmImportCallDescriptor(module, ASYNC_LOWER + "[future-read-1]" + WRITE_VIA_STREAM, int.class, II, "canon future.read (async) for " + module + "." + WRITE_VIA_STREAM);
    }

    private static WasmImportCallDescriptor futureDropReadable(String module) {
        return new WasmImportCallDescriptor(module, "[future-drop-readable-1]" + WRITE_VIA_STREAM, void.class, I, "canon future.drop-readable for " + module + "." + WRITE_VIA_STREAM);
    }
}
