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

    /** Reject null before conversion, including nested JDK container elements. */
    public static void checkArgument(Object value, String parameter) {
        checkArgument(value, parameter, new java.util.IdentityHashMap<Object, Boolean>());
    }

    private static void checkArgument(Object value, String path,
                                      java.util.IdentityHashMap<Object, Boolean> visited) {
        if (value == null) {
            throw new IllegalArgumentException("Null Java boundary argument: " + path);
        }
        // Java containers may be cyclic. Inspect each object once without invoking equals/hashCode.
        if (visited.put(value, Boolean.TRUE) != null) return;
        if (value instanceof java.util.Map<?, ?>) {
            int index = 0;
            for (java.util.Map.Entry<?, ?> entry : ((java.util.Map<?, ?>) value).entrySet()) {
                checkArgument(entry.getKey(), path + "[" + index + "].key", visited);
                checkArgument(entry.getValue(), path + "[" + index + "].value", visited);
                index++;
            }
        } else if (value instanceof java.util.Collection<?>) {
            int index = 0;
            for (Object element : (java.util.Collection<?>) value) {
                checkArgument(element, path + "[" + index++ + "]", visited);
            }
        } else if (value instanceof java.util.Optional<?>) {
            ((java.util.Optional<?>) value).ifPresent(element -> checkArgument(element, path + ".value", visited));
        }
    }

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
