package ca.uwaterloo.flix.api

import org.scalatest.funsuite.AnyFunSuite

class TestVersion extends AnyFunSuite {

  /** The shape `git describe --tags --long --dirty` emits for a fork release tag. */
  private def qualified(commits: Int, dirty: Boolean = false): String =
    s"v0.75.1+fork.wstein.260802.1-$commits-ge401b4b6${if (dirty) "-dirty" else ""}"

  /** The same shape for an upstream-style tag carrying no fork qualifier. */
  private def plain(commits: Int, dirty: Boolean = false): String =
    s"v0.75.1-$commits-ge401b4b6${if (dirty) "-dirty" else ""}"

  //
  // Fork release tags: v<upstream>+fork.wstein.<YYMMDD>.<N>
  //

  test("parseDescribe.qualified.exact.clean") {
    // Sitting exactly on the tag with a clean tree: the tag's own qualifier is the
    // whole story, with no distance-from-tag noise appended.
    val v = Version.parseDescribe(Some(qualified(commits = 0)))
    assert(v == Version(0, 75, 1, Some("fork.wstein.260802.1")))
  }

  test("parseDescribe.qualified.exact.dirty") {
    // Zero commits past the tag is *not* enough on its own -- a dirty tree means the
    // build does not correspond to the tag, so the distance and sha are reported too.
    val v = Version.parseDescribe(Some(qualified(commits = 0, dirty = true)))
    assert(v == Version(0, 75, 1, Some("fork.wstein.260802.1.0.ge401b4b6.dirty")))
  }

  test("parseDescribe.qualified.ahead.clean") {
    val v = Version.parseDescribe(Some(qualified(commits = 5)))
    assert(v == Version(0, 75, 1, Some("fork.wstein.260802.1.5.ge401b4b6")))
  }

  test("parseDescribe.qualified.ahead.dirty") {
    val v = Version.parseDescribe(Some(qualified(commits = 5, dirty = true)))
    assert(v == Version(0, 75, 1, Some("fork.wstein.260802.1.5.ge401b4b6.dirty")))
  }

  test("parseDescribe.qualified.forkQualifierIsNotSplitOnDots") {
    // The qualifier is captured non-greedily up to the `-<commits>-g<sha>` suffix, so
    // its internal dots must survive intact rather than being treated as separators.
    val v = Version.parseDescribe(Some("v1.2.3+fork.wstein.991231.42-0-gdeadbee"))
    assert(v == Version(1, 2, 3, Some("fork.wstein.991231.42")))
  }

  //
  // Plain tags with no fork qualifier.
  //

  test("parseDescribe.plain.exact.clean") {
    // An exact, clean, unqualified tag is the only case that reports no qualifier at all.
    val v = Version.parseDescribe(Some(plain(commits = 0)))
    assert(v == Version(0, 75, 1, None))
  }

  test("parseDescribe.plain.exact.dirty") {
    val v = Version.parseDescribe(Some(plain(commits = 0, dirty = true)))
    assert(v == Version(0, 75, 1, Some("0.ge401b4b6.dirty")))
  }

  test("parseDescribe.plain.ahead.clean") {
    val v = Version.parseDescribe(Some(plain(commits = 5)))
    assert(v == Version(0, 75, 1, Some("5.ge401b4b6")))
  }

  test("parseDescribe.plain.ahead.dirty") {
    val v = Version.parseDescribe(Some(plain(commits = 5, dirty = true)))
    assert(v == Version(0, 75, 1, Some("5.ge401b4b6.dirty")))
  }

  //
  // Degraded inputs. These must not throw: a version string is never worth failing a
  // build or aborting startup over.
  //

  test("parseDescribe.unparseable.reportedVerbatim") {
    // What `gitDescribe` writes when no `v*` tag is reachable, e.g. a shallow clone.
    val v = Version.parseDescribe(Some("unknown"))
    assert(v == Version(0, 0, 0, Some("unknown")))
  }

  test("parseDescribe.unparseable.arbitraryString") {
    val v = Version.parseDescribe(Some("not-a-describe-output"))
    assert(v == Version(0, 0, 0, Some("not-a-describe-output")))
  }

  test("parseDescribe.unparseable.vendorTag") {
    // Pins why build.mill anchors its describe glob to `v[0-9]*`: a bare `v*` also
    // matches this repository's `vendor-*` tags, and such a tag reaching this point
    // degrades the reported version to 0.0.0 rather than failing loudly.
    val v = Version.parseDescribe(Some("vendor-2026.07.24.1-0-ge401b4b6"))
    assert(v == Version(0, 0, 0, Some("vendor-2026.07.24.1-0-ge401b4b6")))
  }

  test("parseDescribe.missingResource") {
    // No `version.txt` on the classpath at all, i.e. a hand-assembled classpath.
    val v = Version.parseDescribe(None)
    assert(v == Version(0, 0, 0, Some("unknown")))
  }

  //
  // Rendering.
  //

  test("toString.withQualifier") {
    assert(Version(0, 75, 1, Some("fork.wstein.260802.1")).toString == "0.75.1+fork.wstein.260802.1")
  }

  test("toString.withoutQualifier") {
    assert(Version(0, 75, 1).toString == "0.75.1")
  }

  test("CurrentVersion.isWellFormed") {
    // Whatever the build stamped in, it must at least render as a dotted triple: this is
    // what `flix --version` prints, and object initialisation must not have thrown.
    assert(Version.CurrentVersion.toString.matches("""^\d+\.\d+\.\d+(\+\S+)?$"""))
  }
}
