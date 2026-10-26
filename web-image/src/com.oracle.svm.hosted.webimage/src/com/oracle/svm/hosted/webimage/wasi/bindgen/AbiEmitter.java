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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Case;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Enum;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Field;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Flags;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Future;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Handle;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Kind;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.ListKind;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Option;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Record;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Resource;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Stream;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Result;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Tuple;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.TypeDef;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.TypeRef;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitModel.Variant;
import com.oracle.svm.hosted.webimage.wasi.bindgen.WitTypes.Flat;

/**
 * Emits the {@code Abi} class with lowering and lifting helpers for every type used by the
 * bindings.
 * <p>
 * Values crossing the Canonical ABI as "flat" core values are passed around as {@code long[]}
 * where {@code i32} values are stored sign-extended, {@code f32} values as their zero-extended
 * raw bits, and {@code f64} values as their raw bits. This makes the variant "join" rules of the
 * Canonical ABI a matter of zero-extending {@code i32} values into {@code i64} slots.
 */
public final class AbiEmitter {

    private final WitTypes types;
    private final JavaWriter w = new JavaWriter();
    private final Set<String> emitted = new LinkedHashSet<>();
    private final List<TypeRef> pending = new ArrayList<>();
    private final Set<Integer> tupleArities = new LinkedHashSet<>();

    public AbiEmitter(WitTypes types) {
        this.types = types;
    }

    public Set<Integer> tupleArities() {
        return tupleArities;
    }

    /**
     * Requests helpers for the given type (and transitively its components).
     */
    public void require(TypeRef ref) {
        if (types.prim(ref) != null) {
            return;
        }
        Kind kind = types.kind(ref);
        if (kind instanceof Enum || kind instanceof Flags || kind instanceof Resource || kind instanceof Handle || kind instanceof Stream || kind instanceof Future) {
            // Handled inline
            return;
        }
        String name = types.helperName(ref);
        if (emitted.add(name)) {
            pending.add(ref);
        }
    }

    private String helper(String prefix, TypeRef ref) {
        require(ref);
        return "Abi." + prefix + "_" + types.helperName(ref);
    }

    /** Extra arguments for helpers of types that contain streams or futures. */
    private String asyncArgs(TypeRef ref, String ops, String base) {
        return types.containsAsync(ref) ? ", " + ops + ", " + base : "";
    }

    /** Extra parameters for helpers of types that contain streams or futures. */
    private String asyncParams(TypeRef ref) {
        return types.containsAsync(ref) ? ", Object[] ops, int base" : "";
    }

    // Element codecs for streams and futures

    private final Set<String> codecs = new LinkedHashSet<>();

    /**
     * Expression for the codec of a stream element or future payload type.
     */
    public String codecExpr(TypeRef ref) {
        if (ref == null) {
            return "WitElementCodec.UNIT";
        }
        String prim = types.prim(ref);
        if ("u8".equals(prim) || "s8".equals(prim)) {
            return "WitElementCodec.BYTE";
        }
        if (types.containsAsync(ref)) {
            throw WitTypes.unsupported("streams or futures nested in stream elements");
        }
        String name = prim != null ? prim : types.helperName(ref);
        if (codecs.add(name)) {
            codecRefs.put(name, ref);
            require(ref);
        }
        return "Abi.CODEC_" + name;
    }

    private void emitCodecs() {
        // Codecs may require further codecs while being emitted, iterate over a snapshot each round
        List<String> done = new ArrayList<>();
        while (done.size() < codecs.size()) {
            for (String name : new ArrayList<>(codecs)) {
                if (done.contains(name)) {
                    continue;
                }
                done.add(name);
                TypeRef ref = codecRefs.get(name);
                String javaType = types.javaType(ref, true);
                w.open("static final WitElementCodec<" + javaType + "> CODEC_" + name + " = new WitElementCodec<>()");
                w.line("@Override");
                w.open("public int size()");
                w.line("return " + types.size(ref) + ";");
                w.close();
                w.line();
                w.line("@Override");
                w.open("public int align()");
                w.line("return " + types.align(ref) + ";");
                w.close();
                w.line();
                w.line("@Override");
                w.open("public void lower(" + javaType + " value, int address)");
                w.line(lowerStmt(ref, "value", "address"));
                w.close();
                w.line();
                w.line("@Override");
                w.open("public " + javaType + " lift(int address)");
                w.line("return " + liftExpr(ref, "address") + ";");
                w.close();
                w.close(";");
                w.line();
            }
        }
    }

