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
package com.oracle.svm.webimage.wasm;

/**
 * Foreign call descriptor for a call to an imported Wasm function.
 * <p>
 * Unlike {@code WasmImportForeignCallDescriptor} in the hosted project, this class can be used from
 * runtime code. The hosted node lowerer declares the corresponding Wasm import on demand and
 * derives the import signature from the Java argument and result types.
 * <p>
 * No two instances must exist that share {@link #module} and {@link #importName} but have a
 * different signature.
 */
public class WasmImportCallDescriptor extends WasmForeignCallDescriptor {

    public final String module;
    public final String importName;
    public final String comment;

    public WasmImportCallDescriptor(String module, String importName, Class<?> resultType, Class<?>[] argumentTypes, String comment) {
        super(module + "." + importName, resultType, argumentTypes);
        this.module = module;
        this.importName = importName;
        this.comment = comment;
    }
}
