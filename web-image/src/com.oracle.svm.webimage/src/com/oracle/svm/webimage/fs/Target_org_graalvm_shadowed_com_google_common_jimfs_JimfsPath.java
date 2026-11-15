/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.webimage.fs;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

import com.oracle.svm.shared.util.SubstrateUtil;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Paths of the default file system are Jimfs paths. Their URIs would use the {@code jimfs} scheme,
 * which no code outside of Web Image knows: the default file system's provider has the
 * {@code file} scheme, and libraries that load configuration or resources from a path's URI only
 * accept the usual schemes. {@link WebImageNIOFileSystemProvider} maps {@code file} URIs back to
 * the Jimfs file system.
 */
@TargetClass(className = "org.graalvm.shadowed.com.google.common.jimfs.JimfsPath")
final class Target_org_graalvm_shadowed_com_google_common_jimfs_JimfsPath {

    @Substitute
    public URI toUri() {
        Path absolute = SubstrateUtil.cast(this, Path.class).toAbsolutePath();
        try {
            return new URI("file", null, absolute.toString(), null);
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }
}

/**
 * The asynchronous file channel of Jimfs is built on parts of Guava that are not in the shaded
 * copy, and there is no asynchronous file I/O to map it to.
 */
@TargetClass(className = "org.graalvm.shadowed.com.google.common.jimfs.JimfsFileSystemProvider")
final class Target_org_graalvm_shadowed_com_google_common_jimfs_JimfsFileSystemProvider {

    @Substitute
    @SuppressWarnings({"static-method", "unused"})
    public java.nio.channels.AsynchronousFileChannel newAsynchronousFileChannel(Path path, java.util.Set<? extends java.nio.file.OpenOption> options, java.util.concurrent.ExecutorService executor,
                    java.nio.file.attribute.FileAttribute<?>... attrs) throws java.io.IOException {
        throw new UnsupportedOperationException("Asynchronous file channels are not supported");
    }
}
