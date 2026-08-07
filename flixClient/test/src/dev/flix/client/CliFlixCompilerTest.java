/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.flix.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the tooling contract the way a build tool does.
 *
 * <p>These are the assertions the Gradle and Mill plugins used to each make against their own copy
 * of a reader. They are here because there is now one reader; if they had been dropped with the
 * copies, the client would be untested code replacing tested code.
 */
class CliFlixCompilerTest {

    /** A runner that answers with a fixed document and never starts a process. */
    private record Canned(int status, String stdout, List<List<String>> seen) implements FlixProcessRunner {
        Canned(int status, String stdout) {
            this(status, stdout, new ArrayList<>());
        }

        @Override
        public Result run(List<String> command, Path workingDirectory) {
            seen.add(command);
            return new Result(status, stdout);
        }
    }

    private static CliFlixCompiler compilerReturning(int status, String stdout) {
        return new CliFlixCompiler(Path.of("java"), Path.of("flix.jar"), new Canned(status, stdout));
    }

    private static final String ONE_ERROR = """
            {
              "protocolVersion": 1,
              "flixVersion": "0.75.1",
              "success": false,
              "diagnostics": [{
                "path": "/w/src/Acme/Api.flix",
                "range": { "start": { "line": 2, "character": 52 }, "end": { "line": 2, "character": 55 } },
                "severity": 1,
                "code": "E2136",
                "kind": "Resolution Error",
                "message": "Undefined name: 'nam'.",
                "fullMessage": "-- Resolution Error [E2136] --"
              }]
            }""";

    @Test
    void readsLocationCodeAndMessage() {
        FlixResult result = compilerReturning(1, ONE_ERROR).check(Path.of("/w"), List.of());

        assertFalse(result.success());
        assertEquals(1, result.diagnostics().size());
        FlixDiagnostic diagnostic = result.diagnostics().get(0);
        assertEquals("/w/src/Acme/Api.flix", diagnostic.path());
        assertEquals("E2136", diagnostic.code());
        assertEquals("Resolution Error", diagnostic.kind());
        assertEquals("Undefined name: 'nam'.", diagnostic.message());
    }

    @Test
    void rendersPositionsTheWayTheCompilerPrintsThem() {
        // The contract's positions are LSP's and so zero-based, while the compiler's own output and
        // every editor's "go to line" are one-based. Getting this backwards puts a marker one line
        // and one column off every time, which reads as a rounding error rather than as a bug.
        FlixDiagnostic diagnostic = compilerReturning(1, ONE_ERROR)
                .check(Path.of("/w"), List.of())
                .diagnostics()
                .get(0);

        assertEquals("/w/src/Acme/Api.flix:3:53: E2136: Undefined name: 'nam'.", diagnostic.render());
    }

    @Test
    void aSuccessfulBuildReportsNoDiagnosticsRatherThanNoDocument() {
        // An empty list and an unreadable document must not look alike: one is a build that passed,
        // the other is a compiler that could not be understood.
        FlixResult result = compilerReturning(0, """
                {"protocolVersion": 1, "success": true, "diagnostics": []}""")
                .build(Path.of("/w"), List.of());

        assertTrue(result.success());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void outputThatIsNotThisContractIsRefused() {
        // Each of these would otherwise read as "no diagnostics" and let a failing build pass: a
        // compiler too old to know the flag, one that crashed, and JSON that is something else.
        for (String stdout : List.of("", "Exception in thread \"main\"", "{\"diagnostics\": []}")) {
            assertThrows(
                    FlixClientException.class,
                    () -> compilerReturning(0, stdout).check(Path.of("/w"), List.of()),
                    () -> "should have refused: " + stdout);
        }
    }

    @Test
    void aDiagnosticWithNoLocationIsStillReported() {
        // A malformed manifest or an unreachable dependency has no source location. Dropping it
        // would leave a build failing with nothing said about why.
        FlixDiagnostic diagnostic = compilerReturning(1, """
                {"protocolVersion": 1, "success": false, "diagnostics": [
                  {"path": null, "code": null, "message": "Cannot read flix.toml"}]}""")
                .check(Path.of("/w"), List.of())
                .diagnostics()
                .get(0);

        assertEquals(null, diagnostic.path());
        assertEquals("error: Cannot read flix.toml", diagnostic.render());
    }

    @Test
    void librariesArePassedAsRepeatedLibFlags() {
        // Joint compilation depends on this: Flix reads the project's Java classes and the facade
        // stubs from `--lib`, and a dropped one fails as an unresolved type rather than as a
        // missing argument.
        Canned runner = new Canned(0, """
                {"protocolVersion": 1, "success": true, "diagnostics": []}""");
        new CliFlixCompiler(Path.of("java"), Path.of("flix.jar"), runner)
                .build(Path.of("/w"), List.of(Path.of("/w/java.jar"), Path.of("/w/stubs.jar")));

        List<String> command = runner.seen().get(0);
        assertEquals(
                List.of("java", "-jar", "flix.jar", "build",
                        "--lib", "/w/java.jar", "--lib", "/w/stubs.jar", "--diagnostics-json"),
                command);
    }

    @Test
    void capabilitiesAreNamedRatherThanInferredFromTheVersion() {
        FlixCapabilities capabilities = compilerReturning(0, """
                {"protocolVersion": 1, "minimumClientVersion": 1, "flixVersion": "0.75.1",
                 "inputModel": "project-directory",
                 "capabilities": {"stubs": true, "daemon": false}}""")
                .capabilities();

        assertTrue(capabilities.served());
        assertEquals(1, capabilities.contractVersion());
        assertEquals("project-directory", capabilities.inputModel());
        assertTrue(capabilities.has("stubs"));
        assertFalse(capabilities.has("daemon"));
        // Absent means no, so a caller need not distinguish "not offered" from "not known".
        assertFalse(capabilities.has("telepathy"));
    }

    @Test
    void aRefusedClientIsReportedRatherThanThrown() {
        // A compiler that will not serve this client is answering the question that was asked, so
        // it is a result. Throwing would make version negotiation indistinguishable from a crash.
        FlixCapabilities capabilities = compilerReturning(1, """
                {"protocolVersion": 2, "minimumClientVersion": 2,
                 "error": "This compiler requires a client speaking contract version 2 or later."}""")
                .capabilities();

        assertFalse(capabilities.served());
        assertEquals(2, capabilities.minimumClientVersion());
        assertTrue(capabilities.error().contains("version 2"));
    }

    @Test
    void stubGenerationReportsItsOutcomeAndStartsNoParse() {
        // `stubs` writes no document, so a reader that insisted on one would fail every successful
        // run. The exit status is the whole result.
        Canned runner = new Canned(0, "");
        FlixResult result = new CliFlixCompiler(Path.of("java"), Path.of("flix.jar"), runner)
                .stubs(Path.of("/w"), Path.of("/w/build/stubs"));

        assertTrue(result.success());
        assertTrue(result.diagnostics().isEmpty());
        assertEquals(
                List.of("java", "-jar", "flix.jar", "stubs", "--out", "/w/build/stubs"),
                runner.seen().get(0));
    }
}
