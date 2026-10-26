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
package com.oracle.svm.webimage.wasi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.oracle.svm.webimage.wasi.WasiFileSystem.Resolved;
import com.oracle.svm.webimage.wasi.WasiFileSystem.Stat;

/**
 * Implements the {@link java.nio.file.spi.FileSystemProvider} operations for paths that are
 * covered by a WASI preopen. The provider wrapper decides per path whether to call these methods
 * or the in-memory file system.
 */
public final class WasiNioSupport {

    private WasiNioSupport() {
    }

    /**
     * The absolute, normalized string form of a path.
     */
    public static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * @return the resolved WASI location or {@code null} if the path is not under a preopen.
     */
    public static Resolved resolve(Path path) {
        return WasiFileSystem.resolve(key(path));
    }

    public static boolean isWasiPath(Path path) {
        return resolve(path) != null;
    }

    private static boolean followLinks(LinkOption... options) {
        for (LinkOption option : options) {
            if (option == LinkOption.NOFOLLOW_LINKS) {
                return false;
            }
        }
        return true;
    }

    public static SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, @SuppressWarnings("unused") FileAttribute<?>... attrs) throws IOException {
        String display = key(path);
        Resolved resolved = resolve(path);
        boolean append = options.contains(StandardOpenOption.APPEND);
        boolean write = options.contains(StandardOpenOption.WRITE) || append;
        boolean read = options.contains(StandardOpenOption.READ) || !write;
        int openFlags = 0;
        if (options.contains(StandardOpenOption.CREATE_NEW)) {
            openFlags |= WasiFsImports.OPEN_FLAG_CREATE | WasiFsImports.OPEN_FLAG_EXCLUSIVE;
        } else if (options.contains(StandardOpenOption.CREATE) && write) {
            openFlags |= WasiFsImports.OPEN_FLAG_CREATE;
        }
        if (options.contains(StandardOpenOption.TRUNCATE_EXISTING) && write) {
            openFlags |= WasiFsImports.OPEN_FLAG_TRUNCATE;
        }
        int descriptorFlags = (read ? WasiFsImports.DESCRIPTOR_FLAG_READ : 0) | (write ? WasiFsImports.DESCRIPTOR_FLAG_WRITE : 0);
        boolean follow = !options.contains(LinkOption.NOFOLLOW_LINKS);
        int descriptor = WasiFileSystem.open(resolved, openFlags, descriptorFlags, follow, display);
        return new WasiFileChannel(descriptor, display, read, write, append);
    }

    public static DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        String display = key(dir);
        Resolved resolved = resolve(dir);
        List<String> names = WasiFileSystem.readDirectory(resolved, display);
        List<Path> entries = new ArrayList<>(names.size());
        for (String name : names) {
            Path entry = dir.resolve(name);
            if (filter == null || filter.accept(entry)) {
                entries.add(entry);
            }
        }
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return entries.iterator();
            }

            @Override
            public void close() {
            }
        };
    }

    public static void createDirectory(Path dir, @SuppressWarnings("unused") FileAttribute<?>... attrs) throws IOException {
        WasiFileSystem.createDirectory(resolve(dir), key(dir));
    }

    public static void delete(Path path) throws IOException {
        String display = key(path);
        Resolved resolved = resolve(path);
        Stat stat = WasiFileSystem.stat(resolved, false, display);
        if (stat.isDirectory()) {
            WasiFileSystem.removeDirectory(resolved, display);
        } else {
            WasiFileSystem.unlinkFile(resolved, display);
        }
    }

    private static boolean hasOption(CopyOption[] options, CopyOption wanted) {
        for (CopyOption option : options) {
            if (option == wanted) {
                return true;
            }
        }
        return false;
    }

    /**
     * Copies a file by streaming its content (either side may be an in-memory path).
     */
    public static void copy(Path source, Path target, CopyOption... options) throws IOException {
        boolean replace = hasOption(options, StandardCopyOption.REPLACE_EXISTING);
        if (Files.exists(target)) {
            if (!replace) {
                throw new FileAlreadyExistsException(target.toString());
            }
            if (Files.isDirectory(target)) {
                Files.delete(target);
            }
        }
        if (Files.isDirectory(source)) {
            Files.createDirectory(target);
            return;
        }
        try (SeekableByteChannel in = Files.newByteChannel(source, Set.of(StandardOpenOption.READ));
                        SeekableByteChannel out = Files.newByteChannel(target, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (in.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    out.write(buffer);
                }
                buffer.clear();
            }
        }
    }

    public static void move(Path source, Path target, CopyOption... options) throws IOException {
        Resolved from = resolve(source);
        Resolved to = resolve(target);
        if (from != null && to != null) {
            if (Files.exists(target) && !hasOption(options, StandardCopyOption.REPLACE_EXISTING)) {
                throw new FileAlreadyExistsException(target.toString());
            }
            WasiFileSystem.rename(from, to, key(source));
            return;
        }
        copy(source, target, options);
        Files.delete(source);
    }

    public static boolean isSameFile(Path path, Path path2) {
        return key(path).equals(key(path2));
    }

    public static boolean isHidden(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    public static void checkAccess(Path path, AccessMode... modes) throws IOException {
        String display = key(path);
        Stat stat = WasiFileSystem.stat(resolve(path), true, display);
        for (AccessMode mode : modes) {
            if (mode == AccessMode.EXECUTE && !stat.isDirectory()) {
                throw new AccessDeniedException(display);
            }
        }
    }

    /**
     * Basic attributes backed by {@code descriptor-stat}.
     */
    public static final class WasiBasicFileAttributes implements BasicFileAttributes {
        private final Stat stat;
        private final String key;

        WasiBasicFileAttributes(Stat stat, String key) {
            this.stat = stat;
            this.key = key;
        }

        @Override
        public FileTime lastModifiedTime() {
            return stat.modificationTime();
        }

        @Override
        public FileTime lastAccessTime() {
            return stat.accessTime();
        }

        @Override
        public FileTime creationTime() {
            return stat.changeTime();
        }

        @Override
        public boolean isRegularFile() {
            return stat.isRegularFile();
        }

        @Override
        public boolean isDirectory() {
            return stat.isDirectory();
        }

        @Override
        public boolean isSymbolicLink() {
            return stat.isSymbolicLink();
        }

        @Override
        public boolean isOther() {
            return !isRegularFile() && !isDirectory() && !isSymbolicLink();
        }

        @Override
        public long size() {
            return stat.size();
        }

        @Override
        public Object fileKey() {
            return key;
        }
    }

    public static WasiBasicFileAttributes readBasicAttributes(Path path, LinkOption... options) throws IOException {
        String display = key(path);
        return new WasiBasicFileAttributes(WasiFileSystem.stat(resolve(path), followLinks(options), display), display);
    }

    @SuppressWarnings("unchecked")
    public static <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException {
        if (type == BasicFileAttributes.class) {
            return (A) readBasicAttributes(path, options);
        }
        throw new UnsupportedOperationException("Attributes of type " + type.getName() + " are not supported on WASI");
    }

    public static Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        String view = "basic";
        String names = attributes;
        int colon = attributes.indexOf(':');
        if (colon >= 0) {
            view = attributes.substring(0, colon);
            names = attributes.substring(colon + 1);
        }
        if (!view.equals("basic")) {
            throw new UnsupportedOperationException("Attribute view '" + view + "' is not supported on WASI");
        }
        WasiBasicFileAttributes attrs = readBasicAttributes(path, options);
        Map<String, Object> all = new HashMap<>();
        all.put("lastModifiedTime", attrs.lastModifiedTime());
        all.put("lastAccessTime", attrs.lastAccessTime());
        all.put("creationTime", attrs.creationTime());
        all.put("size", attrs.size());
        all.put("isRegularFile", attrs.isRegularFile());
        all.put("isDirectory", attrs.isDirectory());
        all.put("isSymbolicLink", attrs.isSymbolicLink());
        all.put("isOther", attrs.isOther());
        all.put("fileKey", attrs.fileKey());
        if (names.equals("*")) {
            return all;
        }
        Map<String, Object> result = new HashMap<>();
        for (String name : names.split(",")) {
            if (all.containsKey(name)) {
                result.put(name, all.get(name));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        if (type == BasicFileAttributeView.class) {
            return (V) new BasicFileAttributeView() {
                @Override
                public String name() {
                    return "basic";
                }

                @Override
                public BasicFileAttributes readAttributes() throws IOException {
                    return readBasicAttributes(path, options);
                }

                @Override
                public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
                    // Timestamps cannot be changed yet, but the file must exist
                    readBasicAttributes(path, options);
                }
            };
        }
        return null;
    }

    public static void setAttribute(Path path, String attribute, @SuppressWarnings("unused") Object value, @SuppressWarnings("unused") LinkOption... options) throws IOException {
        if (!Files.exists(path)) {
            throw new NoSuchFileException(key(path));
        }
        throw new UnsupportedOperationException("Setting attribute '" + attribute + "' is not supported on WASI");
    }
}
