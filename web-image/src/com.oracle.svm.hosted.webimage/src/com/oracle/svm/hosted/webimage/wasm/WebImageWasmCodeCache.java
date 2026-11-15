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

package com.oracle.svm.hosted.webimage.wasm;

import java.util.HashMap;
import java.util.Map;

import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Pair;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.word.UnsignedWord;

import com.oracle.graal.pointsto.BigBang;
import com.oracle.svm.core.code.CodeInfo;
import com.oracle.svm.core.code.CodeInfoEncoder;
import com.oracle.svm.core.code.FrameInfoDecoder.ConstantAccess;
import com.oracle.svm.shared.util.VMError;
import com.oracle.svm.hosted.image.NativeImageHeap;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedUniverse;
import com.oracle.svm.hosted.webimage.WebImageCodeCache;
import com.oracle.svm.hosted.webimage.wasm.codegen.WebImageWasmCompilationResult;
import com.oracle.svm.webimage.wasm.code.FrameData;
import com.oracle.svm.webimage.wasm.code.WasmCodeInfoHolder;
import com.oracle.svm.webimage.wasm.code.WasmCodeInfoQueryResult;

import jdk.graal.compiler.api.replacements.SnippetReflectionProvider;
import jdk.graal.compiler.code.CompilationResult;
import jdk.graal.compiler.debug.DebugContext;
import jdk.graal.compiler.debug.Indent;
import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.Infopoint;

public class WebImageWasmCodeCache extends WebImageCodeCache {

    private final HashMap<Integer, WasmCodeInfoQueryResult> codeInfoMap = new HashMap<>();

    public WebImageWasmCodeCache(Map<HostedMethod, CompilationResult> compilationResultMap, NativeImageHeap imageHeap) {
        super(compilationResultMap, imageHeap);
    }

    @Override
    public int codeSizeFor(HostedMethod method) {
        int methodStart = method.getCodeAddressOffset();
        int methodEnd = computeNextMethodStart(methodStart, compilationResultFor(method).getTargetCodeSize());
        return methodEnd - methodStart;
    }

    @Override
    @SuppressWarnings("try")
    public void layoutMethods(DebugContext debug, BigBang bb) {
        try (Indent indent = debug.logAndIndent("layout methods")) {
            /*
             * Assigns the pseudo-instruction pointers (IP) to methods. A method's
             * 'codeAddressOffset' is its starting pseudo-IP and its codesize is the number of IPs
             * the method requires.
             */
            int curPos = 0;
            for (Pair<HostedMethod, CompilationResult> entry : getOrderedCompilations()) {
                HostedMethod method = entry.getLeft();
                CompilationResult compilation = entry.getRight();

                method.setCodeAddressOffset(curPos);
                curPos = computeNextMethodStart(curPos, compilation.getTargetCodeSize());
            }

            // the total code size is the hypothetical start of the next method
            setCodeAreaSize(curPos);
        }
    }

    @Override
    protected void buildRuntimeMetadata(DebugContext debug, SnippetReflectionProvider snippetReflection, CFunctionPointer firstMethod, UnsignedWord codeSize) {
        super.buildRuntimeMetadata(debug, snippetReflection, firstMethod, codeSize);

        int maxIP = codeInfoMap.keySet().stream().max(Integer::compareTo).orElse(-1);
        WasmCodeInfoQueryResult[] codeInfos = new WasmCodeInfoQueryResult[maxIP + 1];
        codeInfoMap.forEach((ip, info) -> codeInfos[ip] = info);
        // Inject the code infos into the image
        WasmCodeInfoHolder.setCodeInfos(codeInfos);
        int methods = sourceMethodIds.size();
        boolean[] hidden = new boolean[methods];
        for (int i = 0; i < methods; i++) {
            hidden[i] = sourceHidden.get(i);
        }
        WasmCodeInfoHolder.setSourceMethods(sourceClassNames.toArray(new String[0]), sourceMethodNames.toArray(new String[0]), sourceFileNames.toArray(new String[0]), hidden,
                        sourceClasses.toArray(new Class<?>[0]));
    }

    @Override
    protected boolean verifyMethods(DebugContext debug, HostedUniverse hUniverse, CodeInfoEncoder codeInfoEncoder, CodeInfo codeInfo, ConstantAccess constantAccess) {
        // TODO GR-43486 enable this if we ever use CodeInfoEncoder
        return true;
    }