    private final java.util.Map<String, TypeRef> codecRefs = new java.util.HashMap<>();

    // Inline code for memory lowering and lifting

    /**
     * Statement(s) writing {@code value} of the given type to memory at {@code addr}.
     */
    public String lowerStmt(TypeRef ref, String value, String addr) {
        return lowerStmt(ref, value, addr, "null", "0");
    }

    /**
     * @param ops expression for the built-in table of the enclosing function
     * @param base index of the first stream or future of this type in that table
     */
    public String lowerStmt(TypeRef ref, String value, String addr, String ops, String base) {
        String prim = types.prim(ref);
        if (prim != null) {
            return switch (prim) {
                case "bool" -> "WitMemory.writeByte(" + addr + ", (byte) (" + value + " ? 1 : 0));";
                case "u8", "s8" -> "WitMemory.writeByte(" + addr + ", " + value + ");";
                case "u16", "s16" -> "WitMemory.writeShort(" + addr + ", " + value + ");";
                case "u32", "s32", "char" -> "WitMemory.writeInt(" + addr + ", " + value + ");";
                case "u64", "s64" -> "WitMemory.writeLong(" + addr + ", " + value + ");";
                case "f32" -> "WitMemory.writeFloat(" + addr + ", " + value + ");";
                case "f64" -> "WitMemory.writeDouble(" + addr + ", " + value + ");";
                case "string" -> "Abi.lowerString(" + value + ", " + addr + ");";
                default -> throw WitTypes.unsupported(prim);
            };
        }
        Kind kind = types.kind(ref);
        if (kind instanceof Enum) {
            return writeDiscriminant(((Enum) kind).cases().size(), value + ".ordinal()", addr);
        }
        if (kind instanceof Handle || kind instanceof Resource) {
            return "WitMemory.writeInt(" + addr + ", " + toHandle(ref, value) + ");";
        }
        if (kind instanceof Flags f) {
            String bits = value + ".toBits()";
            return switch (WitTypes.flagsSize(f.flags().size())) {
                case 1 -> "WitMemory.writeByte(" + addr + ", (byte) " + bits + ");";
                case 2 -> "WitMemory.writeShort(" + addr + ", (short) " + bits + ");";
                default -> "WitMemory.writeInt(" + addr + ", " + bits + ");";
            };
        }
        if (kind instanceof Stream || kind instanceof Future) {
            return "WitMemory.writeInt(" + addr + ", " + value + ".transferReadable());";
        }
        return helper("lower", ref) + "(" + value + ", " + addr + asyncArgs(ref, ops, base) + ");";
    }

    /**
     * Expression reading a value of the given type from memory at {@code addr}.
     */
    public String liftExpr(TypeRef ref, String addr) {
        return liftExpr(ref, addr, "null", "0");
    }

