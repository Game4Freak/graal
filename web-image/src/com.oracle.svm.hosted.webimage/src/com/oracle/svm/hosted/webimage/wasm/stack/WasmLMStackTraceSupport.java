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

package com.oracle.svm.hosted.webimage.wasm.stack;

import java.util.Arrays;

import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.Pointer;

import com.oracle.svm.shared.NeverInline;
import com.oracle.svm.guest.staging.core.graal.KnownIntrinsics;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.webimage.wasm.stack.WasmStackTraceSupport;

/**
 * Captures stack traces by walking the shadow stack with {@link WebImageWasmStackWalker}.
 */
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public final class WasmLMStackTraceSupport extends WasmStackTraceSupport {

    /** More frames than this are cut off at the outer end. */
    private static final int MAX_FRAMES = 1024;

    private static final class Collector extends WebImageWasmStackFrameVisitor {
        /** Reused for every walk; the image is single-threaded and the walk does not allocate. */
        final int[] ips = new int[MAX_FRAMES];
        int count;

        @Override
        public boolean visitFrame(Pointer sp, CodePointer ip) {
            if (count == ips.length) {
                return false;
            }
            ips[count++] = (int) ip.rawValue();
            return true;
        }
    }

    private final Collector collector = new Collector();

    @Override
    @NeverInline("Starts a stack walk in the caller frame")
    public int[] capture() {
        collector.count = 0;
        WebImageWasmStackWalker.walkCurrentThread(KnownIntrinsics.readCallerStackPointer(), collector);
        return Arrays.copyOf(collector.ips, collector.count);
    }
}
