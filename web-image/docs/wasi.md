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

> Note: The WASI target is experimental and under active development.

## Prerequisites

In addition to the [prerequisites of Web Image](get-started.md#prerequisites), the WASI target needs:

* [`wasm-tools`](https://github.com/bytecodealliance/wasm-tools) 1.240 or later on the system path. It turns the compiled core module into a component. A different location can be set with `-H:WasmToolsPath=<path>`.
* A WASI 0.3 runtime to run the result, for example `wasmtime` 46 or later.

## Building

Build with the `svm-wasi` tool:

```shell
native-image --tool:svm-wasi -cp . HelloWasi
```

The `--tool:svm-wasi` option must come first.
In a GraalVM source build, the `web-image` launcher can select the same target with a backend option:

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
| `java.nio.file` and `java.io` files under a preopened directory | `wasi:filesystem/types`, `wasi:filesystem/preopens` |

`System.getProperty("os.name")` returns `WASI`, `os.arch` returns `wasm32`, and `os.version` returns the implemented WASI version.

## Custom WIT Interfaces

A component can implement its own [WIT](https://component-model.bytecodealliance.org/design/wit.html) world instead of `wasi:cli/command`.
The world must include the WASI interfaces used by the Java runtime:

```wit
package docs:calc;

interface calculate {
    record point { x: f64, y: f64 }
    add: func(a: s32, b: s32) -> s32;
    centroid: func(points: list<point>) -> point;
}

world calculator {
    include wasi:cli/imports@0.3.0;
    export calculate;
}
```

Generate Java bindings for the world (the WASI packages must be available under `wit/deps`):

```shell
mx wit-bindgen --wit wit --world docs:calc/calculator --out src --java-package docs.calc
```

The generator produces one Java interface per exported WIT interface (`Calculate`), an adapter class with the Canonical ABI glue (`CalculateExports`), classes with static methods for imported interfaces, and Java types for the WIT types: records become Java records, variants become sealed interfaces, enums become Java enums, flags become records of booleans, `option<T>` becomes `Optional`, `result<T, E>` becomes `WitResult`, tuples become `TupleN` records, lists become `List` (or `byte[]` for `list<u8>`), and imported resources become classes with a `close()` method.
Implement the interface and register the implementation in `META-INF/services/docs.calc.Calculate` (or call `CalculateExports.setImplementation`), then build with the world selected:

```shell
native-image --tool:svm-wasi -H:+UnlockExperimentalVMOptions -H:WitDirectory=wit -H:WitWorld=docs:calc/calculator -cp . Main
wasmtime run --invoke 'add(2, 40)' main.wasm
```

Exported functions can be called before `main` runs; the runtime initializes itself on the first call.
Low-level bindings can also be written by hand with `@WitImport`, `@WitExport`, and `WitMemory` from `org.graalvm.webimage.api.wit`.

WIT `async func` imports return a `WitFuture<T>`; call `join()` on it from synchronous code, or chain continuations with `thenApply` and `thenCompose`.
Exported `async func` functions are declared as returning `WitFuture<T>` in the generated Java interface; the host is notified when the future completes, so the implementation must not block and should compose futures instead.

`stream<T>` values are `WitStream<T>` and `future<T>` values are `WitPromise<T>`: streams received from the host are read with `read(max)` or `readBytes(max)`, streams passed to the host are created with the generated `create<Function>Stream<N>()` factory, written, and closed.
Generate the WASI interfaces too with `--include-wasi` to use, for example, `wasi:cli/stdout#write-via-stream` directly.

Exported resources become Java interfaces; their constructors and static functions are methods of the exported interface (`newCounter`, `counterGet`).

Not supported yet: cancellation, `error-context`, and flags with more than 32 fields.

## Limitations

* Only directories preopened by the host (`--dir` in Wasmtime) are backed by the host file system. All other paths, for example `/tmp`, live in an in-memory file system that is empty at startup, as in the JavaScript hosts.
* File attributes are limited to the basic view: POSIX permissions, owners, hard links, and changing timestamps are not supported.
* Sockets and HTTP are not supported.
* Stack traces are not available (`-H:+DisableStackTraces` is the default for this target).
* The `@JS` annotation and other JavaScript interoperability features are not available. Using them results in a build error.
* The target is single-threaded.
