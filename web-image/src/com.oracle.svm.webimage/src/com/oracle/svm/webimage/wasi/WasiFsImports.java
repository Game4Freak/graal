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
 * Descriptors for the imports of {@code wasi:filesystem@0.3.0}.
 * <p>
 * All functions of the {@code descriptor} resource are declared {@code async} in WIT; they are
 * lowered synchronously here, which blocks until the host completes them. Results that do not fit
 * into a single core value are written to a return pointer passed as the last argument.
 */
public final class WasiFsImports {

    private WasiFsImports() {
    }

    public static final String TYPES = "wasi:filesystem/types@0.3.0";
    public static final String PREOPENS = "wasi:filesystem/preopens@0.3.0";

    private static final Class<?>[] I = new Class<?>[]{int.class};
    private static final Class<?>[] II = new Class<?>[]{int.class, int.class};
    private static final Class<?>[] III = new Class<?>[]{int.class, int.class, int.class};
    private static final Class<?>[] IIII = new Class<?>[]{int.class, int.class, int.class, int.class};
    private static final Class<?>[] IIIII = new Class<?>[]{int.class, int.class, int.class, int.class, int.class};
    private static final Class<?>[] IIIIIII = new Class<?>[]{int.class, int.class, int.class, int.class, int.class, int.class, int.class};
    private static final Class<?>[] ILI = new Class<?>[]{int.class, long.class, int.class};
    private static final Class<?>[] IIL = new Class<?>[]{int.class, int.class, long.class};

    private static WasmImportCallDescriptor method(String name, Class<?> result, Class<?>[] args) {
        return new WasmImportCallDescriptor(TYPES, "[method]descriptor." + name, result, args, TYPES + ".descriptor." + name);
    }

    private static WasmImportCallDescriptor builtin(String prefix, String name, Class<?> result, Class<?>[] args) {
        return new WasmImportCallDescriptor(TYPES, prefix + "[method]descriptor." + name, result, args, "canon " + prefix + " for descriptor." + name);
    }

    /** {@code get-directories: func() -> list<tuple<descriptor, string>>}. */
    public static final WasmImportCallDescriptor GET_DIRECTORIES = new WasmImportCallDescriptor(PREOPENS, "get-directories", void.class, I, PREOPENS + ".get-directories");

    /** {@code [resource-drop]descriptor}. */
    public static final WasmImportCallDescriptor DESCRIPTOR_DROP = new WasmImportCallDescriptor(TYPES, "[resource-drop]descriptor", void.class, I, "canon resource.drop for descriptor");

    /** {@code open-at(path-flags, path, open-flags, flags) -> result<descriptor, error-code>}. */
    public static final WasmImportCallDescriptor OPEN_AT = method("open-at", void.class, IIIIIII);
    /** {@code stat() -> result<descriptor-stat, error-code>}. */
    public static final WasmImportCallDescriptor STAT = method("stat", void.class, II);
    /** {@code stat-at(path-flags, path) -> result<descriptor-stat, error-code>}. */
    public static final WasmImportCallDescriptor STAT_AT = method("stat-at", void.class, IIIII);
    /** {@code get-type() -> result<descriptor-type, error-code>}. */
    public static final WasmImportCallDescriptor GET_TYPE = method("get-type", void.class, II);
    /** {@code create-directory-at(path) -> result<_, error-code>}. */
    public static final WasmImportCallDescriptor CREATE_DIRECTORY_AT = method("create-directory-at", void.class, IIII);
    /** {@code unlink-file-at(path) -> result<_, error-code>}. */
    public static final WasmImportCallDescriptor UNLINK_FILE_AT = method("unlink-file-at", void.class, IIII);
    /** {@code remove-directory-at(path) -> result<_, error-code>}. */
    public static final WasmImportCallDescriptor REMOVE_DIRECTORY_AT = method("remove-directory-at", void.class, IIII);
    /** {@code readlink-at(path) -> result<string, error-code>}. */
    public static final WasmImportCallDescriptor READLINK_AT = method("readlink-at", void.class, IIII);
    /** {@code rename-at(old-path, new-descriptor, new-path) -> result<_, error-code>}. */
    public static final WasmImportCallDescriptor RENAME_AT = method("rename-at", void.class, IIIIIII);
    /** {@code set-size(size) -> result<_, error-code>}. */
    public static final WasmImportCallDescriptor SET_SIZE = method("set-size", void.class, ILI);
    /** {@code sync() -> result<_, error-code>}. */
    public static final WasmImportCallDescriptor SYNC = method("sync", void.class, II);
    /** {@code is-same-object(other) -> bool}. */
    public static final WasmImportCallDescriptor IS_SAME_OBJECT = method("is-same-object", int.class, II);

