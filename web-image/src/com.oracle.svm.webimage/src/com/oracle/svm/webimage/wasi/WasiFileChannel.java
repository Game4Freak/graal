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
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;

import org.graalvm.nativeimage.StackValue;
import org.graalvm.word.Pointer;

/**
 * A {@link SeekableByteChannel} over a WASI file descriptor.
 * <p>
 * Reads use {@code read-via-stream} starting at the current position; the stream is kept open
 * while reads are sequential and recreated after a seek. Writes use {@code write-via-stream} at
 * the current position (or {@code append-via-stream} in append mode) in the same way. Closing the
 * channel drops the streams, awaits their completion futures (which reports write errors), and
 * drops the descriptor.
 */
public final class WasiFileChannel implements SeekableByteChannel {

    private final int descriptor;
    private final String displayPath;
    private final boolean readable;
    private final boolean writable;
    private final boolean append;

    private long position;
    private boolean open = true;

    /** Readable stream end for reads, or -1. */
    private int readStream = -1;
    private int readFuture = -1;
    private boolean readEof;

    /** Writable stream end for writes, or -1. */
    private int writeStream = -1;
    private int writeFuture = -1;

    public WasiFileChannel(int descriptor, String displayPath, boolean readable, boolean writable, boolean append) {
        this.descriptor = descriptor;
        this.displayPath = displayPath;
        this.readable = readable;
        this.writable = writable;
        this.append = append;
    }

    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    // Reading

    private void openReadStream() {
        Pointer handles = StackValue.get(2 * Integer.BYTES);
        WasiCalls.callVILI(WasiFsImports.READ_VIA_STREAM, descriptor, position, WasiMemory.address(handles));
        readStream = handles.readInt(0);
        readFuture = handles.readInt(Integer.BYTES);
        readEof = false;
    }

    private void closeReadStream() throws IOException {
        if (readStream >= 0) {
            WasiCalls.callVI(WasiFsImports.READ_STREAM_DROP_READABLE, readStream);
            readStream = -1;
            WasiFileSystem.awaitUnitFuture(WasiFsImports.READ_FUTURE_READ, WasiFsImports.READ_FUTURE_DROP_READABLE, readFuture, displayPath);
            readFuture = -1;
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        ensureOpen();
        if (!readable) {
            throw new NonReadableChannelException();
        }
        int remaining = dst.remaining();
        if (remaining == 0) {
            return 0;
        }
        if (readStream < 0) {
            openReadStream();
        }
        if (readEof) {
            return -1;
        }

        byte[] array;
        int offset;
        if (dst.hasArray()) {
            array = dst.array();
            offset = dst.arrayOffset() + dst.position();
        } else {
            array = new byte[remaining];
            offset = 0;
        }
        int payload = WasiFileSystem.readStream(readStream, array, offset, remaining);
        int count = WasiStreams.copyResultCount(payload);
        if (dst.hasArray()) {
            dst.position(dst.position() + count);
        } else {
            dst.put(array, 0, count);
        }
        position += count;
        if (WasiStreams.copyResultCode(payload) == WasiStreams.DROPPED) {
            readEof = true;
            closeReadStream();
            if (count == 0) {
                return -1;
            }
        }
        return count;
    }

    // Writing

    private void openWriteStream() {
        long pair;
        if (append) {
            pair = WasiCalls.callL(WasiFsImports.APPEND_STREAM_NEW);
        } else {
            pair = WasiCalls.callL(WasiFsImports.WRITE_STREAM_NEW);
        }
        int readableEnd = (int) pair;
        writeStream = (int) (pair >>> 32);
        if (append) {
            writeFuture = WasiCalls.callIII(WasiFsImports.APPEND_VIA_STREAM, descriptor, readableEnd);
        } else {
            writeFuture = WasiCalls.callIIIL(WasiFsImports.WRITE_VIA_STREAM, descriptor, readableEnd, position);
        }
    }

    private void closeWriteStream() throws IOException {
        if (writeStream >= 0) {
            if (append) {
                WasiCalls.callVI(WasiFsImports.APPEND_STREAM_DROP_WRITABLE, writeStream);
            } else {
                WasiCalls.callVI(WasiFsImports.WRITE_STREAM_DROP_WRITABLE, writeStream);
            }
            writeStream = -1;
            if (append) {
                WasiFileSystem.awaitUnitFuture(WasiFsImports.APPEND_FUTURE_READ, WasiFsImports.APPEND_FUTURE_DROP_READABLE, writeFuture, displayPath);
            } else {
                WasiFileSystem.awaitUnitFuture(WasiFsImports.WRITE_FUTURE_READ, WasiFsImports.WRITE_FUTURE_DROP_READABLE, writeFuture, displayPath);
            }
            writeFuture = -1;
        }
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        ensureOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
        int remaining = src.remaining();
        if (remaining == 0) {
            return 0;
        }
        // Reads and writes at the same time would race on the position, use one stream at a time
        closeReadStream();
        if (writeStream < 0) {
            openWriteStream();
        }

        byte[] array;
        int offset;
        if (src.hasArray()) {
            array = src.array();
            offset = src.arrayOffset() + src.position();
        } else {
            array = new byte[remaining];
            offset = 0;
            src.get(src.position(), array);
        }
        int written = WasiFileSystem.writeStream(append, writeStream, array, offset, remaining);
        src.position(src.position() + written);
        position += written;
        if (written < remaining) {
            // The host dropped the stream, the future holds the error
            closeWriteStream();
            throw new IOException("Write to " + displayPath + " was interrupted by the host");
        }
        return written;
    }

    // Positioning

    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        if (newPosition < 0) {
            throw new IllegalArgumentException();
        }
        if (newPosition != position) {
            closeReadStream();
            closeWriteStream();
            position = newPosition;
        }
        return this;
    }

    @Override
    public long size() throws IOException {
        ensureOpen();
        // Pending writes must be visible
        closeWriteStream();
        return WasiFileSystem.stat(descriptor, displayPath).size();
    }

    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
        closeReadStream();
        closeWriteStream();
        if (size < size()) {
            WasiFileSystem.setSize(descriptor, size, displayPath);
        }
        if (position > size) {
            position = size;
        }
        return this;
    }

    public void force() throws IOException {
        ensureOpen();
        closeWriteStream();
        WasiFileSystem.sync(descriptor, displayPath);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() throws IOException {
        if (open) {
            open = false;
            try {
                closeReadStream();
                closeWriteStream();
            } finally {
                WasiFileSystem.drop(descriptor);
            }
        }
    }
}
