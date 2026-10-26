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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.oracle.svm.shared.util.VMError;

/**
 * The WIT packages bundled with the WASI target.
 * <p>
 * The files are vendored from the {@code wasmtime-wasi} crate so that the embedded world matches
 * the runtime used for testing.
 */
public final class WasiWit {

    private WasiWit() {
    }

    /** The world implemented by executable images. */
    public static final String COMMAND_WORLD = "wasi:cli/command";

    /** The WASI version implemented by the runtime. */
    public static final String WASI_VERSION = "0.3.0";

    private static final List<String> FILES = List.of("package.wit", "deps/cli.wit", "deps/clocks.wit", "deps/filesystem.wit", "deps/random.wit", "deps/sockets.wit");

    /**
     * Extracts the bundled WIT package into {@code targetDir/wit} and returns that directory.
     */
    public static Path extract(Path targetDir) {
        Path witDir = targetDir.resolve("wit");
        try {
            for (String file : FILES) {
                Path target = witDir.resolve(file);
                Files.createDirectories(target.getParent());
                try (InputStream stream = WasiWit.class.getResourceAsStream("wit/" + file)) {
                    VMError.guarantee(stream != null, "Couldn't find bundled WIT file %s", file);
                    Files.copy(stream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw VMError.shouldNotReachHere("Failed to extract bundled WIT files", e);
        }
        return witDir;
    }
}
