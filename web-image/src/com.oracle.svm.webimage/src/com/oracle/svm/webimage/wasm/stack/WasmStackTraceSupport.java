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

package com.oracle.svm.webimage.wasm.stack;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.webimage.wasm.code.WasmCodeInfoHolder;

import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;

import jdk.graal.compiler.api.replacements.Fold;
import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;

/**
 * Java stack traces without help from the host.
 * <p>
 * The linear-memory Wasm backend keeps a shadow stack in which every frame records the pseudo
 * instruction pointer (IP) of its call site (see {@link WebImageWasmFrameMap}). A stack trace is
 * captured as the array of these IPs, which is cheap, and only turned into
 * {@link StackTraceElement}s when somebody looks at it.
 * <p>
 * The singleton exists in images whose host cannot provide stack traces (WASI).
 */
public abstract class WasmStackTraceSupport {

    @Fold
    public static boolean isAvailable() {
        return ImageSingletons.contains(WasmStackTraceSupport.class);
    }

    public static WasmStackTraceSupport singleton() {
        return ImageSingletons.lookup(WasmStackTraceSupport.class);
    }

    /**
     * The IPs of the frames on the stack, starting with the caller of this method.
     */
    public abstract int[] capture();

    /**
     * Capturing is a foreign call: throwables are also created in snippets, where everything else
     * would be inlined, and the stack walk is not code that can live in a snippet.
     */
    public static final SnippetRuntime.SubstrateForeignCallDescriptor CAPTURE = SnippetRuntime.findForeignCall(WasmStackTraceSupport.class, "captureStub",
                    ForeignCallDescriptor.CallSideEffect.HAS_SIDE_EFFECT);

    /**
     * Result of the last capture. The foreign call has no result: a value-returning foreign call
     * needs a bytecode index for its frame state, which a node intrinsic outside of a snippet
     * does not have. The image is single-threaded, so a field does the job.
     */
    private static int[] captured;

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    private static void captureStub() {
        captured = isAvailable() ? singleton().capture() : null;
    }

    @Node.NodeIntrinsic(ForeignCallNode.class)
    private static native void captureCall(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor);

    /** The backtrace of the caller, to be decoded with {@link #toStackTrace}. */
    public static Object captureBacktrace() {
        captureCall(CAPTURE);
        int[] result = captured;
        captured = null;
        return result;
    }

    /** The frames of the caller for {@link StackWalker}, innermost first. */
    public static java.util.List<StackWalker.StackFrame> captureFrames() {
        return WasmCodeInfoHolder.toStackFrames((int[]) captureBacktrace());
    }

    public static StackTraceElement[] toStackTrace(Object backtrace, boolean skipThrowableCreation) {
        return WasmCodeInfoHolder.toStackTrace((int[]) backtrace, skipThrowableCreation);
    }
}
