# Build Output Ownership

The compiler owns generated class files below `build/development/class/` for `build`
and below `build/class/` for `build-classes`.

After a successful compilation, the corresponding class directory is reconciled with
the compiler's current class set: stale `.class` files are removed and empty package
directories are pruned. This includes classes produced by an earlier build for a
source definition that has since been removed.

An ordinary build does not delete non-class files placed in a class directory. Use
`clean` when the whole build output directory should be removed.

Reconciliation runs only after compilation and class emission succeed, preserving the
last successful build when compilation fails.
