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
package com.oracle.svm.hosted.webimage.wasi.bindgen;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal writer for generated Java sources with indentation.
 */
public final class JavaWriter {

    private final StringBuilder sb = new StringBuilder();
    private int indent;
    private final List<String> imports = new ArrayList<>();

    public JavaWriter line(String s) {
        if (!s.isEmpty()) {
            sb.append("    ".repeat(indent));
        }
        sb.append(s).append('\n');
        return this;
    }

    public JavaWriter line() {
        return line("");
    }

    public JavaWriter open(String s) {
        line(s + " {");
        indent++;
        return this;
    }

    public JavaWriter close() {
        indent--;
        return line("}");
    }

    public JavaWriter close(String suffix) {
        indent--;
        return line("}" + suffix);
    }

    /** Ends a block without printing the closing brace (the next {@link #open} prints it). */
    public JavaWriter closeNoBrace() {
        indent--;
        return this;
    }

    public void addImport(String name) {
        if (!imports.contains(name)) {
            imports.add(name);
        }
    }

    public String toSource(String javaPackage, String header) {
        StringBuilder out = new StringBuilder();
        out.append(header);
        out.append("package ").append(javaPackage).append(";\n\n");
        if (!imports.isEmpty()) {
            List<String> sorted = new ArrayList<>(imports);
            sorted.sort(null);
            for (String imp : sorted) {
                out.append("import ").append(imp).append(";\n");
            }
            out.append('\n');
        }
        out.append(sb);
        return out.toString();
    }
}