    public String liftExpr(TypeRef ref, String addr, String ops, String base) {
        String prim = types.prim(ref);
        if (prim != null) {
            return switch (prim) {
                case "bool" -> "(WitMemory.readByte(" + addr + ") != 0)";
                case "u8", "s8" -> "WitMemory.readByte(" + addr + ")";
                case "u16", "s16" -> "WitMemory.readShort(" + addr + ")";
                case "u32", "s32", "char" -> "WitMemory.readInt(" + addr + ")";
                case "u64", "s64" -> "WitMemory.readLong(" + addr + ")";
                case "f32" -> "WitMemory.readFloat(" + addr + ")";
                case "f64" -> "WitMemory.readDouble(" + addr + ")";
                case "string" -> "Abi.liftString(" + addr + ")";
                default -> throw WitTypes.unsupported(prim);
            };
        }
        Kind kind = types.kind(ref);
        TypeDef def = types.def(ref);
        if (kind instanceof Enum e) {
            return types.className(def) + ".values()[" + readDiscriminant(e.cases().size(), addr) + "]";
        }
        if (kind instanceof Handle || kind instanceof Resource) {
            return fromHandle(ref, "WitMemory.readInt(" + addr + ")");
        }
        if (kind instanceof Flags f) {
            String bits = switch (WitTypes.flagsSize(f.flags().size())) {
                case 1 -> "(WitMemory.readByte(" + addr + ") & 0xFF)";
                case 2 -> "(WitMemory.readShort(" + addr + ") & 0xFFFF)";
                default -> "WitMemory.readInt(" + addr + ")";
            };
            return types.className(def) + ".fromBits(" + bits + ")";
        }
        if (kind instanceof Stream st) {
            return "WitStream.received((WitStreamOps) " + ops + "[" + base + " + " + types.asyncCount(st.element()) + "], " + codecExpr(st.element()) + ", WitMemory.readInt(" + addr + "))";
        }
        if (kind instanceof Future fu) {
            return "WitPromise.received((WitFutureOps) " + ops + "[" + base + " + " + types.asyncCount(fu.payload()) + "], " + codecExpr(fu.payload()) + ", WitMemory.readInt(" + addr + "))";
        }
        return helper("lift", ref) + "(" + addr + asyncArgs(ref, ops, base) + ")";
    }

    /** The resource definition behind a handle or resource reference. */
    private TypeDef resourceDef(TypeRef ref) {
        TypeDef def = types.def(ref);
        if (def.kind instanceof Handle h) {
            return types.model().definition(new WitModel.Id(h.resource()));
        }
        return def;
    }

    private boolean isOwn(TypeRef ref) {
        TypeDef def = types.def(ref);
        return !(def.kind instanceof Handle h) || h.own();
    }

    /**
     * Expression turning a Java resource value into a core handle. Passing an owned imported
     * resource transfers the handle, so the Java object no longer refers to it.
     */
    private String toHandle(TypeRef ref, String value) {
        TypeDef res = resourceDef(ref);
        String adapter = types.exportedResourceAdapter(res);
        if (adapter != null) {
            return adapter + ".toHandle" + types.className(res) + "(" + value + ")";
        }
        return isOwn(ref) ? value + ".transfer()" : value + ".handle()";
    }

    /**
     * Expression turning a core handle into a Java resource value. An owned handle of an exported
     * resource is dropped after the object was resolved, since the object (not the handle) is
     * what Java code holds on to.
     */
    private String fromHandle(TypeRef ref, String handle) {
        TypeDef res = resourceDef(ref);
        String adapter = types.exportedResourceAdapter(res);
        if (adapter != null) {
            return adapter + (isOwn(ref) ? ".takeOwn" : ".fromHandle") + types.className(res) + "(" + handle + ")";
        }
        return "new " + types.className(res) + "(" + handle + ")";
    }

    private static String writeDiscriminant(int cases, String value, String addr) {
        return switch (WitTypes.discriminantSize(cases)) {
            case 1 -> "WitMemory.writeByte(" + addr + ", (byte) (" + value + "));";
            case 2 -> "WitMemory.writeShort(" + addr + ", (short) (" + value + "));";
            default -> "WitMemory.writeInt(" + addr + ", " + value + ");";
        };
    }

    private static String readDiscriminant(int cases, String addr) {
        return switch (WitTypes.discriminantSize(cases)) {
            case 1 -> "(WitMemory.readByte(" + addr + ") & 0xFF)";
            case 2 -> "(WitMemory.readShort(" + addr + ") & 0xFFFF)";
            default -> "WitMemory.readInt(" + addr + ")";
        };
    }

    // Inline code for flat lowering and lifting

    /**
     * Statement(s) storing the flat values of {@code value} into {@code f[off...]}.
     */
    public String flatLowerStmt(TypeRef ref, String value, String f, String off) {
        return flatLowerStmt(ref, value, f, off, "null", "0");
    }

