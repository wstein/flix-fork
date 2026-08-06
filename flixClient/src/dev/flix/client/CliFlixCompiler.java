/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package dev.flix.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Drives a Flix compiler jar as a subprocess.
 *
 * <p>Only stdout carries the contract document; progress and prompts go to stderr and are inherited
 * into the caller's log. Output that is not a document is a failure rather than an absence of
 * diagnostics — it means a compiler too old to know the flag, or a crash, and reading either as
 * "nothing to report" would let a broken build pass.
 */
public final class CliFlixCompiler implements FlixCompiler {

    private final Path javaExecutable;
    private final Path compilerJar;

    public CliFlixCompiler(Path javaExecutable, Path compilerJar) {
        this.javaExecutable = javaExecutable;
        this.compilerJar = compilerJar;
    }

    /** Uses the JVM running this code. */
    public CliFlixCompiler(Path compilerJar) {
        this(Path.of(System.getProperty("java.home"), "bin", "java"), compilerJar);
    }

    @Override
    public FlixCapabilities capabilities() {
        Invocation invocation = run(null, List.of("capabilities", "--contract-version", String.valueOf(CONTRACT_VERSION)));
        JsonObject document = document(invocation, "capabilities");
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        if (document.has("capabilities") && document.get("capabilities").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : document.getAsJsonObject("capabilities").entrySet()) {
                capabilities.put(entry.getKey(), entry.getValue().getAsBoolean());
            }
        }
        return new FlixCapabilities(
                invocation.status == 0,
                integer(document, "protocolVersion"),
                integer(document, "minimumClientVersion"),
                string(document, "flixVersion"),
                string(document, "inputModel"),
                capabilities,
                string(document, "error"));
    }

    @Override
    public FlixResult check(Path projectDirectory, List<Path> libraries) {
        return compile(projectDirectory, "check", libraries);
    }

    @Override
    public FlixResult build(Path projectDirectory, List<Path> libraries) {
        return compile(projectDirectory, "build", libraries);
    }

    @Override
    public FlixResult stubs(Path projectDirectory, Path destination) {
        Invocation invocation = run(projectDirectory, List.of("stubs", "--out", destination.toString()));
        // `stubs` reports refusals on stderr rather than as a document, so there is nothing to
        // parse. The exit status is the whole result.
        return new FlixResult(invocation.status == 0, List.of());
    }

    private FlixResult compile(Path projectDirectory, String action, List<Path> libraries) {
        List<String> arguments = new ArrayList<>();
        arguments.add(action);
        for (Path library : libraries) {
            arguments.add("--lib");
            arguments.add(library.toString());
        }
        arguments.add("--diagnostics-json");

        Invocation invocation = run(projectDirectory, arguments);
        JsonObject document = document(invocation, action);

        List<FlixDiagnostic> diagnostics = new ArrayList<>();
        if (document.has("diagnostics") && document.get("diagnostics").isJsonArray()) {
            for (JsonElement element : document.getAsJsonArray("diagnostics")) {
                diagnostics.add(diagnostic(element.getAsJsonObject()));
            }
        }
        return new FlixResult(invocation.status == 0, List.copyOf(diagnostics));
    }

    private static FlixDiagnostic diagnostic(JsonObject entry) {
        int line = 0;
        int character = 0;
        if (entry.has("range") && entry.get("range").isJsonObject()) {
            JsonObject range = entry.getAsJsonObject("range");
            if (range.has("start") && range.get("start").isJsonObject()) {
                JsonObject start = range.getAsJsonObject("start");
                line = integer(start, "line");
                character = integer(start, "character");
            }
        }
        return new FlixDiagnostic(
                string(entry, "path"),
                line,
                character,
                string(entry, "code"),
                string(entry, "kind"),
                string(entry, "message"),
                string(entry, "fullMessage"));
    }

    /** Returns the contract document in {@code invocation}, or fails saying why there is none. */
    private static JsonObject document(Invocation invocation, String action) {
        JsonObject document = parse(invocation.stdout);
        if (document == null) {
            throw new FlixClientException(
                    "Flix " + action + " produced no readable output. The compiler may predate the "
                            + "tooling contract.\n" + invocation.stdout);
        }
        return document;
    }

    private static JsonObject parse(String stdout) {
        try {
            JsonElement element = JsonParser.parseString(stdout);
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject document = element.getAsJsonObject();
            // Keyed on the version being present rather than on the payload: a document without it
            // is not this contract, while one with an empty diagnostics list is a clean build.
            return document.has("protocolVersion") ? document : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject document, String name) {
        JsonElement element = document.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static int integer(JsonObject document, String name) {
        JsonElement element = document.get(name);
        return element == null || element.isJsonNull() ? 0 : element.getAsInt();
    }

    private Invocation run(Path workingDirectory, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-jar");
        command.add(compilerJar.toString());
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        try {
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new Invocation(process.waitFor(), stdout);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlixClientException("Interrupted while running the Flix compiler.");
        }
    }

    private record Invocation(int status, String stdout) {
    }
}
