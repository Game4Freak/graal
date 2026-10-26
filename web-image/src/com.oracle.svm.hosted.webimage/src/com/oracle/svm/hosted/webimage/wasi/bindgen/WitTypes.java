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

import java.util.ArrayList;
import java.util.List;

import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Alias;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Case;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Enum;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Field;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Flags;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Future;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Handle;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Kind;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.ListKind;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Option;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Prim;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Record;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Resource;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Stream;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Result;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Tuple;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.TypeDef;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.TypeRef;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Unsupported;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Variant;

/**
 * Java type mapping and Canonical ABI layout (32-bit) of WIT types.
 */
public final class WitTypes {

    public enum Flat {
        I32,
        I64,
        F32,
        F64
    }

    public static final int MAX_FLAT_PARAMS = 16;
    public static final int MAX_FLAT_ASYNC_PARAMS = 4;
    public static final int MAX_FLAT_RESULTS = 1;

    private final WitModel model;

    public WitTypes(WitModel model) {
        this.model = model;
    }

    public WitModel model() {
        return model;
    }

    /** The defining kind of a reference, or null for primitives. */
    public Kind kind(TypeRef ref) {
        TypeDef def = model.definition(ref);
        return def == null ? null : def.kind;
    }

    public String prim(TypeRef ref) {
        return model.resolve(ref) instanceof Prim p ? p.name() : null;
    }

    public TypeDef def(TypeRef ref) {
        return model.definition(ref);
    }