    public String flatLowerStmt(TypeRef ref, String value, String f, String off, String ops, String base) {
        String prim = types.prim(ref);
        if (prim != null) {
            return switch (prim) {
                case "bool" -> f + "[" + off + "] = " + value + " ? 1 : 0;";
                case "u8" -> f + "[" + off + "] = " + value + " & 0xFF;";
                case "u16" -> f + "[" + off + "] = " + value + " & 0xFFFF;";
                case "s8", "s16", "u32", "s32", "char", "u64", "s64" -> f + "[" + off + "] = " + value + ";";
                case "f32" -> f + "[" + off + "] = Float.floatToRawIntBits(" + value + ") & 0xFFFFFFFFL;";
                case "f64" -> f + "[" + off + "] = Double.doubleToRawLongBits(" + value + ");";
                case "string" -> "Abi.flatLowerString(" + value + ", " + f + ", " + off + ");";
                default -> throw WitTypes.unsupported(prim);
            };
        }
        Kind kind = types.kind(ref);
        if (kind instanceof Enum) {
            return f + "[" + off + "] = " + value + ".ordinal();";
        }
        if (kind instanceof Handle || kind instanceof Resource) {
            return f + "[" + off + "] = " + toHandle(ref, value) + ";";
        }
        if (kind instanceof Flags) {
            return f + "[" + off + "] = " + value + ".toBits() & 0xFFFFFFFFL;";
        }
        if (kind instanceof Stream || kind instanceof Future) {
            return f + "[" + off + "] = " + value + ".transferReadable();";
        }
        return helper("flatLower", ref) + "(" + value + ", " + f + ", " + off + asyncArgs(ref, ops, base) + ");";
    }

    /**
     * Expression building a value of the given type from the flat values {@code f[off...]}.
     */
    public String flatLiftExpr(TypeRef ref, String f, String off) {
        return flatLiftExpr(ref, f, off, "null", "0");
    }

    public String flatLiftExpr(TypeRef ref, String f, String off, String ops, String base) {
        String prim = types.prim(ref);
        String slot = f + "[" + off + "]";
        if (prim != null) {
            return switch (prim) {
                case "bool" -> "(" + slot + " != 0)";
                case "u8", "s8" -> "(byte) " + slot;
                case "u16", "s16" -> "(short) " + slot;
                case "u32", "s32", "char" -> "(int) " + slot;
                case "u64", "s64" -> slot;
                case "f32" -> "Float.intBitsToFloat((int) " + slot + ")";
                case "f64" -> "Double.longBitsToDouble(" + slot + ")";
                case "string" -> "Abi.flatLiftString(" + f + ", " + off + ")";
                default -> throw WitTypes.unsupported(prim);
            };
        }
        Kind kind = types.kind(ref);
        TypeDef def = types.def(ref);
        if (kind instanceof Enum) {
            return types.className(def) + ".values()[(int) " + slot + "]";
        }
        if (kind instanceof Handle || kind instanceof Resource) {
            return fromHandle(ref, "(int) " + slot);
        }
        if (kind instanceof Flags) {
            return types.className(def) + ".fromBits((int) " + slot + ")";
        }
        if (kind instanceof Stream st) {
            return "WitStream.received((WitStreamOps) " + ops + "[" + base + " + " + types.asyncCount(st.element()) + "], " + codecExpr(st.element()) + ", (int) " + slot + ")";
        }
        if (kind instanceof Future fu) {
            return "WitPromise.received((WitFutureOps) " + ops + "[" + base + " + " + types.asyncCount(fu.payload()) + "], " + codecExpr(fu.payload()) + ", (int) " + slot + ")";
        }
        return helper("flatLift", ref) + "(" + f + ", " + off + asyncArgs(ref, ops, base) + ")";
    }

    // Helper class generation

