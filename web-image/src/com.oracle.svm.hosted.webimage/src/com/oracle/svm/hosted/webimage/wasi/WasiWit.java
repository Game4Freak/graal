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

    /** A WIT directory and the world to select in it. */
    public record WitSelection(Path directory, String world) {
    }

    /**
     * Prepares a user-provided WIT tree for {@code wasm-tools component embed}.
     * <p>
     * The Java runtime imports the interfaces of {@code wasi:cli/imports}, so the embedded world
     * must contain them. A world that is given by its qualified name ({@code ns:pkg/world}) does
     * not have to include them itself: the tree is copied, the bundled WASI packages are added if
     * the tree does not provide {@code wasi:cli}, and a synthetic world that includes both the
     * user's world and {@code wasi:cli/imports} is selected instead. Imports that the image does
     * not use are pruned by {@code wasm-tools}.
     * <p>
     * An unqualified world name refers to the root package of the tree and is used as is.
     */
    public static WitSelection prepareUserWit(Path userWit, String userWorld, Path targetDir) {
        if (userWorld.indexOf(':') < 0 || !Files.isDirectory(userWit)) {
            return new WitSelection(userWit, userWorld);
        }
        Path witDir = targetDir.resolve("user-wit");
        try {
            boolean[] hasCli = {false};
            try (java.util.stream.Stream<Path> files = Files.walk(userWit)) {
                for (Path source : (Iterable<Path>) files::iterator) {
                    Path target = witDir.resolve(userWit.relativize(source).toString());
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        if (source.toString().endsWith(".wit") && Files.readString(source).contains("package wasi:cli@")) {
                            hasCli[0] = true;
                        }
                    }
                }
            }
            if (!hasCli[0]) {
                for (String file : FILES) {
                    if (!file.startsWith("deps/")) {
                        continue;
                    }
                    Path target = witDir.resolve("deps").resolve("graalvm-" + file.substring("deps/".length()));
                    Files.createDirectories(target.getParent());
                    try (InputStream stream = WasiWit.class.getResourceAsStream("wit/" + file)) {
                        VMError.guarantee(stream != null, "Couldn't find bundled WIT file %s", file);
                        Files.copy(stream, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Path synthetic = witDir.resolve("deps").resolve("graalvm-webimage-component").resolve("world.wit");
            Files.createDirectories(synthetic.getParent());
            Files.writeString(synthetic, "package graalvm:webimage-component;\n\nworld component {\n    include " + userWorld + ";\n    include wasi:cli/imports@" + WASI_VERSION + ";\n}\n");
        } catch (IOException e) {
            throw VMError.shouldNotReachHere("Failed to prepare the WIT directory " + userWit, e);
        }
        return new WitSelection(witDir, "graalvm:webimage-component/component");
    }
}
