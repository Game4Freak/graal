---
layout: docs
title: WASI Target
link_title: WASI Target
permalink: /reference-manual/web-image/wasi/
toc_group: web-image
---

# Building WASI Components with Web Image

The WASI target of Web Image compiles a JVM application into a standalone [WebAssembly component](https://component-model.bytecodealliance.org/) that implements the `wasi:cli/command` world of [WASI 0.3](https://wasi.dev/).
The component has no JavaScript wrapper and runs in any runtime that supports WASI 0.3, for example [Wasmtime](https://wasmtime.dev/) 46 or later.

> Note: The WASI target is experimental and under active development. It is currently only available through the `web-image` launcher of a GraalVM source build.

## Prerequisites

In addition to the [prerequisites of Web Image](get-started.md#prerequisites), the WASI target needs:

* [`wasm-tools`](https://github.com/bytecodealliance/wasm-tools) 1.240 or later on the system path. It turns the compiled core module into a component. A different location can be set with `-H:WasmToolsPath=<path>`.
* A WASI 0.3 runtime to run the result, for example `wasmtime` 46 or later.

## Building

Select the target with the `WASI` backend:

```shell
web-image -H:Backend=WASI -cp . HelloWasi
```

The build produces the following files:

* _hellowasi.wasm_: the WebAssembly component. This is the file to run or distribute.
* _hellowasi.core.wasm_: the core WebAssembly module before it was wrapped into a component (build information).
* _hellowasi.wat_: the text format of the core module (build information).

## Running

Run the component with Wasmtime:

```shell
wasmtime run hellowasi.wasm arg1 arg2
```

Program arguments, environment variables, the working directory, and preopened directories are passed through the usual Wasmtime options:

```shell
wasmtime run --env NAME=value -S cwd=/work --dir .::/work hellowasi.wasm
```

The exit code of `System.exit` becomes the exit code of the process.
An uncaught exception prints a message to the standard error stream and exits with code 1.

## What Works

The following parts of the Java standard library are backed by WASI 0.3 interfaces:

| Java | WASI interface |
| --- | --- |
| `System.out`, `System.err`, `System.in` | `wasi:cli/stdout`, `wasi:cli/stderr`, `wasi:cli/stdin` |
| Program arguments, `System.getenv`, `user.dir` | `wasi:cli/environment` |
| `System.exit` | `wasi:cli/exit` |
| `System.currentTimeMillis`, `System.nanoTime`, `Thread.sleep` | `wasi:clocks/system-clock`, `wasi:clocks/monotonic-clock` |
| `SecureRandom`, `Math.random` | `wasi:random/random` |

`System.getProperty("os.name")` returns `WASI`, `os.arch` returns `wasm32`, and `os.version` returns the implemented WASI version.

## Limitations

* The file system is an in-memory file system, as in the JavaScript hosts. `wasi:filesystem` is not used yet, so preopened directories are not visible to Java code.
* Sockets and HTTP are not supported.
* Stack traces are not available (`-H:+DisableStackTraces` is the default for this target).
* The `@JS` annotation and other JavaScript interoperability features are not available. Using them results in a build error.
* The target is single-threaded.
