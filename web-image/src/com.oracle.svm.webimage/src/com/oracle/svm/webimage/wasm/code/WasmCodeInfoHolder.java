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

package com.oracle.svm.webimage.wasm.code;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.impl.Word;

import com.oracle.svm.shared.BuildPhaseProvider.AfterCompilation;
import com.oracle.svm.guest.staging.log.Log;
import com.oracle.svm.guest.staging.core.heap.UnknownObjectField;
import com.oracle.svm.shared.Uninterruptible;
import com.oracle.svm.shared.util.VMError;

/**
 * Stores {@link WasmCodeInfoQueryResult} for each instruction pointer (IP).
 */
public class WasmCodeInfoHolder {
    /**
     * Maps an IP to its {@link WasmCodeInfoQueryResult}.
     */
    @UnknownObjectField(availability = AfterCompilation.class) private static WasmCodeInfoQueryResult[] codeInfos = null;

    /**
     * Lookup {@link WasmCodeInfoQueryResult} by its IP.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static WasmCodeInfoQueryResult getCodeInfo(CodePointer ip) {
        int maxIP = codeInfos.length;
        long raw = ip.rawValue();

        if (Word.unsigned(raw).aboveOrEqual(maxIP)) {
            throw reportInvalidIp(ip);
        } else {
            return codeInfos[(int) raw];
        }
    }

    @Uninterruptible(reason = "Switch to interruptible code for fatal error reporting.", mayBeInlined = true, calleeMustBe = false)
    private static RuntimeException reportInvalidIp(CodePointer ip) {
        Log.log().hex(ip).newline();
        throw VMError.shouldNotReachHere("Tried to look up out-of-range IP");
    }

    @Platforms(Platform.HOSTED_ONLY.class)
    public static void setCodeInfos(WasmCodeInfoQueryResult[] codeInfos) {
        WasmCodeInfoHolder.codeInfos = codeInfos;
    }

    /*
     * Tables indexed by the method indices in WasmCodeInfoQueryResult#getSourceFrames. They are
     * parallel arrays of shared strings instead of StackTraceElement objects per IP to keep the
     * image heap small.
     */
    @UnknownObjectField(availability = AfterCompilation.class) private static String[] sourceClassNames = new String[0];
    @UnknownObjectField(availability = AfterCompilation.class) private static String[] sourceMethodNames = new String[0];
    @UnknownObjectField(availability = AfterCompilation.class) private static String[] sourceFileNames = new String[0];
    /** Whether the method only exists to create a throwable (and is left out of its stack trace). */
    @UnknownObjectField(availability = AfterCompilation.class) private static boolean[] sourceHidden = new boolean[0];

    /** The declaring classes of the methods, for {@link StackWalker}; an entry may be {@code null}. */
    @UnknownObjectField(availability = AfterCompilation.class) private static Class<?>[] sourceClasses = new Class<?>[0];

    @Platforms(Platform.HOSTED_ONLY.class)
    public static void setSourceMethods(String[] classNames, String[] methodNames, String[] fileNames, boolean[] hidden, Class<?>[] classes) {
        sourceClasses = classes;
        sourceClassNames = classNames;
        sourceMethodNames = methodNames;
        sourceFileNames = fileNames;
        sourceHidden = hidden;
    }

    /** A frame of {@link StackWalker}, decoded from the code information of an IP. */
    private static final class Frame implements StackWalker.StackFrame {
        private final StackTraceElement element;
        private final Class<?> declaringClass;

        Frame(StackTraceElement element, Class<?> declaringClass) {
            this.element = element;
            this.declaringClass = declaringClass;
        }

        @Override
        public String getClassName() {
            return element.getClassName();
        }

        @Override
        public String getMethodName() {
            return element.getMethodName();
        }

        @Override
        public Class<?> getDeclaringClass() {
            if (declaringClass == null) {
                throw new UnsupportedOperationException("The declaring class of " + element + " is not available");
            }
            return declaringClass;
        }

        @Override
        public int getByteCodeIndex() {
            return -1;
        }

        @Override
        public String getFileName() {
            return element.getFileName();
        }

        @Override
        public int getLineNumber() {
            return element.getLineNumber();
        }

        @Override
        public boolean isNativeMethod() {
            return false;
        }

        @Override
        public StackTraceElement toStackTraceElement() {
            return element;
        }

        @Override
        public String toString() {
            return element.toString();
        }
    }

    /**
     * Turns the IPs of a stack walk (innermost frame first) into {@link StackWalker} frames,
     * without the frames of the stack walking machinery itself.
     */
    public static java.util.List<StackWalker.StackFrame> toStackFrames(int[] ips) {
        java.util.ArrayList<StackWalker.StackFrame> result = new java.util.ArrayList<>(ips.length);
        boolean skipping = true;
        for (int ip : ips) {
            if (ip < 0 || ip >= codeInfos.length || codeInfos[ip] == null) {
                continue;
            }
            int[] frames = codeInfos[ip].getSourceFrames();
            for (int i = 0; i + 1 < frames.length; i += 2) {
                int method = frames[i];
                String className = sourceClassNames[method];
                if (skipping && (sourceHidden[method] || className.startsWith("java.lang.StackWalker") || className.contains("Target_java_lang_StackWalker"))) {
                    continue;
                }
                skipping = false;
                result.add(new Frame(new StackTraceElement(className, sourceMethodNames[method], sourceFileNames[method], frames[i + 1]), sourceClasses[method]));
            }
        }
        return result;
    }

    /**
     * Turns the IPs of a stack walk (innermost frame first) into stack trace elements.
     *
     * @param skipThrowableCreation leaves out the leading frames that create the throwable
     *            ({@code fillInStackTrace} and the constructors of throwable classes)
     */
    public static StackTraceElement[] toStackTrace(int[] ips, boolean skipThrowableCreation) {
        java.util.ArrayList<StackTraceElement> elements = new java.util.ArrayList<>(ips.length);
        boolean skipping = skipThrowableCreation;
        for (int ip : ips) {
            if (ip < 0 || ip >= codeInfos.length || codeInfos[ip] == null) {
                continue;
            }
            int[] frames = codeInfos[ip].getSourceFrames();
            for (int i = 0; i + 1 < frames.length; i += 2) {
                int method = frames[i];
                if (skipping && sourceHidden[method]) {
                    continue;
                }
                skipping = false;
                elements.add(new StackTraceElement(sourceClassNames[method], sourceMethodNames[method], sourceFileNames[method], frames[i + 1]));
            }
        }
        return elements.toArray(new StackTraceElement[0]);
    }
}
