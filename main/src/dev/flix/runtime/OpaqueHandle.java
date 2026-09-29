/*
 * Copyright 2026 Werner Stein
 *
 * Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE.md file.
 */
package dev.flix.runtime;

import java.util.Objects;

/** An identity-based, non-serializable capability to retain and return a Flix value. */
public final class OpaqueHandle<T> {
    private final String typeKey;
    private final String typeName;
    private final Object payload;

    private OpaqueHandle(String typeKey, String typeName, Object payload) {
        this.typeKey = Objects.requireNonNull(typeKey);
        this.typeName = Objects.requireNonNull(typeName);
        this.payload = Objects.requireNonNull(payload);
    }

    static OpaqueHandle<Object> wrap(String typeKey, String typeName, Object payload) {
        return new OpaqueHandle<>(typeKey, typeName, payload);
    }

    Object unwrap(String expectedKey, String expectedName) {
        if (!typeKey.equals(expectedKey)) {
            throw new IllegalArgumentException("Opaque handle type mismatch: expected " + expectedName + ", actual " + typeName);
        }
        return payload;
    }

    @Override
    public String toString() {
        return "OpaqueHandle[" + typeName + "]";
    }
    // Object.equals/hashCode intentionally retain identity semantics. T is reserved for phase 2 markers.
}
