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
package com.oracle.svm.hosted.webimage.wasi;

import java.lang.reflect.Method;

import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.JavaMainWrapper;
import com.oracle.svm.hosted.webimage.wasm.WebImageWasmLMJavaMainSupport;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.AllAccess;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonLayeredInstallationKind.ApplicationLayerOnly;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.webimage.platform.WebImageWasiPlatform;
import com.oracle.svm.webimage.wasi.WasiEnvironment;
import com.oracle.svm.webimage.wasi.WasiExit;
import com.oracle.svm.webimage.wasi.WasiStdio;

/**
 * Entry point for the WASI target.
 * <p>
 * {@link #run()} is exported as {@code wasi:cli/run@0.3.0#run} (a synchronous lift of
 * {@code run: async func() -> result}). Arguments are read from {@code wasi:cli/environment}.
 */
@Platforms(WebImageWasiPlatform.class)
@SingletonTraits(access = AllAccess.class, layeredCallbacks = NoLayeredCallbacks.class, layeredInstallationKind = ApplicationLayerOnly.class, other = DisallowLayered.class)
public class WebImageWasiJavaMainSupport extends WebImageWasmLMJavaMainSupport {

    @Platforms(Platform.HOSTED_ONLY.class)
    public WebImageWasiJavaMainSupport(Class<?> javaMainClass, Method javaMainMethod) throws IllegalAccessException {
        super(javaMainClass, javaMainMethod);
    }

    /**
     * Runs the application.
     *
     * @return the discriminant of the {@code result} returned from {@code run}: 0 for ok, 1 for
     *         error.
     */
    public static int run() {
        return finish(doRun(javaArguments(), JavaMainWrapper::invokeMain));
    }

    /**
     * Initializes the runtime without running user code.
     */
    public static int initializeLibrary() {
        return finish(doRun(javaArguments(), Runner.NOP));
    }

    /**
     * The arguments reported by WASI include the program name as the first element, which Java
     * does not pass to the main method.
     */
    private static String[] javaArguments() {
        String[] args = WasiEnvironment.getArguments();
        if (args.length == 0) {
            return args;
        }
        String[] javaArgs = new String[args.length - 1];
        System.arraycopy(args, 1, javaArgs, 0, javaArgs.length);
        return javaArgs;
    }

    private static int finish(int exitCode) {
        WasiStdio.closeAll();
        if (exitCode == 0) {
            return 0;
        }
        if (exitCode != 1) {
            // A plain error result maps to exit code 1, other codes need the exit interface.
            WasiExit.exitWithCode(exitCode);
        }
        return 1;
    }
}
