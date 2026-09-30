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

    /** The compiler records converted positions; native Java values cross unchecked. */
    public static void checkArgument(Object value, String path, String shape) {
        if (shape.equals("U")) return;
        if (value == null) throw new IllegalArgumentException("Null Java boundary argument: " + path);
        if (shape.charAt(0) == 'L' && !shape.substring(1).equals("U")) {
            int index = 0;
            for (Object element : (java.util.Collection<?>) value) {
                checkArgument(element, path + "[" + index++ + "]", shape.substring(1));
            }
        } else if (shape.charAt(0) == 'O' && !shape.substring(1).equals("U")) {
            ((java.util.Optional<?>) value).ifPresent(element -> checkArgument(element, path + ".value", shape.substring(1)));
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
