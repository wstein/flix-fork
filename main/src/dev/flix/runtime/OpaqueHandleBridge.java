/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package dev.flix.runtime;

/** Compiler-internal bridge, not a supported Java API or a security boundary. */
public final class OpaqueHandleBridge {
    private OpaqueHandleBridge() { }

    public static OpaqueHandle<Object> wrap(String typeKey, String typeName, Object payload) {
        return OpaqueHandle.wrap(typeKey, typeName, payload);
    }

    public static Object unwrap(String expectedKey, String expectedName, OpaqueHandle<Object> handle) {
        if (handle == null) {
            throw new IllegalArgumentException("Opaque handle type mismatch: expected " + expectedName + ", actual null");
        }
        return handle.unwrap(expectedKey, expectedName);
    }
}
