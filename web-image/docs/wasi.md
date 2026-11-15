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
`System.exit` ends the program at once through `wasi:cli/exit`, also when a caller would catch `Throwable`.
An uncaught exception prints its stack trace to the standard error stream and exits with code 1.

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
The Java runtime uses the interfaces of `wasi:cli/imports`, which the world can include itself:

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

A world that is selected by its qualified name (`namespace:package/world`, optionally with `@version`) does not have to include `wasi:cli/imports`.
The builder then embeds a world that includes both the selected world and `wasi:cli/imports`, and adds the bundled WASI packages if the WIT directory does not contain `wasi:cli`.
This also covers worlds that live in a package under `deps/` while the root package is empty, which is the layout that generators of WIT trees commonly produce.
Imports that the image does not use are removed from the component.
Interfaces and functions that are marked `@unstable` in the WIT files are available.

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
The generator skips the WASI packages that the Java runtime is built on (`wasi:cli`, `wasi:clocks`, `wasi:random`, `wasi:filesystem`, and `wasi:sockets`).
Generate them too with `--include-wasi` to use, for example, `wasi:cli/stdout#write-via-stream` directly.
Other WASI packages are generated like any other package, so a world that imports `wasi:http/types` and exports `wasi:http/handler` produces a `Handler` interface to implement, with `Request`, `Response`, and `Fields` classes.

Exported resources become Java interfaces; their constructors and static functions are methods of the exported interface (`newCounter`, `counterGet`).
An imported resource that is passed to the host by value must be given up with `transfer()`, which returns its handle and invalidates the Java object.

An exported `async func` keeps running after its result is returned while streams it writes are still open, so an implementation can return a stream right away and fill it from continuations.
`WitFuture.cancel()` cancels a pending call to the host; a cancelled export has its future cancelled and its continuations run with a `CancelledException`.

Components built this way can be linked with other components, for example with `wac plug client.wasm --plug provider.wasm -o linked.wasm`, and the linked component runs with `wasmtime run`.

Not supported yet: `error-context` (not used by WASI 0.3), cancelling a pending stream read or write, and flags with more than 32 fields.

## Stack Traces

Exceptions have Java stack traces with class, method, file, and line number, including the frames of inlined methods:

```
java.lang.IllegalStateException: wrapped
	at Trace.wrap(Trace.java:6)
	at Trace.main(Trace.java:8)
Caused by: java.lang.NullPointerException
	at Trace.depth(Trace.java:4)
	at Trace.lambda$wrap$0(Trace.java:6)
```

The host is not involved: the runtime records the call sites on its own stack and decodes them when a stack trace is printed or queried.
`Throwable.getStackTrace`, `Thread.getStackTrace`, `StackWalker`, and the caller lookup of caller-sensitive methods of the JDK use the same information.
The tables for this make a component larger, about 40 percent for a small program.
Build with `-H:+DisableStackTraces` to leave them out; stack traces are then empty and `StackWalker` is not supported.

## Threads

By default the target is single-threaded: `Thread.start` has no effect and `LockSupport.park` is not supported.
Build with `-H:+WasiThreads` to run Java threads as cooperative threads of the Component Model:

```shell
native-image --tool:svm-wasi -H:+UnlockExperimentalVMOptions -H:+WasiThreads -cp . Main
wasmtime run -W component-model-threading main.wasm
```

The component then imports the `thread.*` built-ins and needs a runtime with the Component Model threading feature, which is `-W component-model-threading` in Wasmtime.

Threads never run in parallel and are not preempted.
One thread runs until it blocks, and then the next runnable thread continues.
A thread blocks in `Thread.sleep`, `Thread.yield`, `Thread.join`, `Object.wait`, `LockSupport.park` and everything built on it (`java.util.concurrent` locks, queues, executors, `CompletableFuture`), and when it ends.
A thread that computes without blocking keeps the other threads waiting.

The following works as on a JVM: thread pools and scheduled executors, `wait` and `notify`, interrupts, thread-local variables, daemon threads, uncaught exception handlers, and the program going on after `main` until the last thread that is not a daemon has ended.
Every thread has its own stack of 512 KB by default, which `-H:WasiThreadStackSize=<bytes>` changes.

Keep the following differences in mind:

* A `synchronized` block does not keep other threads out while its thread blocks inside of it by other means than `wait`. Because threads only switch where a thread blocks, a `synchronized` block that does not block is still atomic.
* A call to the host that blocks, for example reading a file or a synchronous import, blocks all threads.
* In a component that is driven through its exports, threads only run while an export is executing. Before an export returns to the host, the runtime lets the other threads run until all of them are blocked; a timer of a background thread does not fire between two calls.
* An asynchronous export may block, for example by waiting for a result that another thread produces. The other threads run meanwhile, and the host does not start another call of the component until the export returns or waits for the host.

## Limitations

* Only directories preopened by the host (`--dir` in Wasmtime) are backed by the host file system. All other paths, for example `/tmp`, live in an in-memory file system that is empty at startup, as in the JavaScript hosts.
* File attributes are limited to the basic view: POSIX permissions, owners, hard links, and changing timestamps are not supported.
* Sockets and HTTP are not supported.
* The `@JS` annotation and other JavaScript interoperability features are not available. Using them results in a build error.
* Threads need a build option and a runtime feature, and they are cooperative (see [Threads](#threads)).
