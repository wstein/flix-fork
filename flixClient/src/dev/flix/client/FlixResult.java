/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.flix.client;

import java.util.List;

/** The outcome of a compilation, and everything the compiler had to say about it. */
public record FlixResult(boolean success, List<FlixDiagnostic> diagnostics) {
}
