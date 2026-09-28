/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.flix.client;

/** Thrown when the compiler could not be driven at all, as distinct from reporting errors. */
public class FlixClientException extends RuntimeException {
    public FlixClientException(String message) {
        super(message);
    }
}
