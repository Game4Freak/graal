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
package com.oracle.svm.webimage;

import java.util.function.BooleanSupplier;

import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.options.Option;

/**
 * Options for {@code java.util.logging} in Web Image.
 */
public final class WebImageLoggingSupport {

    private WebImageLoggingSupport() {
    }

    public static class Options {
        @Option(help = "Make java.util.logging.Logger.getLogger return the global logger. Turn this off for applications that bring their own substitution of that method (for example a different log manager).")//
        public static final HostedOptionKey<Boolean> ReplaceLoggerLookup = new HostedOptionKey<>(true);
    }

    /** Whether {@code Logger.getLogger} is substituted by Web Image. */
    public static final class LoggerLookupReplaced implements BooleanSupplier {
        @Override
        public boolean getAsBoolean() {
            return Options.ReplaceLoggerLookup.getValue();
        }
    }
}
