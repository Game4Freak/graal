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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.MapCursor;

import jdk.graal.compiler.util.json.JsonParser;

/**
 * The WIT model as produced by {@code wasm-tools component wit --json}.
 */
public final class WitModel {

    /** Reference to a type: a primitive name or the index of a {@link TypeDef}. */
    public sealed interface TypeRef permits Prim, Id {
    }

    public record Prim(String name) implements TypeRef {
    }

    public record Id(int id) implements TypeRef {
    }

    public sealed interface Kind permits Alias, Record, Variant, Enum, Flags, Option, Result, ListKind, Tuple, Resource, Handle, Stream, Future, Unsupported {
    }

    /** {@code stream<T>}; the element type is null for {@code stream} without payload. */
    public record Stream(TypeRef element) implements Kind {
    }

    /** {@code future<T>}; the payload type is null for {@code future} without payload. */
    public record Future(TypeRef payload) implements Kind {
    }

    public record Alias(TypeRef target) implements Kind {
    }

    public record Field(String name, TypeRef type) {
    }

    public record Record(List<Field> fields) implements Kind {
    }

    /** A variant case; {@code type} is null for cases without payload. */
    public record Case(String name, TypeRef type) {
    }

    public record Variant(List<Case> cases) implements Kind {
    }

    public record Enum(List<String> cases) implements Kind {
    }

    public record Flags(List<String> flags) implements Kind {
    }

    public record Option(TypeRef type) implements Kind {
    }

    /** {@code ok} and {@code err} may be null. */
    public record Result(TypeRef ok, TypeRef err) implements Kind {
    }

    public record ListKind(TypeRef element) implements Kind {
    }

    public record Tuple(List<TypeRef> types) implements Kind {
    }

    public record Resource() implements Kind {
    }

    public record Handle(boolean own, int resource) implements Kind {
    }

    public record Unsupported(String what) implements Kind {
    }

    public static final class TypeDef {
        public final int id;
        /** Null for anonymous types. */
        public final String name;
        public final Kind kind;
        /** Owning interface id, or -1 (world-owned or anonymous). */
        public final int ownerInterface;