    public static UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("WIT type not supported by the generator: " + what);
    }

    // Java types

    /** Adapter class per exported resource (by defining type id). */
    private final java.util.Map<Integer, String> exportedResourceAdapters = new java.util.HashMap<>();

    public void registerExportedResource(int resourceId, String adapterClass) {
        exportedResourceAdapters.put(resourceId, adapterClass);
    }

    /**
     * The adapter class holding the handle table of an exported resource, or null for imported
     * resources.
     */
    public String exportedResourceAdapter(TypeDef resourceDef) {
        TypeDef resolved = model.definition(new WitModel.Id(resourceDef.id));
        return exportedResourceAdapters.get((resolved != null ? resolved : resourceDef).id);
    }

    private final java.util.Map<Integer, String> classNames = new java.util.HashMap<>();
    private final java.util.Set<String> takenClassNames = new java.util.HashSet<>();

    /**
     * The name of the generated class for a named type definition. All classes live in one Java
     * package, so a type whose name is already used by a type of another WIT package gets the
     * package name as prefix (for example {@code FilesystemErrorCode}).
     */
    public String className(TypeDef aliasOrDef) {
        // Types imported with `use` are aliases of the defining type, which owns the class
        TypeDef resolved = model.definition(new WitModel.Id(aliasOrDef.id));
        TypeDef def = resolved != null ? resolved : aliasOrDef;
        if (def.name == null) {
            throw new IllegalArgumentException("Type " + def.id + " is anonymous");
        }
        String assigned = classNames.get(def.id);
        if (assigned != null) {
            return assigned;
        }
        String name = JavaNames.type(def.name);
        if (takenClassNames.contains(name)) {
            String prefix = "";
            if (def.ownerInterface >= 0) {
                WitModel.Interface owner = model.interfaceOf(def.ownerInterface);
                if (owner.pkg >= 0) {
                    prefix = JavaNames.type(model.packageOf(owner.pkg).packageName());
                }
            }
            String candidate = prefix + name;
            int counter = 2;
            while (takenClassNames.contains(candidate)) {
                candidate = prefix + name + counter++;
            }
            name = candidate;
        }
        takenClassNames.add(name);
        classNames.put(def.id, name);
        return name;
    }

    public String javaType(TypeRef ref, boolean boxed) {
        String prim = prim(ref);
        if (prim != null) {
            return switch (prim) {
                case "bool" -> boxed ? "Boolean" : "boolean";
                case "u8", "s8" -> boxed ? "Byte" : "byte";
                case "u16", "s16" -> boxed ? "Short" : "short";
                case "u32", "s32", "char" -> boxed ? "Integer" : "int";
                case "u64", "s64" -> boxed ? "Long" : "long";
                case "f32" -> boxed ? "Float" : "float";
                case "f64" -> boxed ? "Double" : "double";
                case "string" -> "String";
                default -> throw unsupported(prim);
            };
        }
        TypeDef def = def(ref);
        return switch (def.kind) {
            case Record r -> className(def);
            case Variant v -> className(def);
            case Enum e -> className(def);
            case Flags f -> className(def);
            case Resource r -> className(def);
            case Handle h -> className(model.type(h.resource()));
            case Option o -> "java.util.Optional<" + javaType(o.type(), true) + ">";
            case Result r -> "WitResult<" + (r.ok() == null ? "Void" : javaType(r.ok(), true)) + ", " + (r.err() == null ? "Void" : javaType(r.err(), true)) + ">";
            case ListKind l -> isByteList(l) ? "byte[]" : "java.util.List<" + javaType(l.element(), true) + ">";
            case Tuple t -> {
                StringBuilder sb = new StringBuilder("Tuple").append(t.types().size()).append('<');
                for (int i = 0; i < t.types().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(javaType(t.types().get(i), true));
                }
                yield sb.append('>').toString();
            }
            case Stream st -> "WitStream<" + (st.element() == null ? "Void" : javaType(st.element(), true)) + ">";
            case Future fu -> "WitPromise<" + (fu.payload() == null ? "Void" : javaType(fu.payload(), true)) + ">";
            case Alias a -> throw new IllegalStateException("Unresolved alias");
            case Unsupported u -> throw unsupported(u.what());
        };
    }

    /**
     * The number of {@code stream} and {@code future} occurrences in the type, in the order used
     * by {@code wasm-tools} to index the Canonical ABI built-ins (post-order: payload first).
     */
    public int asyncCount(TypeRef ref) {
        if (ref == null || prim(ref) != null) {
            return 0;
        }
        TypeDef def = def(ref);
        return switch (def.kind) {
            case Record r -> {
                int n = 0;
                for (Field f : r.fields()) {
                    n += asyncCount(f.type());
                }
                yield n;
            }
            case Tuple t -> {
                int n = 0;
                for (TypeRef e : t.types()) {
                    n += asyncCount(e);
                }
                yield n;
            }
            case Variant v -> {
                int n = 0;
                for (Case c : v.cases()) {
                    n += asyncCount(c.type());
                }
                yield n;
            }
            case Option o -> asyncCount(o.type());
            case Result r -> asyncCount(r.ok()) + asyncCount(r.err());
            case ListKind l -> asyncCount(l.element());
            case Stream st -> asyncCount(st.element()) + 1;
            case Future fu -> asyncCount(fu.payload()) + 1;
            default -> 0;
        };
    }

    public boolean containsAsync(TypeRef ref) {
        return asyncCount(ref) > 0;
    }

    public boolean isByteList(ListKind l) {
        String p = prim(l.element());
        return "u8".equals(p) || "s8".equals(p);
    }

    /** Name used for the per-type helper methods in the generated Abi class. */
    public String helperName(TypeRef ref) {
        TypeDef def = def(ref);
        if (def == null) {
            return prim(ref);
        }
        if (def.name != null && !(def.kind instanceof Option) && !(def.kind instanceof Result) && !(def.kind instanceof ListKind) && !(def.kind instanceof Tuple)) {
            return className(def);
        }
        return "t" + def.id;
    }

    // Layout

    public static int alignTo(int offset, int align) {
        return (offset + align - 1) / align * align;
    }

    public static int discriminantSize(int cases) {
        if (cases <= 256) {
            return 1;
        }
        if (cases <= 65536) {
            return 2;
        }
        return 4;
    }

    public int size(TypeRef ref) {
        String prim = prim(ref);
        if (prim != null) {
            return switch (prim) {
                case "bool", "u8", "s8" -> 1;
                case "u16", "s16" -> 2;
                case "u32", "s32", "char", "f32" -> 4;
                case "u64", "s64", "f64" -> 8;
                case "string" -> 8;
                default -> throw unsupported(prim);
            };
        }
        TypeDef def = def(ref);
        return switch (def.kind) {
            case Record r -> recordSize(fieldTypes(r.fields()));
            case Tuple t -> recordSize(t.types());
            case Variant v -> variantSize(caseTypes(v.cases()));
            case Option o -> variantSize(java.util.Arrays.asList(null, o.type()));
            case Result r -> variantSize(java.util.Arrays.asList(r.ok(), r.err()));
            case Enum e -> discriminantSize(e.cases().size());
            case Flags f -> flagsSize(f.flags().size());
            case ListKind l -> 8;
            case Handle h -> 4;
            case Resource r -> 4;
            case Stream st -> 4;
            case Future fu -> 4;
            case Alias a -> throw new IllegalStateException("Unresolved alias");
            case Unsupported u -> throw unsupported(u.what());
        };
    }

    public int align(TypeRef ref) {
        String prim = prim(ref);
        if (prim != null) {
            return switch (prim) {
                case "bool", "u8", "s8" -> 1;
                case "u16", "s16" -> 2;
                case "u32", "s32", "char", "f32", "string" -> 4;
                case "u64", "s64", "f64" -> 8;
                default -> throw unsupported(prim);
            };
        }
        TypeDef def = def(ref);
        return switch (def.kind) {
            case Record r -> recordAlign(fieldTypes(r.fields()));
            case Tuple t -> recordAlign(t.types());
            case Variant v -> variantAlign(caseTypes(v.cases()));
            case Option o -> variantAlign(java.util.Arrays.asList(null, o.type()));
            case Result r -> variantAlign(java.util.Arrays.asList(r.ok(), r.err()));
            case Enum e -> discriminantSize(e.cases().size());
            case Flags f -> flagsSize(f.flags().size());
            case ListKind l -> 4;
            case Handle h -> 4;
            case Resource r -> 4;
            case Stream st -> 4;
            case Future fu -> 4;
            case Alias a -> throw new IllegalStateException("Unresolved alias");
            case Unsupported u -> throw unsupported(u.what());
        };
    }

    public static int flagsSize(int count) {
        if (count <= 8) {
            return 1;
        }
        if (count <= 16) {
            return 2;
        }
        if (count <= 32) {
            return 4;
        }
        throw unsupported("flags with more than 32 fields");
    }

    public static List<TypeRef> fieldTypes(List<Field> fields) {
        List<TypeRef> types = new ArrayList<>();
        for (Field f : fields) {
            types.add(f.type());
        }
        return types;
    }

    /** Case payload types, null entries for cases without payload. */
    public static List<TypeRef> caseTypes(List<Case> cases) {
        List<TypeRef> types = new ArrayList<>();
        for (Case c : cases) {
            types.add(c.type());
        }
        return types;
    }

    /** Field offsets of a record (or tuple). */
    public List<Integer> offsets(List<TypeRef> types) {
        List<Integer> offsets = new ArrayList<>();
        int offset = 0;
        for (TypeRef t : types) {
            offset = alignTo(offset, align(t));
            offsets.add(offset);
            offset += size(t);
        }
        return offsets;
    }

    public int recordAlign(List<TypeRef> types) {
        int a = 1;
        for (TypeRef t : types) {
            a = Math.max(a, align(t));
        }
        return a;
    }

    public int recordSize(List<TypeRef> types) {
        int size = 0;
        for (TypeRef t : types) {
            size = alignTo(size, align(t)) + size(t);
        }
        return alignTo(size, recordAlign(types));
    }

    public int maxCaseAlign(List<TypeRef> cases) {
        int a = 1;
        for (TypeRef t : cases) {
            if (t != null) {
                a = Math.max(a, align(t));
            }
        }
        return a;
    }

    public int variantAlign(List<TypeRef> cases) {
        return Math.max(discriminantSize(cases.size()), maxCaseAlign(cases));
    }

    /** Offset of the payload in a variant. */
    public int payloadOffset(List<TypeRef> cases) {
        return alignTo(discriminantSize(cases.size()), maxCaseAlign(cases));
    }

    public int variantSize(List<TypeRef> cases) {
        int maxSize = 0;
        for (TypeRef t : cases) {
            if (t != null) {
                maxSize = Math.max(maxSize, size(t));
            }
        }
        return alignTo(payloadOffset(cases) + maxSize, variantAlign(cases));
    }

    // Flattening

    public List<Flat> flats(TypeRef ref) {
        List<Flat> result = new ArrayList<>();
        collectFlats(ref, result);
        return result;
    }

    private void collectFlats(TypeRef ref, List<Flat> out) {
        String prim = prim(ref);
        if (prim != null) {
            switch (prim) {
                case "bool", "u8", "s8", "u16", "s16", "u32", "s32", "char" -> out.add(Flat.I32);
                case "u64", "s64" -> out.add(Flat.I64);
                case "f32" -> out.add(Flat.F32);
                case "f64" -> out.add(Flat.F64);
                case "string" -> {
                    out.add(Flat.I32);
                    out.add(Flat.I32);
                }
                default -> throw unsupported(prim);
            }
            return;
        }
        TypeDef def = def(ref);
        switch (def.kind) {
            case Record r -> {
                for (Field f : r.fields()) {
                    collectFlats(f.type(), out);
                }
            }
            case Tuple t -> {
                for (TypeRef e : t.types()) {
                    collectFlats(e, out);
                }
            }
            case Variant v -> out.addAll(variantFlats(caseTypes(v.cases())));
            case Option o -> out.addAll(variantFlats(java.util.Arrays.asList(null, o.type())));
            case Result r -> out.addAll(variantFlats(java.util.Arrays.asList(r.ok(), r.err())));
            case Enum e -> out.add(Flat.I32);
            case Flags f -> out.add(Flat.I32);
            case ListKind l -> {
                out.add(Flat.I32);
                out.add(Flat.I32);
            }
            case Handle h -> out.add(Flat.I32);
            case Resource r -> out.add(Flat.I32);
            case Stream st -> out.add(Flat.I32);
            case Future fu -> out.add(Flat.I32);
            case Alias a -> throw new IllegalStateException("Unresolved alias");
            case Unsupported u -> throw unsupported(u.what());
        }
    }

    /** Discriminant followed by the joined payload flats. */
    public List<Flat> variantFlats(List<TypeRef> cases) {
        List<Flat> joined = new ArrayList<>();
        for (TypeRef c : cases) {
            if (c == null) {
                continue;
            }
            List<Flat> caseFlats = flats(c);
            for (int i = 0; i < caseFlats.size(); i++) {
                if (i < joined.size()) {
                    joined.set(i, join(joined.get(i), caseFlats.get(i)));
                } else {
                    joined.add(caseFlats.get(i));
                }
            }
        }
        List<Flat> result = new ArrayList<>();
        result.add(Flat.I32);
        result.addAll(joined);
        return result;
    }

    private static Flat join(Flat a, Flat b) {
        if (a == b) {
            return a;
        }
        if ((a == Flat.I32 && b == Flat.F32) || (a == Flat.F32 && b == Flat.I32)) {
            return Flat.I32;
        }
        return Flat.I64;
    }

    public static String javaFlatType(Flat flat) {
        return switch (flat) {
            case I32 -> "int";
            case I64 -> "long";
            case F32 -> "float";
            case F64 -> "double";
        };
    }

    /** Expression converting a packed flat value ({@code long}) to a native parameter. */
    public static String unpack(Flat flat, String expr) {
        return switch (flat) {
            case I32 -> "(int) " + expr;
            case I64 -> expr;
            case F32 -> "Float.intBitsToFloat((int) " + expr + ")";
            case F64 -> "Double.longBitsToDouble(" + expr + ")";
        };
    }

    /** Expression packing a native value into a flat {@code long}. */
    public static String pack(Flat flat, String expr) {
        return switch (flat) {
            case I32 -> expr + " & 0xFFFFFFFFL";
            case I64 -> expr;
            case F32 -> "Float.floatToRawIntBits(" + expr + ") & 0xFFFFFFFFL";
            case F64 -> "Double.doubleToRawLongBits(" + expr + ")";
        };
    }
}
