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
package com.oracle.svm.hosted.webimage.wasm.codegen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.OS;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.util.InterruptImageBuilding;
import com.oracle.svm.core.util.UserError;
import com.oracle.svm.hosted.c.codegen.CCompilerInvoker;
import com.oracle.svm.hosted.c.util.FileUtils;
import com.oracle.svm.shared.option.HostedOptionKey;
import com.oracle.svm.shared.option.SubstrateOptionsParser;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.BuildtimeAccessOnly;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.DisallowLayered;
import com.oracle.svm.shared.singletons.traits.BuiltinTraits.NoLayeredCallbacks;
import com.oracle.svm.shared.singletons.traits.SingletonTraits;
import com.oracle.svm.shared.util.SubstrateUtil;

import jdk.graal.compiler.debug.DebugOptions;
import jdk.graal.compiler.options.Option;
import jdk.graal.compiler.options.OptionType;
import jdk.graal.compiler.options.OptionValues;

/**
 * Wrapper around the {@code wasm-tools} executable from the Bytecode Alliance.
 * <p>
 * It is used to turn a core Wasm module into a WebAssembly component: {@code component embed}
 * records the WIT world the module implements in a custom section and {@code component new} wraps
 * the module into a component.
 */
@SingletonTraits(access = BuildtimeAccessOnly.class, layeredCallbacks = NoLayeredCallbacks.class, other = DisallowLayered.class)
public class WasmTools {

    public static class Options {
        @Option(help = "Provide custom path to the wasm-tools executable used to create WebAssembly components", type = OptionType.User)//
        public static final HostedOptionKey<String> WasmToolsPath = new HostedOptionKey<>(null);
    }

    public static final String PROJECT_NAME = "wasm-tools";
    public static final String URL = "https://github.com/bytecodealliance/wasm-tools";
    public static final String EXECUTABLE = "wasm-tools";
    /** First version that supports WASI 0.3 (async streams and futures) in {@code component embed}. */
    public static final int MINIMUM_MAJOR = 1;
    public static final int MINIMUM_MINOR = 240;

    public final Path executable;
    public final String versionString;

    protected WasmTools(Path executable, String versionString) {
        this.executable = executable;
        this.versionString = versionString;
    }

    public static WasmTools singleton() {
        return ImageSingletons.lookup(WasmTools.class);
    }

    public static void install() {
        assert !ImageSingletons.contains(WasmTools.class);
        try {
            ImageSingletons.add(WasmTools.class, create());
        } catch (UserError.UserException err) {
            throw rethrowWithInfo(err);
        }
    }

    private static WasmTools create() {
        Path executable = findExecutable().toAbsolutePath();
        if (!SubstrateOptions.CheckToolchain.getValue()) {
            return new WasmTools(executable, null);
        }

        RunResult result;
        try {
            result = run(executable, List.of("--version"));
        } catch (InterruptedException e) {
            throw new InterruptImageBuilding("Interrupted while checking " + executable);
        } catch (IOException e) {
            throw UserError.abort(e, "Collecting wasm-tools info failed");
        }
        if (result.exitCode() != 0) {
            throw UserError.abort("Collecting wasm-tools info failed.%n%s", formatCommandInfo(result));
        }
        String version = String.join(System.lineSeparator(), result.outputLines()).trim();
        checkVersion(version);
        return new WasmTools(executable, version);
    }

    /**
     * Checks that the output of {@code wasm-tools --version} (for example {@code wasm-tools 1.262.0})
     * satisfies the minimum version.
     */
    static void checkVersion(String version) {
        String[] parts = version.trim().split("\\s+");
        String number = parts[parts.length - 1];
        String[] components = number.split("\\.");
        try {
            int major = Integer.parseInt(components[0]);
            int minor = components.length > 1 ? Integer.parseInt(components[1]) : 0;
            if (major > MINIMUM_MAJOR || (major == MINIMUM_MAJOR && minor >= MINIMUM_MINOR)) {
                return;
            }
        } catch (NumberFormatException e) {
            throw UserError.abort("Could not parse wasm-tools version from '%s'", version);
        }
        throw UserError.abort("Version %s of wasm-tools is too old. At least version %d.%d.0 is required.", number, MINIMUM_MAJOR, MINIMUM_MINOR);
    }