    /**
     * Generates the {@code Abi} class source with helpers for all required types.
     */
    public String generate(String javaPackage, String header) {
        w.addImport("org.graalvm.webimage.api.wit.WitArena");
        w.addImport("org.graalvm.webimage.api.wit.WitElementCodec");
        w.addImport("org.graalvm.webimage.api.wit.WitFutureOps");
        w.addImport("org.graalvm.webimage.api.wit.WitMemory");
        w.addImport("org.graalvm.webimage.api.wit.WitPromise");
        w.addImport("org.graalvm.webimage.api.wit.WitResult");
        w.addImport("org.graalvm.webimage.api.wit.WitStream");
        w.addImport("org.graalvm.webimage.api.wit.WitStreamOps");
        w.line("/**");
        w.line(" * Canonical ABI lowering and lifting helpers. Generated, do not edit.");
        w.line(" */");
        w.line("@SuppressWarnings(\"all\")");
        w.open("final class Abi");
        w.line();
        w.open("private Abi()");
        w.close();
        w.line();
        emitCommonHelpers();
        // Types may request further types while being emitted
        for (int i = 0; i < pending.size(); i++) {
            emitType(pending.get(i));
        }
        emitCodecs();
        for (int i = 0; i < pending.size(); i++) {
            // Codecs may have requested more types
            if (!emittedHelpers.contains(types.helperName(pending.get(i)))) {
                emitType(pending.get(i));
            }
        }
        w.close();
        return w.toSource(javaPackage, header);
    }

    private void emitCommonHelpers() {
        w.open("static void lowerString(String value, int addr)");
        w.line("WitMemory.writeInt(addr, WitArena.lowerString(value));");
        w.line("WitMemory.writeInt(addr + 4, WitArena.utf8Length(value));");
        w.close();
        w.line();
        w.open("static String liftString(int addr)");
        w.line("return WitArena.liftString(WitMemory.readInt(addr), WitMemory.readInt(addr + 4));");
        w.close();
        w.line();
        w.open("static void flatLowerString(String value, long[] f, int off)");
        w.line("f[off] = WitArena.lowerString(value);");
        w.line("f[off + 1] = WitArena.utf8Length(value);");
        w.close();
        w.line();
        w.open("static String flatLiftString(long[] f, int off)");
        w.line("return WitArena.liftString((int) f[off], (int) f[off + 1]);");
        w.close();
        w.line();
        w.open("static void lowerBytes(byte[] value, int addr)");
        w.line("int p = WitArena.alloc(Math.max(value.length, 1), 1);");
        w.line("WitMemory.copyToMemory(value, 0, p, value.length);");
        w.line("WitMemory.writeInt(addr, p);");
        w.line("WitMemory.writeInt(addr + 4, value.length);");
        w.close();
        w.line();
        w.open("static byte[] liftBytes(int addr)");
        w.line("int p = WitMemory.readInt(addr);");
        w.line("int n = WitMemory.readInt(addr + 4);");
        w.line("byte[] result = new byte[n];");
        w.line("WitMemory.copyFromMemory(p, result, 0, n);");
        w.line("WitArena.deferFree(p);");
        w.line("return result;");
        w.close();
        w.line();
    }

    private final Set<String> emittedHelpers = new LinkedHashSet<>();

    private void emitType(TypeRef ref) {
        TypeDef def = types.def(ref);
        String name = types.helperName(ref);
        if (!emittedHelpers.add(name)) {
            return;
        }
        String javaType = types.javaType(ref, false);
        switch (def.kind) {
            case Record r -> emitRecordLike(name, javaType, WitTypes.fieldTypes(r.fields()), fieldAccessors(r.fields()), "new " + javaType + "(");
            case Tuple t -> {
                tupleArities.add(t.types().size());
                List<String> accessors = new ArrayList<>();
                for (int i = 0; i < t.types().size(); i++) {
                    accessors.add("t" + i);
                }
                emitRecordLike(name, javaType, t.types(), accessors, "new " + javaType.substring(0, javaType.indexOf('<')) + "<>(");
            }
            case Variant v -> emitVariant(name, javaType, def, v);
            case Option o -> emitOption(name, javaType, o);
            case Result r -> emitResult(name, javaType, r);
            case ListKind l -> emitList(name, javaType, l);
            default -> throw new IllegalStateException("No helper needed for " + def.kind);
        }
    }

