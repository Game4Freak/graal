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

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;

/**
 * Node intrinsics that call an imported Wasm function described by a
 * {@link com.oracle.svm.webimage.wasm.WasmImportCallDescriptor}.
 * <p>
 * One intrinsic exists per core Wasm signature. The method names encode the signature: {@code call}
 * followed by the result type ({@code V} for void, {@code I} for {@code i32}, {@code L} for
 * {@code i64}) and the argument types.
 */
public final class WasiCalls {

    private WasiCalls() {
    }

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callV(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVI(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVII(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, int b);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVL(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, long a);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVLI(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, long a, int b);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native int callI(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native int callII(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native int callIII(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, int b);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native int callIIII(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, int b, int c);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVIII(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, int b, int c);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVIIII(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, int b, int c, int d);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVIIIII(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, int b, int c, int d, int e);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVIIIIIII(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, int b, int c, int d, int e, int f, int g);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native void callVILI(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, long b, int c);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native int callIIIL(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor, int a, int b, long c);

    @Node.NodeIntrinsic(ForeignCallNode.class)
    public static native long callL(@Node.ConstantNodeParameter ForeignCallDescriptor descriptor);
}
