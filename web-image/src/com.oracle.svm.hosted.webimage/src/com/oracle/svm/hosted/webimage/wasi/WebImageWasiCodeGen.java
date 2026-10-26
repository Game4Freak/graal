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

import static com.oracle.svm.hosted.webimage.metrickeys.ImageBreakdownMetricKeys.ENTIRE_IMAGE_SIZE;
import static com.oracle.svm.hosted.webimage.metrickeys.ImageBreakdownMetricKeys.WASM_IMAGE_SIZE;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.BuildArtifacts;
import com.oracle.svm.core.c.libc.TemporaryBuildDirectoryProvider;
import com.oracle.svm.core.image.ImageHeapLayoutInfo;
import com.oracle.svm.hosted.NativeImageGenerator;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.webimage.WebImageCodeCache;
import com.oracle.svm.hosted.webimage.WebImageHostedConfiguration;
import com.oracle.svm.hosted.webimage.codegen.WebImageProviders;
import com.oracle.svm.hosted.webimage.logging.LoggerContext;
import com.oracle.svm.hosted.webimage.util.metrics.ImageMetricsCollector;
import com.oracle.svm.hosted.webimage.wasm.codegen.WasmTools;
import com.oracle.svm.hosted.webimage.wasm.codegen.WebImageWasmLMCodeGen;

import jdk.graal.compiler.debug.DebugContext;

/**
 * Code generator for the WASI target.
 * <p>
 * Produces a core module with the linear memory backend, then wraps it into a WebAssembly
 * component implementing {@link WasiWit#COMMAND_WORLD} using {@code wasm-tools}. No JavaScript
 * wrapper is emitted.
 */
public class WebImageWasiCodeGen extends WebImageWasmLMCodeGen {

    /** Export name of the main entry point under the Canonical ABI naming scheme. */
    public static final String RUN_EXPORT = "wasi:cli/run@" + WasiWit.WASI_VERSION + "#run";

    private final Path componentFile;
    private final Path embeddedFile;

    public WebImageWasiCodeGen(WebImageCodeCache codeCache, ImageHeapLayoutInfo heapLayout, List<HostedMethod> hostedEntryPoints, HostedMethod mainEntryPoint,
                    WebImageProviders providers, DebugContext debug, WebImageHostedConfiguration config) {
        super(codeCache, heapLayout, hostedEntryPoints, mainEntryPoint, providers, debug, config);
        this.componentFile = NativeImageGenerator.generatedFiles(options).resolve(imageName + ".wasm");
        this.embeddedFile = ImageSingletons.lookup(TemporaryBuildDirectoryProvider.class).getTemporaryBuildDirectory().resolve(imageName + ".embedded.wasm");
    }

    @Override
    protected String getWasmFilename() {
        return imageName + ".core.wasm";
    }

    @Override
    protected String getWatFilename() {
        return imageName + ".wat";
    }

    @Override
    protected String getMainExportName() {
        return RUN_EXPORT;
    }

    @Override
    protected boolean exportJavaThrowableTag() {
        return false;
    }

    @Override
    @SuppressWarnings("try")
    protected void emitJSCode() {
        /*
         * The WASI target has no JavaScript wrapper. The metrics scope is still opened because the
         * image size breakdown expects its counters to exist.
         */
        try (ImageMetricsCollector collector = new ImageMetricsCollector.PreClosure(codeBuffer)) {
            // Nothing to emit
        }
    }

    @Override
    protected void assembleWasmFile(Path watPath, Path wasmPath) {
        super.assembleWasmFile(watPath, wasmPath);
        Path tempDir = ImageSingletons.lookup(TemporaryBuildDirectoryProvider.class).getTemporaryBuildDirectory();
        Path witDir = WasiWit.extract(tempDir);
        WasmTools.singleton().componentize(witDir, WasiWit.COMMAND_WORLD, wasmPath, embeddedFile, componentFile, options, getProviders().stdout()::println);
    }

    @Override
    protected void postProcess() {
        long size = componentFile.toFile().length();
        LoggerContext.counter(WASM_IMAGE_SIZE).add(size);
        LoggerContext.counter(ENTIRE_IMAGE_SIZE).add(size);
    }

    @Override
    protected Collection<Path> writeFiles() {
        List<Path> outFiles = new ArrayList<>();
        outFiles.add(componentFile);
        BuildArtifacts artifacts = BuildArtifacts.singleton();
        artifacts.add(BuildArtifacts.ArtifactType.EXECUTABLE, componentFile);
        artifacts.add(BuildArtifacts.ArtifactType.BUILD_INFO, wasmFile);
        artifacts.add(BuildArtifacts.ArtifactType.BUILD_INFO, watFile);
        return outFiles;
    }
}
