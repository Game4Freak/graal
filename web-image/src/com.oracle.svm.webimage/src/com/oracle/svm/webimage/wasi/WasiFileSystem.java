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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.word.Pointer;

import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.webimage.wasm.WasmImportCallDescriptor;

/**
 * File system access through {@code wasi:filesystem@0.3.0}.
 * <p>
 * The host grants access to directories ("preopens"), each mapped to a guest path. Absolute guest
 * paths are resolved to the preopen with the longest matching prefix and a path relative to it.
 * Paths outside of every preopen are not handled here (the caller falls back to the in-memory
 * file system).
 */
public final class WasiFileSystem {

    private WasiFileSystem() {
    }

    /**
     * A preopened directory.
     */
    public record Preopen(int descriptor, String guestPath) {
    }

    /**
     * A path resolved to a preopen descriptor and a path relative to it ({@code "."} for the
     * preopen itself).
     */
    public record Resolved(Preopen preopen, String relativePath) {
        public boolean isRoot() {
            return relativePath.equals(".");
        }
    }

    private static Preopen[] preopens;

    /**
     * The directories granted by the host, sorted so that longer guest paths come first.
     */
    public static Preopen[] getPreopens() {
        if (preopens == null) {
            Pointer result = StackValue.get(2 * Integer.BYTES);
            WasiCalls.callVI(WasiFsImports.GET_DIRECTORIES, WasiMemory.address(result));
            int base = result.readInt(0);
            int count = result.readInt(Integer.BYTES);
            List<Preopen> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int entry = base + i * WasiFsImports.PREOPEN_ENTRY_SIZE;
                int descriptor = WasiMemory.readInt(entry, 0);
                int data = WasiMemory.readInt(entry, 4);
                String path = normalize(WasiMemory.readString(data, WasiMemory.readInt(entry, 8)));
                WasiAllocator.freeAddress(data);
                list.add(new Preopen(descriptor, path));
            }
            WasiAllocator.freeAddress(base);
            list.sort((a, b) -> Integer.compare(b.guestPath().length(), a.guestPath().length()));
            preopens = list.toArray(new Preopen[0]);
        }
        return preopens;
    }

    private static String normalize(String path) {
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /**
     * Resolves an absolute, normalized guest path.
     *
     * @return the resolved path or {@code null} if no preopen covers the path
     */
    public static Resolved resolve(String absolutePath) {
        for (Preopen preopen : getPreopens()) {
            String prefix = preopen.guestPath();
            if (prefix.equals("/")) {
                if (absolutePath.startsWith("/")) {
                    return new Resolved(preopen, absolutePath.length() == 1 ? "." : absolutePath.substring(1));
                }
            } else if (absolutePath.equals(prefix)) {
                return new Resolved(preopen, ".");
            } else if (absolutePath.startsWith(prefix + "/")) {
                return new Resolved(preopen, absolutePath.substring(prefix.length() + 1));
            }
        }
        return null;
    }

    public static boolean isWasiPath(String absolutePath) {
        return resolve(absolutePath) != null;
    }

    // Errors

    /**
     * Maps a {@code result<_, E>} discriminant and error code at the given address to an
     * exception. The error code variant starts at {@code address + errorOffset}.
     */
    static void checkResult(Pointer result, int errorOffset, String path) throws IOException {
        if (result.readInt(0) != 0) {
            throw toException(result.readByte(errorOffset) & 0xFF, path);
        }
    }

    static IOException toException(int errorCode, String path) {
        return switch (errorCode) {
            case WasiFsImports.ERROR_NO_ENTRY -> new NoSuchFileException(path);
            case WasiFsImports.ERROR_EXIST, WasiFsImports.ERROR_ALREADY -> new FileAlreadyExistsException(path);
            case WasiFsImports.ERROR_NOT_EMPTY -> new DirectoryNotEmptyException(path);
            case WasiFsImports.ERROR_NOT_DIRECTORY -> new NotDirectoryException(path);
            case WasiFsImports.ERROR_ACCESS, WasiFsImports.ERROR_NOT_PERMITTED, WasiFsImports.ERROR_READ_ONLY -> new AccessDeniedException(path);
            default -> new FileSystemException(path, null, "WASI error: " + (errorCode < WasiFsImports.ERROR_NAMES.length ? WasiFsImports.ERROR_NAMES[errorCode] : String.valueOf(errorCode)));
        };
    }

    // Path strings

    /**
     * A path encoded as UTF-8 in unmanaged memory, for passing to imports.
     */
    static final class PathBytes implements AutoCloseable {
        final Pointer pointer;
        final int length;

        PathBytes(String path) {
            byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
            this.length = bytes.length;
            this.pointer = WasiAllocator.singleton().allocate(Math.max(length, 1));
            for (int i = 0; i < length; i++) {
                pointer.writeByte(i, bytes[i]);
            }
        }

        int address() {
            return WasiMemory.address(pointer);
        }

        @Override
        public void close() {
            WasiAllocator.singleton().free(pointer);
        }
    }

    // Descriptor operations

    public static void drop(int descriptor) {
        WasiCalls.callVI(WasiFsImports.DESCRIPTOR_DROP, descriptor);
    }

    /**
     * Opens a path relative to the given directory descriptor.
     *
     * @return the new descriptor
     */
    public static int openAt(int directory, String relativePath, int openFlags, int descriptorFlags, boolean followSymlinks, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_HANDLE_SIZE);
        try (PathBytes path = new PathBytes(relativePath)) {
            WasiCalls.callVIIIIIII(WasiFsImports.OPEN_AT, directory, followSymlinks ? WasiFsImports.PATH_FLAG_SYMLINK_FOLLOW : 0, path.address(), path.length, openFlags, descriptorFlags,
                            WasiMemory.address(result));
        }
        checkResult(result, 4, displayPath);
        return result.readInt(4);
    }

    public static int open(Resolved resolved, int openFlags, int descriptorFlags, boolean followSymlinks, String displayPath) throws IOException {
        return openAt(resolved.preopen().descriptor(), resolved.relativePath(), openFlags, descriptorFlags, followSymlinks, displayPath);
    }

    /**
     * File attributes as returned by {@code stat}.
     */
    public record Stat(int type, long linkCount, long size, FileTime accessTime, FileTime modificationTime, FileTime changeTime) {
        public boolean isDirectory() {
            return type == WasiFsImports.TYPE_DIRECTORY;
        }

        public boolean isRegularFile() {
            return type == WasiFsImports.TYPE_REGULAR_FILE;
        }

        public boolean isSymbolicLink() {
            return type == WasiFsImports.TYPE_SYMBOLIC_LINK;
        }
    }

    private static FileTime readOptionalInstant(Pointer p, int offset) {
        if (p.readByte(offset) == 0) {
            return FileTime.fromMillis(0);
        }
        long seconds = p.readLong(offset + 8);
        int nanos = p.readInt(offset + 16);
        return FileTime.fromMillis(seconds * 1_000L + (nanos & 0xFFFF_FFFFL) / 1_000_000L);
    }

    private static Stat readStat(Pointer result, String displayPath) throws IOException {
        checkResult(result, WasiFsImports.RESULT_STAT_PAYLOAD_OFFSET, displayPath);
        int base = WasiFsImports.RESULT_STAT_PAYLOAD_OFFSET;
        int type = result.readByte(base) & 0xFF;
        if (type == WasiFsImports.TYPE_OTHER && result.readByte(base + 4) != 0) {
            // other(option<string>) carries a host-allocated string
            WasiAllocator.freeAddress(result.readInt(base + 8));
        }
        return new Stat(type, result.readLong(base + WasiFsImports.STAT_OFFSET_LINK_COUNT), result.readLong(base + WasiFsImports.STAT_OFFSET_SIZE),
                        readOptionalInstant(result, base + WasiFsImports.STAT_OFFSET_ACCESS_TIME),
                        readOptionalInstant(result, base + WasiFsImports.STAT_OFFSET_MODIFICATION_TIME),
                        readOptionalInstant(result, base + WasiFsImports.STAT_OFFSET_CHANGE_TIME));
    }

    public static Stat stat(int descriptor, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_STAT_SIZE);
        WasiCalls.callVII(WasiFsImports.STAT, descriptor, WasiMemory.address(result));
        return readStat(result, displayPath);
    }

    public static Stat statAt(int directory, String relativePath, boolean followSymlinks, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_STAT_SIZE);
        try (PathBytes path = new PathBytes(relativePath)) {
            WasiCalls.callVIIIII(WasiFsImports.STAT_AT, directory, followSymlinks ? WasiFsImports.PATH_FLAG_SYMLINK_FOLLOW : 0, path.address(), path.length, WasiMemory.address(result));
        }
        return readStat(result, displayPath);
    }

    public static Stat stat(Resolved resolved, boolean followSymlinks, String displayPath) throws IOException {
        if (resolved.isRoot()) {
            return stat(resolved.preopen().descriptor(), displayPath);
        }
        return statAt(resolved.preopen().descriptor(), resolved.relativePath(), followSymlinks, displayPath);
    }

    /**
     * Runs an operation of the form {@code f(path) -> result<_, error-code>}.
     */
    private static final int OP_CREATE_DIRECTORY = 0;
    private static final int OP_UNLINK_FILE = 1;
    private static final int OP_REMOVE_DIRECTORY = 2;

    private static void pathOperation(int kind, int directory, String relativePath, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_UNIT_SIZE);
        try (PathBytes path = new PathBytes(relativePath)) {
            switch (kind) {
                case OP_CREATE_DIRECTORY -> WasiCalls.callVIIII(WasiFsImports.CREATE_DIRECTORY_AT, directory, path.address(), path.length, WasiMemory.address(result));
                case OP_UNLINK_FILE -> WasiCalls.callVIIII(WasiFsImports.UNLINK_FILE_AT, directory, path.address(), path.length, WasiMemory.address(result));
                default -> WasiCalls.callVIIII(WasiFsImports.REMOVE_DIRECTORY_AT, directory, path.address(), path.length, WasiMemory.address(result));
            }
        }
        checkResult(result, 4, displayPath);
    }

    public static void createDirectory(Resolved resolved, String displayPath) throws IOException {
        pathOperation(OP_CREATE_DIRECTORY, resolved.preopen().descriptor(), resolved.relativePath(), displayPath);
    }

    public static void unlinkFile(Resolved resolved, String displayPath) throws IOException {
        pathOperation(OP_UNLINK_FILE, resolved.preopen().descriptor(), resolved.relativePath(), displayPath);
    }

    public static void removeDirectory(Resolved resolved, String displayPath) throws IOException {
        pathOperation(OP_REMOVE_DIRECTORY, resolved.preopen().descriptor(), resolved.relativePath(), displayPath);
    }

    public static void rename(Resolved source, Resolved target, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_UNIT_SIZE);
        try (PathBytes oldPath = new PathBytes(source.relativePath()); PathBytes newPath = new PathBytes(target.relativePath())) {
            WasiCalls.callVIIIIIII(WasiFsImports.RENAME_AT, source.preopen().descriptor(), oldPath.address(), oldPath.length, target.preopen().descriptor(), newPath.address(), newPath.length,
                            WasiMemory.address(result));
        }
        checkResult(result, 4, displayPath);
    }

    public static String readLink(Resolved resolved, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_STRING_SIZE);
        try (PathBytes path = new PathBytes(resolved.relativePath())) {
            WasiCalls.callVIIII(WasiFsImports.READLINK_AT, resolved.preopen().descriptor(), path.address(), path.length, WasiMemory.address(result));
        }
        checkResult(result, 4, displayPath);
        int data = result.readInt(4);
        String target = WasiMemory.readString(data, result.readInt(8));
        WasiAllocator.freeAddress(data);
        return target;
    }

    public static void setSize(int descriptor, long size, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_UNIT_SIZE);
        WasiCalls.callVILI(WasiFsImports.SET_SIZE, descriptor, size, WasiMemory.address(result));
        checkResult(result, 4, displayPath);
    }

    public static void sync(int descriptor, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_UNIT_SIZE);
        WasiCalls.callVII(WasiFsImports.SYNC, descriptor, WasiMemory.address(result));
        checkResult(result, 4, displayPath);
    }

    /**
     * Lists the names of the entries of the directory at the given path.
     */
    public static List<String> readDirectory(Resolved resolved, String displayPath) throws IOException {
        int directory;
        boolean ownsDescriptor;
        if (resolved.isRoot()) {
            directory = resolved.preopen().descriptor();
            ownsDescriptor = false;
        } else {
            directory = open(resolved, WasiFsImports.OPEN_FLAG_DIRECTORY, WasiFsImports.DESCRIPTOR_FLAG_READ, true, displayPath);
            ownsDescriptor = true;
        }
        try {
            return readDirectoryEntries(directory, displayPath);
        } finally {
            if (ownsDescriptor) {
                drop(directory);
            }
        }
    }

    private static final int DIRECTORY_CHUNK = 16;

    private static List<String> readDirectoryEntries(int directory, String displayPath) throws IOException {
        Pointer handles = StackValue.get(2 * Integer.BYTES);
        WasiCalls.callVII(WasiFsImports.READ_DIRECTORY, directory, WasiMemory.address(handles));
        int stream = handles.readInt(0);
        int future = handles.readInt(Integer.BYTES);

        List<String> names = new ArrayList<>();
        Pointer buffer = WasiAllocator.singleton().allocate(DIRECTORY_CHUNK * WasiFsImports.DIRECTORY_ENTRY_SIZE);
        try {
            while (true) {
                int payload = WasiCalls.callIIII(WasiFsImports.DIR_STREAM_READ, stream, WasiMemory.address(buffer), DIRECTORY_CHUNK);
                if (payload == WasiStreams.BLOCKED) {
                    payload = WasiStreams.blockOn(stream);
                }
                int count = WasiStreams.copyResultCount(payload);
                for (int i = 0; i < count; i++) {
                    int entry = i * WasiFsImports.DIRECTORY_ENTRY_SIZE;
                    int type = buffer.readByte(entry) & 0xFF;
                    if (type == WasiFsImports.TYPE_OTHER && buffer.readByte(entry + 4) != 0) {
                        WasiAllocator.freeAddress(buffer.readInt(entry + 8));
                    }
                    int data = buffer.readInt(entry + 16);
                    names.add(WasiMemory.readString(data, buffer.readInt(entry + 20)));
                    WasiAllocator.freeAddress(data);
                }
                if (WasiStreams.copyResultCode(payload) == WasiStreams.DROPPED) {
                    break;
                }
            }
        } finally {
            WasiAllocator.singleton().free(buffer);
            WasiCalls.callVI(WasiFsImports.DIR_STREAM_DROP_READABLE, stream);
        }
        awaitUnitFuture(WasiFsImports.DIR_FUTURE_READ, WasiFsImports.DIR_FUTURE_DROP_READABLE, future, displayPath);
        return names;
    }

    /**
     * Waits for a {@code future<result<_, error-code>>}, drops it, and throws if it holds an error.
     * The future functions are selected by kind because node intrinsics need constant descriptors.
     */
    static void awaitUnitFuture(WasmImportCallDescriptor read, WasmImportCallDescriptor drop, int future, String displayPath) throws IOException {
        Pointer result = StackValue.get(WasiFsImports.RESULT_UNIT_SIZE);
        int address = WasiMemory.address(result);
        int payload;
        if (read == WasiFsImports.DIR_FUTURE_READ) {
            payload = WasiCalls.callIII(WasiFsImports.DIR_FUTURE_READ, future, address);
        } else if (read == WasiFsImports.READ_FUTURE_READ) {
            payload = WasiCalls.callIII(WasiFsImports.READ_FUTURE_READ, future, address);
        } else if (read == WasiFsImports.WRITE_FUTURE_READ) {
            payload = WasiCalls.callIII(WasiFsImports.WRITE_FUTURE_READ, future, address);
        } else {
            payload = WasiCalls.callIII(WasiFsImports.APPEND_FUTURE_READ, future, address);
        }
        if (payload == WasiStreams.BLOCKED) {
            payload = WasiStreams.blockOn(future);
        }
        if (drop == WasiFsImports.DIR_FUTURE_DROP_READABLE) {
            WasiCalls.callVI(WasiFsImports.DIR_FUTURE_DROP_READABLE, future);
        } else if (drop == WasiFsImports.READ_FUTURE_DROP_READABLE) {
            WasiCalls.callVI(WasiFsImports.READ_FUTURE_DROP_READABLE, future);
        } else if (drop == WasiFsImports.WRITE_FUTURE_DROP_READABLE) {
            WasiCalls.callVI(WasiFsImports.WRITE_FUTURE_DROP_READABLE, future);
        } else {
            WasiCalls.callVI(WasiFsImports.APPEND_FUTURE_DROP_READABLE, future);
        }
        if (WasiStreams.copyResultCode(payload) == WasiStreams.COMPLETED) {
            checkResult(result, 4, displayPath);
        }
    }

    /**
     * Reads from a file stream into a byte array.
     *
     * @return the copy event payload
     */
    @Uninterruptible(reason = "Handles untracked pointers.", calleeMustBe = false)
    static int readStream(int stream, byte[] bytes, int offset, int length) {
        int payload = WasiCalls.callIIII(WasiFsImports.READ_STREAM_READ, stream, WasiMemory.arrayDataAddress(bytes) + offset, length);
        if (payload == WasiStreams.BLOCKED) {
            payload = WasiStreams.blockOn(stream);
        }
        return payload;
    }

    /**
     * Writes a byte array to a file stream (write-via-stream or append-via-stream).
     *
     * @return the number of bytes written
     */
    @Uninterruptible(reason = "Handles untracked pointers.", calleeMustBe = false)
    static int writeStream(boolean append, int stream, byte[] bytes, int offset, int length) {
        int address = WasiMemory.arrayDataAddress(bytes) + offset;
        int written = 0;
        while (written < length) {
            int payload;
            if (append) {
                payload = WasiCalls.callIIII(WasiFsImports.APPEND_STREAM_WRITE, stream, address + written, length - written);
            } else {
                payload = WasiCalls.callIIII(WasiFsImports.WRITE_STREAM_WRITE, stream, address + written, length - written);
            }
            if (payload == WasiStreams.BLOCKED) {
                payload = WasiStreams.blockOn(stream);
            }
            written += WasiStreams.copyResultCount(payload);
            if (WasiStreams.copyResultCode(payload) == WasiStreams.DROPPED) {
                break;
            }
        }
        return written;
    }
}
