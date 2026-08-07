/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.flix.client;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * How a command is actually run.
 *
 * <p>This exists so that reading the contract and starting a process are separable. A build tool
 * has its own process machinery — Gradle's {@code ExecOperations}, Mill's {@code os.proc} — and it
 * is not interchangeable with {@link ProcessBuilder}: it is what routes output into the build log,
 * and what a build tool's own testing and sandboxing hook into. Without this seam a plugin has to
 * choose between using the client and using its own launcher, and the one that keeps its launcher
 * keeps a copy of the parser too, which is the duplication the client exists to remove.
 *
 * <p>Only stdout is returned. Progress and prompts go to stderr, which an implementation is
 * expected to inherit into the caller's log rather than capture — the contract document is stdout
 * alone, and mixing the two makes it unparseable.
 */
@FunctionalInterface
public interface FlixProcessRunner {

    /**
     * Runs {@code command} in {@code workingDirectory} and returns its status and stdout.
     *
     * @param workingDirectory the directory to run in, or {@code null} for the caller's own
     */
    Result run(List<String> command, Path workingDirectory);

    /** What a run produced: its exit status, and everything it wrote to stdout. */
    record Result(int status, String stdout) {
    }

    /** Runs commands with {@link ProcessBuilder}, inheriting stderr. */
    FlixProcessRunner DEFAULT = (command, workingDirectory) -> {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = builder.start();
            // Read before waiting: a compiler that fills the pipe buffer blocks writing while this
            // blocks waiting, and neither side ever moves.
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Result(process.waitFor(), stdout);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlixClientException("Interrupted while running the Flix compiler.");
        }
    };
}