    private static List<String> fieldAccessors(List<Field> fields) {
        List<String> accessors = new ArrayList<>();
        for (Field f : fields) {
            accessors.add(JavaNames.member(f.name()));
        }
        return accessors;
    }

    /** Base index expressions of the fields of a record-like type. */
    private List<String> fieldBases(List<TypeRef> fieldTypes) {
        List<String> bases = new ArrayList<>();
        int n = 0;
        for (TypeRef t : fieldTypes) {
            bases.add("base + " + n);
            n += t == null ? 0 : types.asyncCount(t);
        }
        return bases;
    }

    private void emitRecordLike(String name, String javaType, List<TypeRef> fieldTypes, List<String> accessors, String constructorPrefix) {
        List<Integer> offsets = types.offsets(fieldTypes);
        List<String> bases = fieldBases(fieldTypes);
        String ap = "";
        for (TypeRef t : fieldTypes) {
            if (types.containsAsync(t)) {
                ap = ", Object[] ops, int base";
            }
        }
        w.open("static void lower_" + name + "(" + javaType + " v, int addr" + ap + ")");
        for (int i = 0; i < fieldTypes.size(); i++) {
            w.line(lowerStmt(fieldTypes.get(i), "v." + accessors.get(i) + "()", "addr + " + offsets.get(i), "ops", bases.get(i)));
        }
        w.close();
        w.line();
        w.open("static " + javaType + " lift_" + name + "(int addr" + ap + ")");
        StringBuilder args = new StringBuilder();
        for (int i = 0; i < fieldTypes.size(); i++) {
            if (i > 0) {
                args.append(", ");
            }
            args.append(liftExpr(fieldTypes.get(i), "addr + " + offsets.get(i), "ops", bases.get(i)));
        }
        w.line("return " + constructorPrefix + args + ");");
        w.close();
        w.line();
        w.open("static void flatLower_" + name + "(" + javaType + " v, long[] f, int off" + ap + ")");
        int flatOffset = 0;
        for (int i = 0; i < fieldTypes.size(); i++) {
            w.line(flatLowerStmt(fieldTypes.get(i), "v." + accessors.get(i) + "()", "f", "off + " + flatOffset, "ops", bases.get(i)));
            flatOffset += types.flats(fieldTypes.get(i)).size();
        }
        w.close();
        w.line();
        w.open("static " + javaType + " flatLift_" + name + "(long[] f, int off" + ap + ")");
        args = new StringBuilder();
        flatOffset = 0;
        for (int i = 0; i < fieldTypes.size(); i++) {
            if (i > 0) {
                args.append(", ");
            }
            args.append(flatLiftExpr(fieldTypes.get(i), "f", "off + " + flatOffset, "ops", bases.get(i)));
            flatOffset += types.flats(fieldTypes.get(i)).size();
        }
        w.line("return " + constructorPrefix + args + ");");
        w.close();
        w.line();
    }

    /**
     * A generic variant: cases with an optional payload, a Java expression selecting the case, and
     * constructors.
     */
    private record VariantCase(TypeRef payload, String javaCaseType, String payloadAccessor, String construct) {
    }

    private void emitVariant(String name, String javaType, TypeDef def, Variant v) {
        List<VariantCase> cases = new ArrayList<>();
        String cls = types.className(def);
        for (Case c : v.cases()) {
            String caseType = cls + "." + JavaNames.type(c.name());
            cases.add(new VariantCase(c.type(), caseType, "value()", c.type() == null ? "new " + caseType + "()" : "new " + caseType + "(%s)"));
        }
        emitVariantLike(name, javaType, cases, "v instanceof " + cls + "." + "%s", true);
    }

    private void emitOption(String name, String javaType, Option o) {
        List<VariantCase> cases = List.of(
                        new VariantCase(null, null, null, "java.util.Optional.empty()"),
                        new VariantCase(o.type(), null, "get()", "java.util.Optional.of(%s)"));
        emitVariantLike(name, javaType, cases, null, false);
    }

