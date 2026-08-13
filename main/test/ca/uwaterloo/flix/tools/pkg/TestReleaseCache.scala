package ca.uwaterloo.flix.tools.pkg

import ca.uwaterloo.flix.tools.pkg.github.GitHub.{Project, ReleaseResponse}
import ca.uwaterloo.flix.tools.pkg.github.GitHub.Release
import ca.uwaterloo.flix.util.{Formatter, Result}
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.mutable

/**
  * Tests for the release listing cache.
  *
  * Every response is supplied rather than fetched. A cache is defined by what it does with answers
  * it cannot control -- an unchanged listing, a refusal, a refusal with nothing to fall back on --
  * and none of those can be asked for from a live registry on demand.
  */
class TestReleaseCache extends AnyFunSuite {

  private implicit val formatter: Formatter = Formatter.NoFormatter

  private val Any: Project = Project("owner", "repo")

  private def listing(version: String): String =
    s"""[{"tag_name": "v$version", "assets": []}]"""

  private def emptyCache(): Path = Files.createTempDirectory("flix-release-cache-")

  private def writeEntry(cache: Path, project: Project, body: String, fetchedAt: Long, etag: String = "\"tag\""): Unit = {
    val file = cache.resolve(project.owner).resolve(s"${project.repo}.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, s"""{"body": ${quote(body)}, "etag": ${quote(etag)}, "fetchedAt": $fetchedAt}""",
      StandardCharsets.UTF_8)
  }

  private def quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** Records what it was asked, and answers with `answers` in order. */
  private class Recorder(answers: Result[ReleaseResponse, PackageError]*) {
    val asked: mutable.ListBuffer[Option[String]] = mutable.ListBuffer.empty
    private var remaining = answers.toList

    val fetch: ReleaseCache.Fetch = (_, _, etag) => {
      asked += etag
      remaining match {
        case head :: rest => remaining = rest; head
        case Nil => fail("The cache asked more often than the test allowed for.")
      }
    }
  }

  private def rateLimited: Result[ReleaseResponse, PackageError] =
    Err(PackageError.ApiRateLimited(Any, URI.create("https://api.github.invalid").toURL, Some(1786361585L)))

  private def capturing[T](f: PrintStream => T): (T, String) = {
    val buffer = new ByteArrayOutputStream()
    val sink = new PrintStream(buffer, true, StandardCharsets.UTF_8)
    try (f(sink), buffer.toString(StandardCharsets.UTF_8)) finally sink.close()
  }

  private def versionsOf(result: Result[List[Release], PackageError]): List[String] =
    result match {
      case Ok(releases) => releases.map(_.version.toString)
      case Err(e) => fail(s"Expected a listing, but found: ${e.message(formatter)}")
    }

  test("a fresh entry is answered without asking anyone") {
    val cache = emptyCache()
    val now = 1_000_000L
    writeEntry(cache, Any, listing("1.2.3"), fetchedAt = now)
    val recorder = new Recorder()

    val (result, _) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, now, cache, recorder.fetch)
    }

    assertResult(List("1.2.3"))(versionsOf(result))
    assertResult(Nil)(recorder.asked.toList)
  }

  test("an entry just inside the window is still fresh") {
    val cache = emptyCache()
    val fetchedAt = 1_000_000L
    writeEntry(cache, Any, listing("1.2.3"), fetchedAt)
    val recorder = new Recorder()

    capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, fetchedAt + (15 * 60 * 1000) - 1, cache, recorder.fetch)
    }

    assertResult(Nil)(recorder.asked.toList)
  }

  test("a stale entry is revalidated, quoting its entity tag") {
    val cache = emptyCache()
    val fetchedAt = 1_000_000L
    writeEntry(cache, Any, listing("1.2.3"), fetchedAt, etag = "\"abc\"")
    val recorder = new Recorder(Ok(ReleaseResponse.NotModified))

    val (result, _) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, fetchedAt + (15 * 60 * 1000), cache, recorder.fetch)
    }

    assertResult(List(Some("\"abc\"")))(recorder.asked.toList)
    assertResult(List("1.2.3"))(versionsOf(result))
  }

  test("a listing confirmed unchanged is not asked about again inside the window") {
    // What the revalidation buys: the entry's age is reset, so the next call is a cache hit.
    val cache = emptyCache()
    val fetchedAt = 1_000_000L
    writeEntry(cache, Any, listing("1.2.3"), fetchedAt)
    val recorder = new Recorder(Ok(ReleaseResponse.NotModified))

    capturing { implicit out =>
      val stale = fetchedAt + (15 * 60 * 1000)
      ReleaseCache.getReleases(Any, None, stale, cache, recorder.fetch)
      ReleaseCache.getReleases(Any, None, stale + 1, cache, recorder.fetch)
    }

    assertResult(1)(recorder.asked.size)
  }

  test("a changed listing replaces the entry") {
    val cache = emptyCache()
    val fetchedAt = 1_000_000L
    writeEntry(cache, Any, listing("1.2.3"), fetchedAt)
    val recorder = new Recorder(Ok(ReleaseResponse.Modified(listing("2.0.0"), Some("\"new\""))))

    val (result, _) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, fetchedAt + (15 * 60 * 1000), cache, recorder.fetch)
    }

    assertResult(List("2.0.0"))(versionsOf(result))

    // And the replacement is what a later call revalidates from.
    val next = new Recorder(Ok(ReleaseResponse.NotModified))
    capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, fetchedAt + (60 * 60 * 1000), cache, next.fetch)
    }
    assertResult(List(Some("\"new\"")))(next.asked.toList)
  }

  test("a refused request falls back to a stale entry, and says so") {
    val cache = emptyCache()
    val fetchedAt = 1_000_000L
    writeEntry(cache, Any, listing("1.2.3"), fetchedAt)
    val recorder = new Recorder(rateLimited)

    val (result, printed) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, fetchedAt + (24 * 60 * 60 * 1000), cache, recorder.fetch)
    }

    assertResult(List("1.2.3"))(versionsOf(result))
    assert(printed.contains("rate limit"), s"the fallback was silent. Printed:\n$printed")
  }

  test("a refused request with nothing cached is the error it was") {
    val recorder = new Recorder(rateLimited)

    val (result, _) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, 1_000_000L, emptyCache(), recorder.fetch)
    }

    result match {
      case Err(_: PackageError.ApiRateLimited) => succeed
      case other => fail(s"Expected the refusal to be reported, but found: $other")
    }
  }

  test("a refusal that is not a rate limit is never answered from the cache") {
    // Waiting does not fix a repository nobody is allowed to read, so serving stale data would
    // hide the problem rather than ride it out.
    val cache = emptyCache()
    val fetchedAt = 1_000_000L
    writeEntry(cache, Any, listing("1.2.3"), fetchedAt)
    val forbidden = Err(PackageError.ApiForbidden(Any, URI.create("https://api.github.invalid").toURL))
    val recorder = new Recorder(forbidden)

    val (result, _) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, fetchedAt + (15 * 60 * 1000), cache, recorder.fetch)
    }

    result match {
      case Err(_: PackageError.ApiForbidden) => succeed
      case other => fail(s"Expected the refusal to be reported, but found: $other")
    }
  }

  test("an unreadable entry is a miss, not a failure to be reported") {
    val cache = emptyCache()
    val file = cache.resolve(Any.owner).resolve(s"${Any.repo}.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8)
    val recorder = new Recorder(Ok(ReleaseResponse.Modified(listing("3.0.0"), None)))

    val (result, _) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, 1_000_000L, cache, recorder.fetch)
    }

    assertResult(List("3.0.0"))(versionsOf(result))
    // Asked without an entity tag, because there was nothing to quote.
    assertResult(List(None))(recorder.asked.toList)
  }

  test("an entry missing its timestamp is a miss") {
    val cache = emptyCache()
    val file = cache.resolve(Any.owner).resolve(s"${Any.repo}.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, s"""{"body": ${quote(listing("1.2.3"))}}""", StandardCharsets.UTF_8)
    val recorder = new Recorder(Ok(ReleaseResponse.Modified(listing("3.0.0"), None)))

    val (result, _) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, 1_000_000L, cache, recorder.fetch)
    }

    assertResult(List("3.0.0"))(versionsOf(result))
  }

  test("an empty cache directory is a miss") {
    val recorder = new Recorder(Ok(ReleaseResponse.Modified(listing("3.0.0"), None)))

    val (result, _) = capturing { implicit out =>
      ReleaseCache.getReleases(Any, None, 1_000_000L, emptyCache(), recorder.fetch)
    }

    assertResult(List("3.0.0"))(versionsOf(result))
  }

  test("entries are kept apart by project") {
    val cache = emptyCache()
    val now = 1_000_000L
    val other = Project("owner", "other")
    writeEntry(cache, Any, listing("1.2.3"), now)
    writeEntry(cache, other, listing("9.9.9"), now)
    val recorder = new Recorder()

    val (mine, _) = capturing { implicit out => ReleaseCache.getReleases(Any, None, now, cache, recorder.fetch) }
    val (theirs, _) = capturing { implicit out => ReleaseCache.getReleases(other, None, now, cache, recorder.fetch) }

    assertResult(List("1.2.3"))(versionsOf(mine))
    assertResult(List("9.9.9"))(versionsOf(theirs))
  }

  test("the default cache directory is per user and names the tool") {
    val path = ReleaseCache.defaultCacheDirectory.toString
    assert(path.contains("flix"), s"expected a flix-owned directory, found $path")
    assert(path.endsWith("releases"), s"expected listings kept apart from other caches, found $path")
  }
}