    @Override
    protected void encodeMethod(CodeInfoEncoder codeInfoEncoder, Pair<HostedMethod, CompilationResult> pair) {
        final HostedMethod method = pair.getLeft();
        final WebImageWasmCompilationResult compilation = (WebImageWasmCompilationResult) pair.getRight();
        final int compilationOffset = method.getCodeAddressOffset();

        EconomicSet<Integer> infopointOffsets = EconomicSet.create();

        FrameData data = new FrameData(compilation.getTotalFrameSize(), method.format("%H.%n(%P)%R"));

        int[] referenceOffsets = compilation.getLiveSlots().stream().mapToInt(s -> s.getOffset(compilation.getTotalFrameSize())).toArray();
        WasmCodeInfoQueryResult queryResult = new WasmCodeInfoQueryResult(data, referenceOffsets, sourceFrames(method, null));

        // Add code info for start of method
        codeInfoMap.put(compilationOffset, queryResult);
        // Call sites of the method with the same source frames share one result object
        java.util.IdentityHashMap<int[], WasmCodeInfoQueryResult> perCallSite = new java.util.IdentityHashMap<>();

        for (Infopoint infopoint : compilation.getInfopoints()) {
            if (infopoint instanceof Call call) {
                final int offset = call.pcOffset + call.size;
                boolean added = infopointOffsets.add(offset);
                if (!added) {
                    throw VMError.shouldNotReachHere("Encoding two infopoints at same offset. Conflicting infopoint: " + infopoint);
                }

                int ip = compilationOffset + offset;

                // The frame layout is the method's; the source frames are those of the call site
                int[] frames = sourceFrames(method, compilation.getCallPosition(offset));
                WasmCodeInfoQueryResult callResult = frames == queryResult.getSourceFrames() ? queryResult : perCallSite.computeIfAbsent(frames, f -> new WasmCodeInfoQueryResult(data, referenceOffsets, f));
                codeInfoMap.put(ip, callResult);
            }
        }
    }

    /*
     * Tables for stack traces: every method that appears in a source frame gets an index into
     * parallel lists of its class, method and file name.
     */
    private final Map<jdk.vm.ci.meta.ResolvedJavaMethod, Integer> sourceMethodIds = new java.util.HashMap<>();
    private final java.util.List<String> sourceClassNames = new java.util.ArrayList<>();
    private final java.util.List<String> sourceMethodNames = new java.util.ArrayList<>();
    private final java.util.List<String> sourceFileNames = new java.util.ArrayList<>();
    private final java.util.List<Boolean> sourceHidden = new java.util.ArrayList<>();
    private final java.util.List<Class<?>> sourceClasses = new java.util.ArrayList<>();
    private static final int[] NO_SOURCE_FRAMES = new int[0];
    private final Map<java.util.List<Integer>, int[]> canonicalSourceFrames = new java.util.HashMap<>();

    /**
     * Encodes the Java frames of an IP as pairs of method index and line number, innermost first.
     * Without a bytecode position, the only frame is the compiled method with an unknown line.
     */
    private int[] sourceFrames(HostedMethod method, jdk.vm.ci.code.BytecodePosition position) {
        if (!com.oracle.svm.webimage.wasm.stack.WasmStackTraceSupport.isAvailable()) {
            // Nobody decodes stack traces in this image
            return NO_SOURCE_FRAMES;
        }
        java.util.List<Integer> frames = new java.util.ArrayList<>();
        if (position == null) {
            frames.add(sourceMethodId(method));
            frames.add(-1);
        }
        for (jdk.vm.ci.code.BytecodePosition p = position; p != null; p = p.getCaller()) {
            frames.add(sourceMethodId(p.getMethod()));
            frames.add(p.getMethod().asStackTraceElement(p.getBCI()).getLineNumber());
        }
        // Many call sites have the same frames (same line, same inlining): one array for all of them
        return canonicalSourceFrames.computeIfAbsent(frames, f -> f.stream().mapToInt(Integer::intValue).toArray());
    }

    private int sourceMethodId(jdk.vm.ci.meta.ResolvedJavaMethod method) {
        Integer id = sourceMethodIds.get(method);
        if (id == null) {
            id = sourceMethodIds.size();
            sourceMethodIds.put(method, id);
            StackTraceElement element = method.asStackTraceElement(0);
            sourceClassNames.add(element.getClassName().intern());
            sourceMethodNames.add(element.getMethodName().intern());
            sourceFileNames.add(element.getFileName() == null ? null : element.getFileName().intern());
            sourceHidden.add(createsThrowable(method));
            // Only classes the analysis has seen have a class object in the image
            sourceClasses.add(method.getDeclaringClass() instanceof com.oracle.svm.hosted.meta.HostedType type && type.getWrapped().isReachable() ? type.getJavaClass() : null);
        }
        return id;
    }

    /** Frames of {@code fillInStackTrace} and of throwable constructors are not part of a stack trace. */
    private static boolean createsThrowable(jdk.vm.ci.meta.ResolvedJavaMethod method) {
        boolean throwable = false;
        for (jdk.vm.ci.meta.ResolvedJavaType type = method.getDeclaringClass(); type != null; type = type.getSuperclass()) {
            if (type.toJavaName().equals("java.lang.Throwable")) {
                throwable = true;
                break;
            }
        }
        if (method.getDeclaringClass().toJavaName().equals(com.oracle.svm.webimage.wasm.stack.WasmStackTraceSupport.class.getName())) {
            // The capturing code itself
            return true;
        }
        return throwable && (method.getName().equals("<init>") || method.getName().startsWith("fillInStackTrace"));
    }

    private static int computeNextMethodStart(int current, int addend) {
        try {
            return Math.addExact(current, addend);
        } catch (ArithmeticException e) {
            throw VMError.shouldNotReachHere("Code size is larger than 2GB");
        }
    }
}