    private void emitResult(String name, String javaType, Result r) {
        List<VariantCase> cases = List.of(
                        new VariantCase(r.ok(), null, "ok()", r.ok() == null ? "WitResult.ok(null)" : "WitResult.ok(%s)"),
                        new VariantCase(r.err(), null, "err()", r.err() == null ? "WitResult.err(null)" : "WitResult.err(%s)"));
        emitVariantLike(name, javaType, cases, null, false);
    }

    /**
     * @param isSealed true for WIT variants (pattern matching on case classes), false for option
     *            (isPresent) and result (isOk)
     */
    private void emitVariantLike(String name, String javaType, List<VariantCase> cases, String instanceOfPattern, boolean isSealed) {
        List<TypeRef> payloads = new ArrayList<>();
        for (VariantCase c : cases) {
            payloads.add(c.payload());
        }
        int disc = cases.size();
        int payloadOffset = types.payloadOffset(payloads);
        List<Flat> joined = types.variantFlats(payloads);
        List<String> bases = fieldBases(payloads);
        String ap = "";
        for (TypeRef t : payloads) {
            if (t != null && types.containsAsync(t)) {
                ap = ", Object[] ops, int base";
            }
        }

        // Memory lowering
        w.open("static void lower_" + name + "(" + javaType + " v, int addr" + ap + ")");
        emitCaseDispatch(cases, instanceOfPattern, isSealed, (i, c, valueExpr) -> {
            w.line(writeDiscriminant(disc, String.valueOf(i), "addr"));
            if (c.payload() != null) {
                w.line(lowerStmt(c.payload(), valueExpr, "addr + " + payloadOffset, "ops", bases.get(i)));
            }
        });
        w.close();
        w.line();

        // Memory lifting
        w.open("static " + javaType + " lift_" + name + "(int addr" + ap + ")");
        w.open("switch (" + readDiscriminant(disc, "addr") + ")");
        for (int i = 0; i < cases.size(); i++) {
            VariantCase c = cases.get(i);
            String value = c.payload() == null ? "" : liftExpr(c.payload(), "addr + " + payloadOffset, "ops", bases.get(i));
            w.line("case " + i + ": return " + c.construct().replace("%s", value) + ";");
        }
        w.line("default: throw new IllegalStateException(\"Invalid discriminant\");");
        w.close();
        w.close();
        w.line();

        // Flat lowering
        w.open("static void flatLower_" + name + "(" + javaType + " v, long[] f, int off" + ap + ")");
        w.line("java.util.Arrays.fill(f, off, off + " + joined.size() + ", 0L);");
        emitCaseDispatch(cases, instanceOfPattern, isSealed, (i, c, valueExpr) -> {
            w.line("f[off] = " + i + ";");
            if (c.payload() != null) {
                List<Flat> caseFlats = types.flats(c.payload());
                w.line("long[] cf = new long[" + caseFlats.size() + "];");
                w.line(flatLowerStmt(c.payload(), valueExpr, "cf", "0", "ops", bases.get(i)));
                for (int k = 0; k < caseFlats.size(); k++) {
                    Flat have = caseFlats.get(k);
                    Flat want = joined.get(k + 1);
                    String conversion = (have == Flat.I32 && want == Flat.I64) ? "cf[" + k + "] & 0xFFFFFFFFL" : "cf[" + k + "]";
                    w.line("f[off + " + (k + 1) + "] = " + conversion + ";");
                }
            }
        });
        w.close();
        w.line();

        // Flat lifting
        w.open("static " + javaType + " flatLift_" + name + "(long[] f, int off" + ap + ")");
        w.open("switch ((int) f[off])");
        for (int i = 0; i < cases.size(); i++) {
            VariantCase c = cases.get(i);
            String value = c.payload() == null ? "" : flatLiftExpr(c.payload(), "f", "off + 1", "ops", bases.get(i));
            w.line("case " + i + ": return " + c.construct().replace("%s", value) + ";");
        }
        w.line("default: throw new IllegalStateException(\"Invalid discriminant\");");
        w.close();
        w.close();
        w.line();
    }

    private interface CaseBody {
        void emit(int index, VariantCase c, String valueExpr);
    }