        TypeDef(int id, String name, Kind kind, int ownerInterface) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.ownerInterface = ownerInterface;
        }
    }

    public record Param(String name, TypeRef type) {
    }

    public enum FunctionKind {
        FREESTANDING,
        CONSTRUCTOR,
        METHOD,
        STATIC
    }

    public static final class Function {
        public final String name;
        public final FunctionKind kind;
        /** Declared as {@code async func}. */
        public final boolean async;
        /** Resource type id for constructors, methods, and statics. */
        public final int resource;
        public final List<Param> params;
        /** Null if the function has no result. */
        public final TypeRef result;

        Function(String name, FunctionKind kind, boolean async, int resource, List<Param> params, TypeRef result) {
            this.name = name;
            this.kind = kind;
            this.async = async;
            this.resource = resource;
            this.params = params;
            this.result = result;
        }

        /** The plain function name without the {@code [method]resource.} style prefix. */
        public String plainName() {
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                return name.substring(dot + 1);
            }
            int bracket = name.indexOf(']');
            return bracket >= 0 ? name.substring(bracket + 1) : name;
        }
    }

    public static final class Interface {
        public final int id;
        /** Null for anonymous (inline) interfaces. */
        public final String name;
        public final Map<String, Integer> types;
        public final Map<String, Function> functions;
        /** Package index or -1. */
        public final int pkg;

        Interface(int id, String name, Map<String, Integer> types, Map<String, Function> functions, int pkg) {
            this.id = id;
            this.name = name;
            this.types = types;
            this.functions = functions;
            this.pkg = pkg;
        }
    }

    public sealed interface WorldItem permits InterfaceItem, FunctionItem, TypeItem {
    }

    public record InterfaceItem(int id) implements WorldItem {
    }

    public record FunctionItem(Function function) implements WorldItem {
    }

    public record TypeItem(int id) implements WorldItem {
    }

    public static final class World {
        public final String name;
        public final Map<String, WorldItem> imports;
        public final Map<String, WorldItem> exports;
        public final int pkg;

        World(String name, Map<String, WorldItem> imports, Map<String, WorldItem> exports, int pkg) {
            this.name = name;
            this.imports = imports;
            this.exports = exports;
            this.pkg = pkg;
        }
    }

    public static final class Package {
        /** For example {@code wasi:cli@0.3.0} or {@code test:calc}. */
        public final String name;
        public final Map<String, Integer> interfaces;
        public final Map<String, Integer> worlds;

        Package(String name, Map<String, Integer> interfaces, Map<String, Integer> worlds) {
            this.name = name;
            this.interfaces = interfaces;
            this.worlds = worlds;
        }

        public String namespace() {
            return name.substring(0, name.indexOf(':'));
        }

        public String packageName() {
            String rest = name.substring(name.indexOf(':') + 1);
            int at = rest.indexOf('@');
            return at >= 0 ? rest.substring(0, at) : rest;
        }

        public String version() {
            int at = name.indexOf('@');
            return at >= 0 ? name.substring(at + 1) : null;
        }
    }

    public final List<TypeDef> types = new ArrayList<>();
    public final List<Interface> interfaces = new ArrayList<>();
    public final List<World> worlds = new ArrayList<>();
    public final List<Package> packages = new ArrayList<>();

    // Parsing

    @SuppressWarnings("unchecked")
    public static WitModel parse(String json) throws IOException {
        EconomicMap<String, Object> root = JsonParser.parseDict(json);
        WitModel model = new WitModel();
        List<Object> types = (List<Object>) root.get("types");
        for (int i = 0; i < types.size(); i++) {
            model.types.add(parseType(i, (EconomicMap<String, Object>) types.get(i)));
        }
        List<Object> interfaces = (List<Object>) root.get("interfaces");
        for (int i = 0; i < interfaces.size(); i++) {
            model.interfaces.add(parseInterface(i, (EconomicMap<String, Object>) interfaces.get(i)));
        }
        for (Object world : (List<Object>) root.get("worlds")) {
            model.worlds.add(parseWorld((EconomicMap<String, Object>) world));
        }
        for (Object pkg : (List<Object>) root.get("packages")) {
            EconomicMap<String, Object> p = (EconomicMap<String, Object>) pkg;
            model.packages.add(new Package((String) p.get("name"), intMap(p.get("interfaces")), intMap(p.get("worlds"))));
        }
        return model;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> intMap(Object o) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (o != null) {
            MapCursor<String, Object> cursor = ((EconomicMap<String, Object>) o).getEntries();
            while (cursor.advance()) {
                result.put(cursor.getKey(), toInt(cursor.getValue()));
            }
        }
        return result;
    }

    private static int toInt(Object o) {
        return ((Number) o).intValue();
    }

    private static TypeRef typeRef(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            return new Prim(s);
        }
        return new Id(toInt(o));
    }

    @SuppressWarnings("unchecked")
    private static TypeDef parseType(int id, EconomicMap<String, Object> t) {
        String name = (String) t.get("name");
        int owner = -1;
        Object ownerObj = t.get("owner");
        if (ownerObj instanceof EconomicMap<?, ?>) {
            EconomicMap<String, Object> ownerMap = (EconomicMap<String, Object>) ownerObj;
            if (ownerMap.get("interface") != null) {
                owner = toInt(ownerMap.get("interface"));
            }
        }
        Object kindObj = t.get("kind");
        if (kindObj instanceof String kindString) {
            // Kinds without payload, for example "resource"
            Kind simple = kindString.equals("resource") ? new Resource() : new Unsupported(kindString);
            return new TypeDef(id, name, simple, owner);
        }
        EconomicMap<String, Object> kind = (EconomicMap<String, Object>) kindObj;
        String kindName = kind.getKeys().iterator().next();
        Object value = kind.get(kindName);
        Kind k = switch (kindName) {
            case "type" -> new Alias(typeRef(value));
            case "record" -> {
                List<Field> fields = new ArrayList<>();
                for (Object f : (List<Object>) ((EconomicMap<String, Object>) value).get("fields")) {
                    EconomicMap<String, Object> fm = (EconomicMap<String, Object>) f;
                    fields.add(new Field((String) fm.get("name"), typeRef(fm.get("type"))));
                }
                yield new Record(fields);
            }
            case "variant" -> {
                List<Case> cases = new ArrayList<>();
                for (Object c : (List<Object>) ((EconomicMap<String, Object>) value).get("cases")) {
                    EconomicMap<String, Object> cm = (EconomicMap<String, Object>) c;
                    cases.add(new Case((String) cm.get("name"), typeRef(cm.get("type"))));
                }
                yield new Variant(cases);
            }
            case "enum" -> {
                List<String> cases = new ArrayList<>();
                for (Object c : (List<Object>) ((EconomicMap<String, Object>) value).get("cases")) {
                    cases.add((String) ((EconomicMap<String, Object>) c).get("name"));
                }
                yield new Enum(cases);
            }
            case "flags" -> {
                List<String> flags = new ArrayList<>();
                for (Object c : (List<Object>) ((EconomicMap<String, Object>) value).get("flags")) {
                    flags.add((String) ((EconomicMap<String, Object>) c).get("name"));
                }
                yield new Flags(flags);
            }
            case "option" -> new Option(typeRef(value));
            case "result" -> {
                EconomicMap<String, Object> r = (EconomicMap<String, Object>) value;
                yield new Result(typeRef(r.get("ok")), typeRef(r.get("err")));
            }
            case "list" -> new ListKind(typeRef(value));
            case "tuple" -> {
                List<TypeRef> types = new ArrayList<>();
                for (Object e : (List<Object>) ((EconomicMap<String, Object>) value).get("types")) {
                    types.add(typeRef(e));
                }
                yield new Tuple(types);
            }
            case "resource" -> new Resource();
            case "stream" -> new Stream(typeRef(value));
            case "future" -> new Future(typeRef(value));
            case "handle" -> {
                EconomicMap<String, Object> h = (EconomicMap<String, Object>) value;
                boolean own = h.containsKey("own");
                yield new Handle(own, toInt(own ? h.get("own") : h.get("borrow")));
            }
            default -> new Unsupported(kindName);
        };
        return new TypeDef(id, name, k, owner);
    }

    @SuppressWarnings("unchecked")
    private static Function parseFunction(EconomicMap<String, Object> f) {
        String name = (String) f.get("name");
        Object kindObj = f.get("kind");
        FunctionKind kind = FunctionKind.FREESTANDING;
        boolean async = false;
        int resource = -1;
        if (kindObj instanceof String ks) {
            async = ks.startsWith("async-");
        } else if (kindObj instanceof EconomicMap<?, ?> km) {
            String k = ((EconomicMap<String, Object>) km).getKeys().iterator().next();
            if (k.startsWith("async-")) {
                async = true;
                k = k.substring("async-".length());
            }
            kind = switch (k) {
                case "constructor" -> FunctionKind.CONSTRUCTOR;
                case "method" -> FunctionKind.METHOD;
                case "static" -> FunctionKind.STATIC;
                default -> FunctionKind.FREESTANDING;
            };
            resource = toInt(((EconomicMap<String, Object>) km).get(k.equals("freestanding") ? k : (async ? "async-" + k : k)));
        }
        List<Param> params = new ArrayList<>();
        for (Object p : (List<Object>) f.get("params")) {
            EconomicMap<String, Object> pm = (EconomicMap<String, Object>) p;
            params.add(new Param((String) pm.get("name"), typeRef(pm.get("type"))));
        }
        return new Function(name, kind, async, resource, params, typeRef(f.get("result")));
    }

    @SuppressWarnings("unchecked")
    private static Interface parseInterface(int id, EconomicMap<String, Object> i) {
        Map<String, Function> functions = new LinkedHashMap<>();
        Object fs = i.get("functions");
        if (fs != null) {
            MapCursor<String, Object> cursor = ((EconomicMap<String, Object>) fs).getEntries();
            while (cursor.advance()) {
                functions.put(cursor.getKey(), parseFunction((EconomicMap<String, Object>) cursor.getValue()));
            }
        }
        int pkg = i.get("package") == null ? -1 : toInt(i.get("package"));
        return new Interface(id, (String) i.get("name"), intMap(i.get("types")), functions, pkg);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, WorldItem> parseWorldItems(Object o) {
        Map<String, WorldItem> items = new LinkedHashMap<>();
        if (o == null) {
            return items;
        }
        MapCursor<String, Object> cursor = ((EconomicMap<String, Object>) o).getEntries();
        while (cursor.advance()) {
            EconomicMap<String, Object> item = (EconomicMap<String, Object>) cursor.getValue();
            String kind = item.getKeys().iterator().next();
            Object value = item.get(kind);
            WorldItem wi = switch (kind) {
                case "interface" -> new InterfaceItem(toInt(((EconomicMap<String, Object>) value).get("id")));
                case "function" -> new FunctionItem(parseFunction((EconomicMap<String, Object>) value));
                case "type" -> new TypeItem(toInt(value));
                default -> throw new IllegalArgumentException("Unknown world item kind: " + kind);
            };
            items.put(cursor.getKey(), wi);
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static World parseWorld(EconomicMap<String, Object> w) {
        int pkg = w.get("package") == null ? -1 : toInt(w.get("package"));
        return new World((String) w.get("name"), parseWorldItems(w.get("imports")), parseWorldItems(w.get("exports")), pkg);
    }

    // Queries

    public TypeDef type(int id) {
        return types.get(id);
    }

    /**
     * Follows aliases to the defining type.
     */
    public TypeRef resolve(TypeRef ref) {
        TypeRef current = ref;
        while (current instanceof Id id && type(id.id()).kind instanceof Alias alias) {
            current = alias.target();
        }
        return current;
    }

    /**
     * The defining (non-alias) type definition of a reference, or null for primitives.
     */
    public TypeDef definition(TypeRef ref) {
        TypeRef r = resolve(ref);
        return r instanceof Id id ? type(id.id()) : null;
    }

    public Interface interfaceOf(int id) {
        return interfaces.get(id);
    }

    public Package packageOf(int index) {
        return packages.get(index);
    }

    /**
     * The fully qualified interface name as used in import and export names, for example
     * {@code wasi:cli/stdout@0.3.0}.
     */
    public String qualifiedName(Interface iface) {
        if (iface.pkg < 0 || iface.name == null) {
            throw new IllegalArgumentException("Interface " + iface.id + " has no package or name");
        }
        Package pkg = packageOf(iface.pkg);
        String version = pkg.version();
        return pkg.namespace() + ":" + pkg.packageName() + "/" + iface.name + (version != null ? "@" + version : "");
    }

    public World findWorld(String qualified) {
        int slash = qualified.lastIndexOf('/');
        String worldName = slash >= 0 ? qualified.substring(slash + 1) : qualified;
        String pkgName = slash >= 0 ? qualified.substring(0, slash) : null;
        for (World world : worlds) {
            if (!world.name.equals(worldName)) {
                continue;
            }
            if (pkgName == null) {
                return world;
            }
            Package pkg = world.pkg >= 0 ? packageOf(world.pkg) : null;
            if (pkg != null && (pkg.name.equals(pkgName) || (pkg.namespace() + ":" + pkg.packageName()).equals(pkgName))) {
                return world;
            }
        }
        return null;
    }
}
