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

import java.nio.charset.StandardCharsets;

import org.graalvm.nativeimage.LogHandler;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.singletons.AutomaticallyRegisteredImageSingleton;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.webimage.platform.WebImageWasiPlatform;
import com.oracle.svm.webimage.print.WebImagePrintingProvider;

/**
 * Standard output and error streams on WASI 0.3.
 * <p>
 * On first use, a {@code stream<u8>} is created and its readable end is handed to the host through
 * {@code write-via-stream}. All output is then written to the writable end. On shutdown, the
 * writable end is dropped and the future returned by {@code write-via-stream} is awaited, which
 * guarantees that the host has consumed all output before the program exits.
 */
public final class WasiStdio {

    private WasiStdio() {
    }

    public static final class Output {
        /** Selects the standard error stream (true) or the standard output stream (false). */
        private final boolean stderr;
        /** Writable stream end, or -1 if not yet opened. */
        private int writable = -1;
        /** Readable end of the future returned by {@code write-via-stream}. */
        private int future = -1;
        private boolean closed = false;

        Output(boolean stderr) {
            this.stderr = stderr;
        }

        private void ensureOpen() {
            if (writable < 0 && !closed) {
                long pair = stderr ? WasiCalls.callL(WasiImports.STDERR_STREAM_NEW) : WasiCalls.callL(WasiImports.STDOUT_STREAM_NEW);
                int readable = (int) pair;
                writable = (int) (pair >>> 32);
                future = stderr ? WasiCalls.callII(WasiImports.STDERR_WRITE_VIA_STREAM, readable) : WasiCalls.callII(WasiImports.STDOUT_WRITE_VIA_STREAM, readable);
            }
        }

        public void write(byte[] bytes, int offset, int length) {
            ensureOpen();
            if (closed) {
                return;
            }
            writeArray(bytes, offset, length);
        }

        @Uninterruptible(reason = "Handles untracked pointers.")
        private void writeArray(byte[] bytes, int offset, int length) {
            WasiStreams.writeAll(stderr, writable, WasiMemory.arrayDataAddress(bytes) + offset, length);
        }

        /**
         * Writes raw bytes from unmanaged memory.
         */
        @Uninterruptible(reason = "Caller passes untracked pointers.", calleeMustBe = false)
        public void writeRaw(CCharPointer bytes, int length) {
            ensureOpen();
            if (closed) {
                return;
            }
            WasiStreams.writeAll(stderr, writable, WasiMemory.address(bytes), length);
        }

        /**
         * Drops the stream and waits until the host has consumed all output.
         */
        public void close() {
            if (writable >= 0 && !closed) {
                closed = true;
                if (stderr) {
                    WasiCalls.callVI(WasiImports.STDERR_STREAM_DROP_WRITABLE, writable);
                } else {
                    WasiCalls.callVI(WasiImports.STDOUT_STREAM_DROP_WRITABLE, writable);
                }
                awaitFuture();
            }
            closed = true;
        }

        @Uninterruptible(reason = "Handles stack memory.")
        private void awaitFuture() {
            // result<_, error-code>: discriminant followed by the error code
            Pointer result = StackValue.get(2 * Integer.BYTES);
            WasiStreams.awaitAndDropFuture(stderr, future, WasiMemory.address(result));
        }
    }

    public static final Output OUT = new Output(false);
    public static final Output ERR = new Output(true);

    /**
     * Standard input: a readable {@code stream<u8>} obtained from {@code read-via-stream}.
     */
    public static final class Input {
        /** Readable stream end, or -1 if not yet opened. */
        private int readable = -1;
        private int future = -1;
        private boolean eof = false;

        private void ensureOpen() {
            if (readable < 0 && !eof) {
                // tuple<stream<u8>, future<...>>: two handles
                Pointer result = StackValue.get(2 * Integer.BYTES);
                WasiCalls.callVI(WasiImports.STDIN_READ_VIA_STREAM, WasiMemory.address(result));
                readable = result.readInt(0);
                future = result.readInt(Integer.BYTES);
            }
        }

        /**
         * Reads up to {@code length} bytes, blocking until at least one byte is available.
         *
         * @return the number of bytes read or -1 at end of input
         */
        public int read(byte[] bytes, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            ensureOpen();
            if (eof) {
                return -1;
            }
            int payload = readArray(bytes, offset, length);
            int count = WasiStreams.copyResultCount(payload);
            if (WasiStreams.copyResultCode(payload) == WasiStreams.DROPPED) {
                close();
                if (count == 0) {
                    return -1;
                }
            }
            return count;
        }

        @Uninterruptible(reason = "Handles untracked pointers.")
        private int readArray(byte[] bytes, int offset, int length) {
            int payload = WasiCalls.callIIII(WasiImports.STDIN_STREAM_READ, readable, WasiMemory.arrayDataAddress(bytes) + offset, length);
            if (payload == WasiStreams.BLOCKED) {
                payload = WasiStreams.blockOn(readable);
            }
            return payload;
        }

        public void close() {
            if (readable >= 0 && !eof) {
                WasiCalls.callVI(WasiImports.STDIN_STREAM_DROP_READABLE, readable);
                WasiCalls.callVI(WasiImports.STDIN_FUTURE_DROP_READABLE, future);
            }
            eof = true;
        }
    }

    public static final Input IN = new Input();

    public static Output get(WebImagePrintingProvider.Descriptor fd) {
        return switch (fd) {
            case OUT -> OUT;
            case ERR -> ERR;
        };
    }

    /**
     * Closes both streams. Must be called before the program exits so that output is flushed.
     */
    public static void closeAll() {
        OUT.close();
        ERR.close();
    }

    /**
     * Printing provider that writes to the WASI standard streams.
     */
    @AutomaticallyRegisteredImageSingleton(value = WebImagePrintingProvider.class, onlyWith = IsWasiHost.class)
    @Platforms(WebImageWasiPlatform.class)
    @SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
    public static final class PrintingProvider extends WebImagePrintingProvider {
        @Override
        public void print(Descriptor fd, char[] chars) {
            byte[] bytes = new String(chars).getBytes(StandardCharsets.UTF_8);
            get(fd).write(bytes, 0, bytes.length);
        }

        @Override
        public void flush(Descriptor fd) {
            // Writes are synchronous, nothing is buffered on the guest side.
        }

        @Override
        public void close(Descriptor fd) {
            get(fd).close();
        }
    }

    /**
     * Log handler for VM-internal logging (for example fatal error messages).
     */
    public static final class WasiLogHandler implements LogHandler {
        @Override
        @Uninterruptible(reason = "Caller passes untracked pointers.", calleeMustBe = false)
        public void log(CCharPointer bytes, UnsignedWord length) {
            ERR.writeRaw(bytes, (int) length.rawValue());
        }

        @Override
        public void flush() {
            // Nothing is buffered.
        }

        @Override
        public void fatalError() {
            ERR.close();
            WasiExit.exitWithCode(255);
            throw VMError.shouldNotReachHereAtRuntime();
        }
    }
}
