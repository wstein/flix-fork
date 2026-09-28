/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.flix.client;

import java.util.Map;

/**
 * What a compiler says it can do, and whether it will serve this client at all.
 *
 * <p>Capabilities are named rather than inferred from the version, because they do not arrive in
 * lockstep, and a compiler advertises one only once it is implemented — advertising ahead is worse
 * than omitting, since a caller trusts it and fails at the point of use.
 */
public record FlixCapabilities(
        boolean served,
        int contractVersion,
        int minimumClientVersion,
        String flixVersion,
        String inputModel,
        Map<String, Boolean> capabilities,
        String error) {

    /** Returns whether the compiler offers {@code name}. Absent means no. */
    public boolean has(String name) {
        return capabilities != null && Boolean.TRUE.equals(capabilities.get(name));
    }
}
