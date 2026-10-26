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

import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.webimage.platform.WebImageWasiPlatform;

/**
 * Aliases for the pure Java ports of fdlibm in the JDK.
 * <p>
 * Native Image substitutes {@link StrictMath} with intrinsic nodes that expect host support. On
 * WASI there is no host support, so the intrinsics are lowered to calls of these implementations
 * (see {@link WasiMath}).
 */
@TargetClass(className = "java.lang.FdLibm", innerClass = "Log")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Log_Wasi {
    @Alias
    static native double compute(double a);
}

@TargetClass(className = "java.lang.FdLibm", innerClass = "Log10")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Log10_Wasi {
    @Alias
    static native double compute(double a);
}

@TargetClass(className = "java.lang.FdLibm", innerClass = "Sin")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Sin_Wasi {
    @Alias
    static native double compute(double a);
}

@TargetClass(className = "java.lang.FdLibm", innerClass = "Cos")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Cos_Wasi {
    @Alias
    static native double compute(double a);
}

@TargetClass(className = "java.lang.FdLibm", innerClass = "Tan")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Tan_Wasi {
    @Alias
    static native double compute(double a);
}

@TargetClass(className = "java.lang.FdLibm", innerClass = "Tanh")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Tanh_Wasi {
    @Alias
    static native double compute(double a);
}

@TargetClass(className = "java.lang.FdLibm", innerClass = "Exp")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Exp_Wasi {
    @Alias
    static native double compute(double a);
}

@TargetClass(className = "java.lang.FdLibm", innerClass = "Cbrt")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Cbrt_Wasi {
    @Alias
    static native double compute(double a);
}

@TargetClass(className = "java.lang.FdLibm", innerClass = "Pow")
@Platforms(WebImageWasiPlatform.class)
final class Target_java_lang_FdLibm_Pow_Wasi {
    @Alias
    static native double compute(double a, double b);
}
