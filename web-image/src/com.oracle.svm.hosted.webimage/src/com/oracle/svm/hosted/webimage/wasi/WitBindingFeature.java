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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.graalvm.nativeimage.Platforms;
import org.graalvm.webimage.api.wit.WitExport;
import org.graalvm.webimage.api.wit.WitImport;

import com.oracle.svm.core.ParsingReason;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.webimage.WitImportGuestValue;
import com.oracle.svm.shared.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.util.GuestAnnotationAccess;
import com.oracle.svm.webimage.platform.WebImageWasiPlatform;
import com.oracle.svm.webimage.wasm.WasmImportCallDescriptor;

import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import jdk.graal.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import jdk.graal.compiler.nodes.graphbuilderconf.NodePlugin;
import jdk.graal.compiler.phases.util.Providers;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Signature;

/**
 * Support for the {@link WitImport} and {@link WitExport} annotations.
 * <p>
 * Calls to methods annotated with {@link WitImport} are replaced with calls to the imported Wasm
 * function. Methods annotated with {@link WitExport} are registered as entry points by
 * {@link com.oracle.svm.hosted.webimage.WebImageGenerator} and exported by
 * {@link com.oracle.svm.hosted.webimage.wasm.codegen.WebImageWasmCodeGen}.
 */
@AutomaticallyRegisteredFeature
@Platforms(WebImageWasiPlatform.class)
public class WitBindingFeature implements InternalFeature {

    private final ConcurrentMap<ResolvedJavaMethod, WasmImportCallDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public void registerGraphBuilderPlugins(Providers providers, GraphBuilderConfiguration.Plugins plugins, ParsingReason reason) {
        plugins.appendNodePlugin(new NodePlugin() {
            @Override
            public boolean handleInvoke(GraphBuilderContext b, ResolvedJavaMethod method, ValueNode[] args) {
                if (!GuestAnnotationAccess.isAnnotationPresent(method, WitImport.class)) {
                    return false;
                }
                WasmImportCallDescriptor descriptor = descriptors.computeIfAbsent(method, WitBindingFeature::createDescriptor);
                ForeignCallNode call = new ForeignCallNode(descriptor, args);
                JavaKind returnKind = method.getSignature().getReturnKind();
                if (returnKind == JavaKind.Void) {
                    b.add(call);
                } else {
                    b.addPush(returnKind, call);
                }
                return true;
            }
        });
    }

    private static WasmImportCallDescriptor createDescriptor(ResolvedJavaMethod method) {
        UserError.guarantee(method.isStatic(), "Method %s annotated with @%s must be static", method.format("%H.%n(%p)"), WitImport.class.getSimpleName());
        WitImportGuestValue annotation = WitImportGuestValue.get(method);
        Signature signature = method.getSignature();
        Class<?>[] argumentTypes = new Class<?>[signature.getParameterCount(false)];
        for (int i = 0; i < argumentTypes.length; i++) {
            argumentTypes[i] = coreType(method, signature.getParameterKind(i));
        }
        Class<?> resultType = signature.getReturnKind() == JavaKind.Void ? void.class : coreType(method, signature.getReturnKind());
        return new WasmImportCallDescriptor(annotation.module(), annotation.name(), resultType, argumentTypes, "@WitImport in " + method.format("%H.%n"));
    }

    /**
     * Maps a Java kind to the Java class used for the core Wasm signature.
     */
    public static Class<?> coreType(ResolvedJavaMethod method, JavaKind kind) {
        return switch (kind) {
            case Int -> int.class;
            case Long -> long.class;
            case Float -> float.class;
            case Double -> double.class;
            default -> throw UserError.abort("Method %s uses the type %s which is not allowed in a Canonical ABI signature. Only int, long, float, and double are allowed.", method.format("%H.%n(%p)"),
                            kind);
        };
    }

    /**
     * Checks that an exported method only uses core Wasm types.
     */
    public static void checkExportSignature(ResolvedJavaMethod method) {
        UserError.guarantee(method.isStatic(), "Method %s annotated with @%s must be static", method.format("%H.%n(%p)"), WitExport.class.getSimpleName());
        Signature signature = method.getSignature();
        for (int i = 0; i < signature.getParameterCount(false); i++) {
            coreType(method, signature.getParameterKind(i));
        }
        if (signature.getReturnKind() != JavaKind.Void) {
            coreType(method, signature.getReturnKind());
        }
    }
}