    private static Path findExecutable() {
        String userDefinedPath = Options.WasmToolsPath.getValue();
        Path path;
        if (userDefinedPath != null) {
            path = Paths.get(userDefinedPath);
        } else {
            String name = OS.WINDOWS.isCurrent() ? EXECUTABLE + ".exe" : EXECUTABLE;
            Optional<Path> optPath = CCompilerInvoker.lookupSearchPath(name);
            if (optPath.isEmpty()) {
                throw UserError.abort("'%s' not found on the system path. Please extend the PATH environment variable so that it provides the '%s' executable.", name, name);
            }
            path = optPath.get();
        }

        if (Files.isDirectory(path) || !Files.isExecutable(path)) {
            String subject = userDefinedPath != null ? SubstrateOptionsParser.commandArgument(Options.WasmToolsPath, userDefinedPath) : "Default wasm-tools executable '" + path + "'";
            throw UserError.abort("%s does not specify a path to an executable.", subject);
        }
        return path;
    }

    private static UserError.UserException rethrowWithInfo(UserError.UserException err) {
        List<String> messages = new ArrayList<>();
        err.getMessages().forEach(messages::add);
        if (!Options.WasmToolsPath.hasBeenSet()) {
            messages.add("A custom path to the " + EXECUTABLE + " executable can be set with the " + SubstrateOptionsParser.commandArgument(Options.WasmToolsPath, "<path>") + " command-line option");
        }
        messages.add("'%s' is part of the %s project (%s). At least version %d.%d.0 is required.".formatted(EXECUTABLE, PROJECT_NAME, URL, MINIMUM_MAJOR, MINIMUM_MINOR));
        messages.add("To prevent native-toolchain checking provide command-line option " + SubstrateOptionsParser.commandArgument(SubstrateOptions.CheckToolchain, "-"));
        return UserError.abort(messages);
    }

    /**
     * Turns the core module into a component implementing the given world.
     *
     * @param witPath Directory (or file) containing the WIT package that defines the world
     * @param world Fully qualified world name, for example {@code wasi:cli/command}
     * @param coreModule The core module
     * @param embedded Path for the intermediate module with the embedded component type
     * @param component Path for the resulting component
     */
    public void componentize(Path witPath, String world, Path coreModule, Path embedded, Path component, OptionValues options, Consumer<String> printer) {
        runChecked(List.of("component", "embed", "--world", world, witPath.toString(), coreModule.toString(), "-o", embedded.toString()), options, printer);
        runChecked(List.of("component", "new", embedded.toString(), "-o", component.toString()), options, printer);
    }

    private void runChecked(List<String> args, OptionValues options, Consumer<String> printer) {
        RunResult result;
        try {
            result = run(executable, args);
        } catch (InterruptedException e) {
            throw new InterruptImageBuilding("Interrupted while running " + executable);
        } catch (IOException e) {
            throw UserError.abort(e, "Running wasm-tools failed");
        }

        List<String> outLines = result.outputLines();
        if (!outLines.isEmpty() && (result.exitCode() != 0 || DebugOptions.LogVerbose.getValue(options))) {
            printer.accept("Output for " + result.commandLine() + ":");
            outLines.forEach(printer);
        }
        UserError.guarantee(result.exitCode() == 0, "%s failed with exit code: %s", SubstrateUtil.getShellCommandString(result.commandLine(), false), result.exitCode());
    }

    private static RunResult run(Path executable, List<String> args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable.toString());
        command.addAll(args);

        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (InputStream inputStream = p.getInputStream()) {
            List<String> outLines = FileUtils.readAllLines(inputStream);
            int exitCode = p.waitFor();
            return new RunResult(command, outLines, exitCode);
        }
    }

    private static String formatCommandInfo(RunResult result) {
        return "Command '%s' failed with exit code %d. Output:%n%s".formatted(SubstrateUtil.getShellCommandString(result.commandLine(), false), result.exitCode(),
                        result.outputLines().stream().map(str -> "  " + str).collect(Collectors.joining(System.lineSeparator())));
    }

    public record RunResult(List<String> commandLine, List<String> outputLines, int exitCode) {
    }
}
