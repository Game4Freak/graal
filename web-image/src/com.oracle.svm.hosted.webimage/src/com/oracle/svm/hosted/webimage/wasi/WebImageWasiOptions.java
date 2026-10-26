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

import com.oracle.svm.shared.option.HostedOptionKey;

import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionType;

/**
 * Options of the WASI target.
 */
public final class WebImageWasiOptions {

    private WebImageWasiOptions() {
    }

    @Option(help = "Directory (or file) with the WIT package that defines the world implemented by the WASI component. Defaults to the bundled WASI packages.", type = OptionType.User)//
    public static final HostedOptionKey<String> WitDirectory = new HostedOptionKey<>(null);

    @Option(help = "Fully qualified name of the WIT world implemented by the WASI component, for example 'docs:calc/calculator'. Defaults to wasi:cli/command.", type = OptionType.User)//
    public static final HostedOptionKey<String> WitWorld = new HostedOptionKey<>(null);
}
