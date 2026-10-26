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

import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor;
import jdk.graal.compiler.replacements.nodes.BinaryMathIntrinsicNode;
import jdk.graal.compiler.replacements.nodes.UnaryMathIntrinsicNode;

/**
 * Pure Java implementations of arithmetic that Wasm has no instruction for and that the JavaScript
 * host provides on other targets. Transcendental functions delegate to the fdlibm port in the JDK.
 */
public final class WasiMath {

    private WasiMath() {
    }

    public static final SnippetRuntime.SubstrateForeignCallDescriptor DREM = SnippetRuntime.findForeignCall(WasiMath.class, "drem", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor FREM = SnippetRuntime.findForeignCall(WasiMath.class, "frem", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);

    public static final SnippetRuntime.SubstrateForeignCallDescriptor LOG = SnippetRuntime.findForeignCall(WasiMath.class, "log", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor LOG10 = SnippetRuntime.findForeignCall(WasiMath.class, "log10", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor SIN = SnippetRuntime.findForeignCall(WasiMath.class, "sin", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor COS = SnippetRuntime.findForeignCall(WasiMath.class, "cos", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor TAN = SnippetRuntime.findForeignCall(WasiMath.class, "tan", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor TANH = SnippetRuntime.findForeignCall(WasiMath.class, "tanh", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor EXP = SnippetRuntime.findForeignCall(WasiMath.class, "exp", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor CBRT = SnippetRuntime.findForeignCall(WasiMath.class, "cbrt", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);
    public static final SnippetRuntime.SubstrateForeignCallDescriptor POW = SnippetRuntime.findForeignCall(WasiMath.class, "pow", ForeignCallDescriptor.CallSideEffect.NO_SIDE_EFFECT);

    public static final SnippetRuntime.SubstrateForeignCallDescriptor[] FOREIGN_CALLS = {DREM, FREM, LOG, LOG10, SIN, COS, TAN, TANH, EXP, CBRT, POW};

    /**
     * The foreign call implementing the given unary math intrinsic.
     */
    public static SnippetRuntime.SubstrateForeignCallDescriptor forUnary(UnaryMathIntrinsicNode.UnaryOperation operation) {
        return switch (operation) {
            case LOG -> LOG;
            case LOG10 -> LOG10;
            case SIN -> SIN;
            case COS -> COS;
            case TAN -> TAN;
            case TANH -> TANH;
            case EXP -> EXP;
            case CBRT -> CBRT;
        };
    }

    /**
     * The foreign call implementing the given binary math intrinsic.
     */
    public static SnippetRuntime.SubstrateForeignCallDescriptor forBinary(BinaryMathIntrinsicNode.BinaryOperation operation) {
        return switch (operation) {
            case POW -> POW;
        };
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double log(double a) {
        return Target_java_lang_FdLibm_Log_Wasi.compute(a);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double log10(double a) {
        return Target_java_lang_FdLibm_Log10_Wasi.compute(a);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double sin(double a) {
        return Target_java_lang_FdLibm_Sin_Wasi.compute(a);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double cos(double a) {
        return Target_java_lang_FdLibm_Cos_Wasi.compute(a);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double tan(double a) {
        return Target_java_lang_FdLibm_Tan_Wasi.compute(a);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double tanh(double a) {
        return Target_java_lang_FdLibm_Tanh_Wasi.compute(a);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double exp(double a) {
        return Target_java_lang_FdLibm_Exp_Wasi.compute(a);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double cbrt(double a) {
        return Target_java_lang_FdLibm_Cbrt_Wasi.compute(a);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double pow(double a, double b) {
        return Target_java_lang_FdLibm_Pow_Wasi.compute(a, b);
    }

    /**
     * Floating point remainder with the semantics of the JVM {@code drem} instruction (truncated
     * division, result has the sign of the dividend), computed exactly on the bit representation.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static double drem(double x, double y) {
        long ux = Double.doubleToRawLongBits(x);
        long uy = Double.doubleToRawLongBits(y);
        int ex = (int) ((ux >>> 52) & 0x7ff);
        int ey = (int) ((uy >>> 52) & 0x7ff);
        long sign = ux & Long.MIN_VALUE;

        if ((uy << 1) == 0 || Double.isNaN(y) || ex == 0x7ff) {
            return (x * y) / (x * y);
        }
        if (Long.compareUnsigned(ux << 1, uy << 1) <= 0) {
            if ((ux << 1) == (uy << 1)) {
                return 0 * x;
            }
            return x;
        }

        // Normalize x
        long uxi = ux;
        if (ex == 0) {
            for (long i = uxi << 12; i >= 0; i <<= 1) {
                ex--;
            }
            uxi <<= -ex + 1;
        } else {
            uxi &= -1L >>> 12;
            uxi |= 1L << 52;
        }
        // Normalize y
        long uyi = uy;
        if (ey == 0) {
            for (long i = uyi << 12; i >= 0; i <<= 1) {
                ey--;
            }
            uyi <<= -ey + 1;
        } else {
            uyi &= -1L >>> 12;
            uyi |= 1L << 52;
        }

        // x mod y
        for (; ex > ey; ex--) {
            long i = uxi - uyi;
            if (i >= 0) {
                if (i == 0) {
                    return 0 * x;
                }
                uxi = i;
            }
            uxi <<= 1;
        }
        long i = uxi - uyi;
        if (i >= 0) {
            if (i == 0) {
                return 0 * x;
            }
            uxi = i;
        }
        for (; (uxi >>> 52) == 0; uxi <<= 1) {
            ex--;
        }

        // Scale result
        if (ex > 0) {
            uxi -= 1L << 52;
            uxi |= ((long) ex) << 52;
        } else {
            uxi >>>= -ex + 1;
        }
        uxi |= sign;
        return Double.longBitsToDouble(uxi);
    }

    /**
     * Single precision variant of {@link #drem(double, double)}.
     */
    @SubstrateForeignCallTarget(stubCallingConvention = false)
    public static float frem(float x, float y) {
        int ux = Float.floatToRawIntBits(x);
        int uy = Float.floatToRawIntBits(y);
        int ex = (ux >>> 23) & 0xff;
        int ey = (uy >>> 23) & 0xff;
        int sign = ux & Integer.MIN_VALUE;

        if ((uy << 1) == 0 || Float.isNaN(y) || ex == 0xff) {
            return (x * y) / (x * y);
        }
        if (Integer.compareUnsigned(ux << 1, uy << 1) <= 0) {
            if ((ux << 1) == (uy << 1)) {
                return 0 * x;
            }
            return x;
        }

        // Normalize x
        int uxi = ux;
        if (ex == 0) {
            for (int i = uxi << 9; i >= 0; i <<= 1) {
                ex--;
            }
            uxi <<= -ex + 1;
        } else {
            uxi &= -1 >>> 9;
            uxi |= 1 << 23;
        }
        // Normalize y
        int uyi = uy;
        if (ey == 0) {
            for (int i = uyi << 9; i >= 0; i <<= 1) {
                ey--;
            }
            uyi <<= -ey + 1;
        } else {
            uyi &= -1 >>> 9;
            uyi |= 1 << 23;
        }

        // x mod y
        for (; ex > ey; ex--) {
            int i = uxi - uyi;
            if (i >= 0) {
                if (i == 0) {
                    return 0 * x;
                }
                uxi = i;
            }
            uxi <<= 1;
        }
        int i = uxi - uyi;
        if (i >= 0) {
            if (i == 0) {
                return 0 * x;
            }
            uxi = i;
        }
        for (; (uxi >>> 23) == 0; uxi <<= 1) {
            ex--;
        }

        // Scale result
        if (ex > 0) {
            uxi -= 1 << 23;
            uxi |= ex << 23;
        } else {
            uxi >>>= -ex + 1;
        }
        uxi |= sign;
        return Float.intBitsToFloat(uxi);
    }
}
