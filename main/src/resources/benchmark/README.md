# Benchmark Suite

A benchmark suite as a single self-contained Flix program.

The benchmark should be compiled and packaged to a JAR.

The benchmark can then be run in a fresh JVM and will emit JSON.

The workflow should be:

```shell
$ java -jar flix.jar build-jar
$ java -jar artifact/benchmark.jar
```

`build-jar` always performs a full, clean build, so a separate `build` step is
not required.