    /** {@code read-via-stream(offset) -> tuple<stream<u8>, future<result<_, error-code>>>}. */
    public static final WasmImportCallDescriptor READ_VIA_STREAM = method("read-via-stream", void.class, ILI);
    public static final WasmImportCallDescriptor READ_STREAM_READ = builtin("[async-lower][stream-read-0]", "read-via-stream", int.class, III);
    public static final WasmImportCallDescriptor READ_STREAM_DROP_READABLE = builtin("[stream-drop-readable-0]", "read-via-stream", void.class, I);
    public static final WasmImportCallDescriptor READ_FUTURE_READ = builtin("[async-lower][future-read-1]", "read-via-stream", int.class, II);
    public static final WasmImportCallDescriptor READ_FUTURE_DROP_READABLE = builtin("[future-drop-readable-1]", "read-via-stream", void.class, I);

    /** {@code write-via-stream(data, offset) -> future<result<_, error-code>>}. */
    public static final WasmImportCallDescriptor WRITE_VIA_STREAM = method("write-via-stream", int.class, IIL);
    public static final WasmImportCallDescriptor WRITE_STREAM_NEW = builtin("[stream-new-0]", "write-via-stream", long.class, new Class<?>[0]);
    public static final WasmImportCallDescriptor WRITE_STREAM_WRITE = builtin("[async-lower][stream-write-0]", "write-via-stream", int.class, III);
    public static final WasmImportCallDescriptor WRITE_STREAM_DROP_WRITABLE = builtin("[stream-drop-writable-0]", "write-via-stream", void.class, I);
    public static final WasmImportCallDescriptor WRITE_FUTURE_READ = builtin("[async-lower][future-read-1]", "write-via-stream", int.class, II);
    public static final WasmImportCallDescriptor WRITE_FUTURE_DROP_READABLE = builtin("[future-drop-readable-1]", "write-via-stream", void.class, I);

    /** {@code append-via-stream(data) -> future<result<_, error-code>>}. */
    public static final WasmImportCallDescriptor APPEND_VIA_STREAM = method("append-via-stream", int.class, II);
    public static final WasmImportCallDescriptor APPEND_STREAM_NEW = builtin("[stream-new-0]", "append-via-stream", long.class, new Class<?>[0]);
    public static final WasmImportCallDescriptor APPEND_STREAM_WRITE = builtin("[async-lower][stream-write-0]", "append-via-stream", int.class, III);
    public static final WasmImportCallDescriptor APPEND_STREAM_DROP_WRITABLE = builtin("[stream-drop-writable-0]", "append-via-stream", void.class, I);
    public static final WasmImportCallDescriptor APPEND_FUTURE_READ = builtin("[async-lower][future-read-1]", "append-via-stream", int.class, II);
    public static final WasmImportCallDescriptor APPEND_FUTURE_DROP_READABLE = builtin("[future-drop-readable-1]", "append-via-stream", void.class, I);

    /** {@code read-directory() -> tuple<stream<directory-entry>, future<result<_, error-code>>>}. */
    public static final WasmImportCallDescriptor READ_DIRECTORY = method("read-directory", void.class, II);
    public static final WasmImportCallDescriptor DIR_STREAM_READ = builtin("[async-lower][stream-read-0]", "read-directory", int.class, III);
    public static final WasmImportCallDescriptor DIR_STREAM_DROP_READABLE = builtin("[stream-drop-readable-0]", "read-directory", void.class, I);
    public static final WasmImportCallDescriptor DIR_FUTURE_READ = builtin("[async-lower][future-read-1]", "read-directory", int.class, II);
    public static final WasmImportCallDescriptor DIR_FUTURE_DROP_READABLE = builtin("[future-drop-readable-1]", "read-directory", void.class, I);

    // Flags

    public static final int DESCRIPTOR_FLAG_READ = 1;
    public static final int DESCRIPTOR_FLAG_WRITE = 2;
    public static final int DESCRIPTOR_FLAG_MUTATE_DIRECTORY = 1 << 5;

    public static final int PATH_FLAG_SYMLINK_FOLLOW = 1;

