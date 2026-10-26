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

import java.util.Set;

/**
 * Converts WIT names (kebab-case) to Java names.
 */
public final class JavaNames {

    private JavaNames() {
    }

    private static final Set<String> KEYWORDS = Set.of("abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue", "default", "do", "double", "else",
                    "enum", "extends", "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
                    "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile", "while",
                    "true", "false", "null", "record", "var", "yield", "sealed", "permits");

    private static String strip(String witName) {
        String n = witName;
        if (n.startsWith("%")) {
            n = n.substring(1);
        }
        return n;
    }

    /** {@code my-name} to {@code myName}. */
    public static String member(String witName) {
        String[] parts = strip(witName).split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(capitalize(parts[i]));
        }
        String result = sb.toString();
        return KEYWORDS.contains(result) ? result + "_" : result;
    }

    /** {@code my-name} to {@code MyName}. */
    public static String type(String witName) {
        String[] parts = strip(witName).split("-");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(capitalize(part));
        }
        return sb.toString();
    }

    /** {@code my-name} to {@code MY_NAME}. */
    public static String constant(String witName) {
        return strip(witName).replace('-', '_').toUpperCase();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
