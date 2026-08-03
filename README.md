<p align="center" >
    <img src="https://raw.githubusercontent.com/flix/flix/master/docs/logo.png" height="91px" 
    alt="The Flix Programming Language" 
    title="The Flix Programming Language">
</p>

**Flix** is a statically typed functional, imperative, and logic programming language.

We refer you to the [official Flix website (flix.dev)](https://flix.dev/) for more information about Flix. 

[![Build and Tests](https://img.shields.io/github/actions/workflow/status/wstein/flix-fork/compiler-build-and-test.yaml?label=build%20and%20tests&branch=develop)](https://github.com/wstein/flix-fork/actions/workflows/compiler-build-and-test.yaml)
[![Community Build](https://img.shields.io/github/actions/workflow/status/wstein/flix-fork/community-build.yaml?label=community%20build&branch=develop)](https://github.com/wstein/flix-fork/actions/workflows/community-build.yaml)
[![Latest Tag](https://img.shields.io/github/v/tag/wstein/flix-fork?label=latest%20tag)](https://github.com/wstein/flix-fork/tags)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE.md)
[![Zulip](https://img.shields.io/badge/zulip-join_chat-brightgreen.svg)](https://flix.zulipchat.com/)

## About this fork

This is **[wstein/flix-fork](https://github.com/wstein/flix-fork)**, an unofficial,
experimental fork of [flix/flix](https://github.com/flix/flix) maintained independently
of the Flix core team. It is not affiliated with, endorsed by, or officially connected to
the Flix project; "Flix" and the Flix logo belong to their respective owners and are used
here only to describe the origin of this code, per the Apache 2.0 license.

This fork tracks upstream `master` and adds experimental extensions not (yet) available
there, including source-level debugging (`--Xdebug`), coverage instrumentation, and
additional documentation tooling. Expect these to be unstable and to change without
notice. For the stable, official compiler, use [flix/flix](https://github.com/flix/flix)
directly.

### Download (experimental)

Every `v*` tag publishes one runnable fat JAR as a
[GitHub Release](https://github.com/wstein/flix-fork/releases) asset, named
`flix-<version>.jar`. Nothing needs to be configured and no account is needed to
download it:

```bash
java -jar flix-0.75.1+fork.wstein.260802.2.jar --version
```

These are experimental builds of a fork — not official Flix releases, and not covered by
any stability or compatibility guarantee.

This fork is deliberately not published to any Maven registry. It is a compiler you run,
not a library you depend on, so a jar is the whole story.

## Example

```flix
///
/// The expressions of the lambda calculus are: variables, lambda abstractions, and applications.
///
enum Expression {
    // A variable expression. A variable is represented by an integer. 
    case Var(Int32),

    // A lambda abstracation expression. A variable is represented by an integer.
    case Abs(Int32, Expression),

    // A function application expression.
    case App(Expression, Expression),
}

///
/// Performs alpha conversion by introducing fresh variables for all variables in the given expression `e0`.
///
def alpha(e0: Expression, m: Map[Int32, Int32]): Expression = match e0 {
    case Var(x) =>
        // Check if we need to rename the variable.
        match Map.get(x, m) {
            case None => Var(x)
            case Some(y) => Var(y)
        }

    case Abs(x, e) =>
        // Generate a fresh variable name for `x`.
        let y = freshVar();
        Abs(y, alpha(e, Map.insert(x, y, m)))

    case App(e1, e2) =>
        // Recursively perform alpha conversion on each expression.
        App(alpha(e1, m), alpha(e2, m))
}
```

## Building

See [docs/BUILD.md](docs/BUILD.md).

## License

Flix, and this fork, are available under the Apache 2.0 license.

## Sponsors

We kindly thank [EJ Technologies](https://www.ej-technologies.com/) for providing us with 
[JProfiler](http://www.ej-technologies.com/products/jprofiler/overview.html)
and [JetBrains](https://www.jetbrains.com/) for providing us with 
[IntelliJ IDEA](https://www.jetbrains.com/idea/).