    public static final int OPEN_FLAG_CREATE = 1;
    public static final int OPEN_FLAG_DIRECTORY = 2;
    public static final int OPEN_FLAG_EXCLUSIVE = 4;
    public static final int OPEN_FLAG_TRUNCATE = 8;

    // descriptor-type cases

    public static final int TYPE_BLOCK_DEVICE = 0;
    public static final int TYPE_CHARACTER_DEVICE = 1;
    public static final int TYPE_DIRECTORY = 2;
    public static final int TYPE_FIFO = 3;
    public static final int TYPE_SYMBOLIC_LINK = 4;
    public static final int TYPE_REGULAR_FILE = 5;
    public static final int TYPE_SOCKET = 6;
    public static final int TYPE_OTHER = 7;

    // error-code cases (see filesystem.wit)

    public static final int ERROR_ACCESS = 0;
    public static final int ERROR_ALREADY = 1;
    public static final int ERROR_BAD_DESCRIPTOR = 2;
    public static final int ERROR_EXIST = 6;
    public static final int ERROR_INVALID = 11;
    public static final int ERROR_IO = 12;
    public static final int ERROR_IS_DIRECTORY = 13;
    public static final int ERROR_NO_ENTRY = 19;
    public static final int ERROR_NOT_DIRECTORY = 23;
    public static final int ERROR_NOT_EMPTY = 24;
    public static final int ERROR_UNSUPPORTED = 26;
    public static final int ERROR_NOT_PERMITTED = 30;
    public static final int ERROR_READ_ONLY = 32;
    public static final int ERROR_CROSS_DEVICE = 35;
    public static final int ERROR_OTHER = 36;

    /**
     * Names of the error-code cases, indexed by discriminant.
     */
    public static final String[] ERROR_NAMES = {"access", "already", "bad-descriptor", "busy", "deadlock", "quota", "exist", "file-too-large", "illegal-byte-sequence", "in-progress", "interrupted",
                    "invalid", "io", "is-directory", "loop", "too-many-links", "message-size", "name-too-long", "no-device", "no-entry", "no-lock", "insufficient-memory",
                    "insufficient-space", "not-directory", "not-empty", "not-recoverable", "unsupported", "no-tty", "no-such-device", "overflow", "not-permitted", "pipe", "read-only",
                    "invalid-seek", "text-file-busy", "cross-device", "other"};

    // Memory layouts (32-bit Canonical ABI)

    /** Size of {@code descriptor-type} (variant with an {@code option<string>} payload). */
    public static final int DESCRIPTOR_TYPE_SIZE = 16;
    /** Size of {@code error-code} (variant with an {@code option<string>} payload). */
    public static final int ERROR_CODE_SIZE = 16;
    /** Size of {@code result<_, error-code>}: discriminant, then the error code at offset 4. */
    public static final int RESULT_UNIT_SIZE = 4 + ERROR_CODE_SIZE;
    /** Size of {@code result<descriptor, error-code>}: discriminant, then handle or error code at offset 4. */
    public static final int RESULT_HANDLE_SIZE = 4 + ERROR_CODE_SIZE;
    /** Size of {@code result<string, error-code>}: discriminant, then (pointer, length) or error code at offset 4. */
    public static final int RESULT_STRING_SIZE = 4 + ERROR_CODE_SIZE;
    /**
     * Layout of {@code descriptor-stat}: type (16), link-count (u64 at 16), size (u64 at 24), and
     * three {@code option<instant>} (24 bytes each, 8-byte aligned) at 32, 56, and 80.
     */
    public static final int STAT_SIZE = 104;
    public static final int STAT_OFFSET_LINK_COUNT = 16;
    public static final int STAT_OFFSET_SIZE = 24;
    public static final int STAT_OFFSET_ACCESS_TIME = 32;
    public static final int STAT_OFFSET_MODIFICATION_TIME = 56;
    public static final int STAT_OFFSET_CHANGE_TIME = 80;
    /** Size of {@code result<descriptor-stat, error-code>}: 8-byte aligned payload at offset 8. */
    public static final int RESULT_STAT_SIZE = 8 + STAT_SIZE;
    public static final int RESULT_STAT_PAYLOAD_OFFSET = 8;
    /** Size of {@code directory-entry}: type (16) and name (pointer, length) at 16. */
    public static final int DIRECTORY_ENTRY_SIZE = 24;
    /** Size of {@code tuple<descriptor, string>} in the preopen list. */
    public static final int PREOPEN_ENTRY_SIZE = 12;
}