    private void emitCaseDispatch(List<VariantCase> cases, String instanceOfPattern, boolean isSealed, CaseBody body) {
        if (isSealed) {
            for (int i = 0; i < cases.size(); i++) {
                VariantCase c = cases.get(i);
                String simpleCase = c.javaCaseType().substring(c.javaCaseType().lastIndexOf('.') + 1);
                String test = instanceOfPattern.replace("%s", simpleCase + " c" + i);
                w.open((i == 0 ? "if (" : "} else if (") + test + ")");
                body.emit(i, c, "c" + i + "." + c.payloadAccessor());
                w.closeNoBrace();
            }
            w.open("} else");
            w.line("throw new IllegalStateException(\"Unknown variant case: \" + v);");
            w.close();
        } else {
            // option (isPresent) or result (isOk)
            boolean isOption = cases.get(0).construct().contains("Optional");
            String condition = isOption ? "v.isPresent()" : "v.isOk()";
            int thenCase = isOption ? 1 : 0;
            int elseCase = isOption ? 0 : 1;
            w.open("if (" + condition + ")");
            body.emit(thenCase, cases.get(thenCase), "v." + cases.get(thenCase).payloadAccessor());
            w.closeNoBrace();
            w.open("} else");
            VariantCase ec = cases.get(elseCase);
            body.emit(elseCase, ec, ec.payloadAccessor() == null ? null : "v." + ec.payloadAccessor());
            w.close();
        }
    }

    private void emitList(String name, String javaType, ListKind l) {
        if (types.isByteList(l)) {
            w.open("static void lower_" + name + "(byte[] v, int addr)");
            w.line("lowerBytes(v, addr);");
            w.close();
            w.line();
            w.open("static byte[] lift_" + name + "(int addr)");
            w.line("return liftBytes(addr);");
            w.close();
            w.line();
        } else {
            TypeRef elem = l.element();
            int size = types.size(elem);
            int align = types.align(elem);
            String ap = asyncParams(elem);
            w.open("static void lower_" + name + "(" + javaType + " v, int addr" + ap + ")");
            w.line("int n = v.size();");
            w.line("int p = WitArena.alloc(Math.max(n * " + size + ", 1), " + align + ");");
            w.open("for (int i = 0; i < n; i++)");
            w.line(lowerStmt(elem, "v.get(i)", "p + i * " + size, "ops", "base"));
            w.close();
            w.line("WitMemory.writeInt(addr, p);");
            w.line("WitMemory.writeInt(addr + 4, n);");
            w.close();
            w.line();
            w.open("static " + javaType + " lift_" + name + "(int addr" + ap + ")");
            w.line("int p = WitMemory.readInt(addr);");
            w.line("int n = WitMemory.readInt(addr + 4);");
            w.line(javaType + " result = new java.util.ArrayList<>(n);");
            w.open("for (int i = 0; i < n; i++)");
            w.line("result.add(" + liftExpr(elem, "p + i * " + size, "ops", "base") + ");");
            w.close();
            w.line("WitArena.deferFree(p);");
            w.line("return result;");
            w.close();
            w.line();
        }
        // Flat form: pointer and length
        String ap = asyncParams(l.element());
        String aa = types.containsAsync(l.element()) ? ", ops, base" : "";
        w.open("static void flatLower_" + name + "(" + javaType + " v, long[] f, int off" + ap + ")");
        w.line("int tmp = WitArena.alloc(8, 4);");
        w.line("lower_" + name + "(v, tmp" + aa + ");");
        w.line("f[off] = WitMemory.readInt(tmp);");
        w.line("f[off + 1] = WitMemory.readInt(tmp + 4);");
        w.close();
        w.line();
        w.open("static " + javaType + " flatLift_" + name + "(long[] f, int off" + ap + ")");
        w.line("int tmp = WitArena.alloc(8, 4);");
        w.line("WitMemory.writeInt(tmp, (int) f[off]);");
        w.line("WitMemory.writeInt(tmp + 4, (int) f[off + 1]);");
        w.line("return lift_" + name + "(tmp" + aa + ");");
        w.close();
        w.line();
    }
}
