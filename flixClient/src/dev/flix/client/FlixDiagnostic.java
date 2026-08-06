/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.flix.client;

/**
 * One problem the compiler reported.
 *
 * <p>{@code code} is the stable identifier — {@code E2136} — and {@code kind} is the category,
 * {@code "Resolution Error"}. The category reads well in a problem list and is useless to key on,
 * since hundreds of distinct errors share it.
 *
 * <p>Positions are the compiler's, and therefore <b>zero-based</b>: they follow LSP, which is what
 * BSP's {@code build/publishDiagnostics} carries, so they pass through untranslated to anything
 * downstream that speaks BSP. {@link #render()} is the one place they become one-based, for people.
 */
public record FlixDiagnostic(
        String path,
        int line,
        int character,
        String code,
        String kind,
        String message,
        String fullMessage) {

    /** {@code file:line:column: CODE: message} — the form editors and CI logs linkify. */
    public String render() {
        String label = code == null ? "error" : code;
        if (path == null) {
            return label + ": " + message;
        }
        return path + ":" + (line + 1) + ":" + (character + 1) + ": " + label + ": " + message;
    }
}
