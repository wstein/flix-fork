package ca.uwaterloo.flix.tools.pkg

import ca.uwaterloo.flix.tools.pkg.github.GitHub.Project
import ca.uwaterloo.flix.util.Formatter
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class TestReleaseCache extends AnyFunSuite {

  private implicit val formatter: Formatter = Formatter.NoFormatter

  /**
    * A project that does not exist, so that any test reaching the network fails loudly instead of
    * quietly proving nothing. Every test here asserts about the cache, so none of them should.
    */
  private val Absent: Project = Project("flix-does-not-exist", "flix-does-not-exist")

  private val OneRelease: String =
    """[{"tag_name": "v1.2.3", "assets": [{"name": "a.fpkg", "browser_download_url": "https://example.invalid/a.fpkg"}]}]"""

  private def emptyCache(): Path = Files.createTempDirectory("flix-release-cache-")

  private def writeEntry(cache: Path, project: Project, body: String, fetchedAt: Long, etag: String = "\"tag\""): Unit = {
    val file = cache.resolve(project.owner).resolve(s"${project.repo}.json")
    Files.createDirectories(file.getParent)
    val json = s"""{"body": ${quote(body)}, "etag": ${quote(etag)}, "fetchedAt": $fetchedAt}"""
    Files.writeString(file, json, StandardCharsets.UTF_8)
  }

  private def quote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  private def silently[T](f: PrintStream => T): T = capturing(f)._1

  /** Runs `f` and returns what it produced together with what it printed. */
  private def capturing[T](f: PrintStream => T): (T, String) = {
    val buffer = new ByteArrayOutputStream()
    val sink = new PrintStream(buffer, true, StandardCharsets.UTF_8)
    try (f(sink), buffer.toString(StandardCharsets.UTF_8)) finally sink.close()
  }

  test("a fresh entry answers without asking anyone") {
    // The project does not exist, so a listing could only have come from the cache.
    val cache = emptyCache()
    val now = 1_000_000L
    writeEntry(cache, Absent, OneRelease, fetchedAt = now)

    silently { implicit out =>
      ReleaseCache.getReleases(Absent, None, now, cache) match {
        case Ok(releases) => assertResult(List(SemVer(1, 2, 3)))(releases.map(_.version))
        case Err(e) => fail(s"Expected the cached listing, but found: ${e.message(formatter)}")
      }
    }
  }

  test("an entry just inside the window is still fresh") {
    val cache = emptyCache()
    val fetchedAt = 1_000_000L
    writeEntry(cache, Absent, OneRelease, fetchedAt)

    silently { implicit out =>
      val justInside = fetchedAt + (15 * 60 * 1000) - 1
      assert(ReleaseCache.getReleases(Absent, None, justInside, cache).isInstanceOf[Ok[?, ?]])
    }
  }

  test("a stale entry is never served without saying so") {
    // Past the window the cache goes to the network. Whether that succeeds, fails, or is refused
    // depends on the quota of whoever runs the tests, so the assertion is the one thing that must
    // hold in every case: a stale listing is either replaced, or reported as stale, never returned
    // silently as though it were current.
    val cache = emptyCache()
    val fetchedAt = 1_000_000L
    writeEntry(cache, Absent, OneRelease, fetchedAt)

    val (result, printed) = capturing { implicit out =>
      ReleaseCache.getReleases(Absent, None, fetchedAt + (15 * 60 * 1000), cache)
    }

    result match {
      case Err(_) => succeed
      case Ok(_) => assert(printed.contains("Using cached release data"),
        s"a stale listing was served with no notice. Printed:\n$printed")
    }
  }

  test("an unreadable entry is a miss, not a failure to be reported") {
    val cache = emptyCache()
    val file = cache.resolve(Absent.owner).resolve(s"${Absent.repo}.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8)

    silently { implicit out =>
      // Treated as absent, so it goes to the network and fails there rather than being reported as
      // a broken cache. Which failure depends on the quota of whoever runs the tests.
      ReleaseCache.getReleases(Absent, None, 1_000_000L, cache) match {
        case Err(_) => succeed
        case Ok(_) => fail("Expected a project that does not exist to fail once the cache was skipped.")
      }
    }
  }

  test("an entry missing its timestamp is a miss") {
    val cache = emptyCache()
    val file = cache.resolve(Absent.owner).resolve(s"${Absent.repo}.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, s"""{"body": ${quote(OneRelease)}}""", StandardCharsets.UTF_8)

    silently { implicit out =>
      assert(ReleaseCache.getReleases(Absent, None, 1_000_000L, cache).isInstanceOf[Err[?, ?]])
    }
  }

  test("an empty cache directory is a miss") {
    silently { implicit out =>
      assert(ReleaseCache.getReleases(Absent, None, 1_000_000L, emptyCache()).isInstanceOf[Err[?, ?]])
    }
  }

  test("the default cache directory is per user and names the tool") {
    val path = ReleaseCache.defaultCacheDirectory.toString
    assert(path.contains("flix"), s"expected a flix-owned directory, found $path")
    assert(path.endsWith("releases"), s"expected listings kept apart from other caches, found $path")
  }
}
