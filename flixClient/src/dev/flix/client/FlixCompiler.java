/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.flix.client;

import java.nio.file.Path;
import java.util.List;

/**
 * A Flix compiler a build tool can drive.
 *
 * <p>This is the narrow client surface, and narrow is a discipline rather than a size: it names
 * capabilities, compilation, and stub generation, and nothing else. The moment it exposes a
 * {@code Flix} instance, a {@code Bootstrap}, or an AST it becomes a second compiler API and
 * inherits every reason a build plugin should not link against the compiler — chiefly that it would
 * then be pinned to a binary version, the problem {@code zinc} solves by building a
 * {@code compiler-bridge} per Scala version.
 *
 * <p>Implementations are transports. {@link CliFlixCompiler} spawns the compiler per call; a warm
 * daemon would be another, behind these same types, so a build plugin need not change to gain one.
 *
 * <p>Deliberately Java with no Scala on its classpath. A Scala client would drag the Scala runtime
 * into every consumer's plugin classpath, which is the reason Kotlin's {@code kotlin-daemon-client}
 * is not written in Kotlin's own compiler stack either.
 */
public interface FlixCompiler {

    /** The contract version this client speaks. Sent to the compiler so it can refuse a mismatch. */
    int CONTRACT_VERSION = 1;

    /**
     * Asks what the compiler offers, and whether it will serve this client.
     *
     * <p>Worth calling before anything else. The alternative is discovering a mismatch as a missing
     * field midway through a build, and reporting it as a compiler error rather than as a version
     * problem.
     */
    FlixCapabilities capabilities();

    /** Type checks the project, without generating code. */
    FlixResult check(Path projectDirectory, List<Path> libraries);

    /** Compiles the project to class files. */
    FlixResult build(Path projectDirectory, List<Path> libraries);

    /**
     * Writes compile-only Java stubs for the project's {@code @Export}ed defs.
     *
     * <p>Returns the defs it could not describe, as diagnostics. It refuses rather than guesses: a
     * missing stub is a build error, while a wrong one compiles and becomes a linkage error in a
     * caller that did nothing wrong.
     */
    FlixResult stubs(Path projectDirectory, Path destination);
}
