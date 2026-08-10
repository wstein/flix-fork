/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.api.bsp

import ca.uwaterloo.flix.api.Bootstrap
import org.json4s.native.JsonMethods
import org.json4s.{JArray, JInt, JString, jvalue2monadic}
import org.scalatest.DoNotDiscover
import org.scalatest.funsuite.AnyFunSuite

import java.io.{BufferedInputStream, InputStream, OutputStream, PushbackInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit

/**
  * The two things about `flix bsp` that only a real process can show.
  *
  * The first is that the connection file works. A `.bsp/flix.json` naming a command that does not
  * start a server is the entire failure mode of discovery, and it is invisible to any assertion about
  * the document's shape -- so the `argv` in the file is executed and a handshake is completed through
  * it.
  *
  * The second is that standard output carries nothing but protocol frames. In-process tests cannot
  * see this: they hand the server a pipe, while the hazard is the *real* descriptor and the compiler's
  * habit of printing to it. `Bootstrap` narrates dependency resolution on every start, so this is not
  * hypothetical.
  */
@DoNotDiscover
class TestBspProcess extends AnyFunSuite {

  /** Where `./mill flix.assembly` leaves the jar, relative to the repository root. */
  private val AssemblyJar: Path = Paths.get("out", "flix", "assembly.dest", "out.jar")

  private val Timeout: Long = 120

  test("the connection file names a command that really starts a server") {
    val project = newProject()
    install(project)

    val argv = connectionArgv(project)
    // Absolute throughout: a client runs this with the *workspace* as its working directory, so a
    // relative path would resolve against the wrong place.
    assert(argv.forall(a => !a.startsWith("./") && !a.startsWith("../")), s"a relative entry in argv: $argv")
    assert(argv.head.endsWith("java") || argv.head.endsWith("java.exe"), s"argv does not start with java: $argv")

    val process = new ProcessBuilder(argv*).directory(project.toFile).start()
    try {
      val result = handshake(project, process.getOutputStream, process.getInputStream)
      assert((result \ "displayName") == JString(BspSession.ServerName), s"unexpected initialize result: $result")
    } finally {
      process.destroyForcibly()
      process.waitFor(Timeout, TimeUnit.SECONDS)
    }
  }

  test("standard output carries protocol frames and nothing else") {
    val project = newProject()
    install(project)

    val process = new ProcessBuilder(connectionArgv(project)*).directory(project.toFile).start()
    try {
      val out = new PushbackInputStream(new BufferedInputStream(process.getInputStream))
      writeFrame(process.getOutputStream, initializeRequest(project))

      // The first byte is the whole assertion. Anything the compiler printed -- a progress bar, a
      // crash report, `Bootstrap`'s own narration of dependency resolution -- would land here, ahead
      // of the header, and end the connection. `Content-Length` starts with `C`.
      //
      // Pushed back rather than consumed, so the frame it belongs to can still be read: eating it and
      // then parsing the remainder as a header is a way to fail a server that is working.
      val first = out.read()
      assert(first == 'C', s"stdout began with '${first.toChar}' ($first) rather than a Content-Length header")
      out.unread(first)

      val frame = readFrame(out)
      assert(frame.contains("\"jsonrpc\""), s"not a protocol frame: $frame")
      // And what the compiler printed is *inside* a frame rather than beside one: the narration that
      // would have corrupted the stream arrives as a notification.
      assert(
        frame.contains("build/logMessage") || frame.contains("\"id\""),
        s"the first frame is neither a notification nor a response: $frame")
    } finally {
      process.destroyForcibly()
      process.waitFor(Timeout, TimeUnit.SECONDS)
    }
  }

  test("a scripted session drives a real server through the whole cycle") {
    val project = newProject()
    Files.writeString(project.resolve("src").resolve("Main.flix"),
      """def main(): Unit \ IO = println("PROGRAM-RAN")""" + "\n")
    Files.writeString(project.resolve("test").resolve("TestMain.flix"),
      """@Test
        |def testPasses(): Unit \ Assert = Assert.assertEq(expected = 2, 1 + 1)
        |""".stripMargin)
    install(project)

    // The end-to-end case, and the only one where nothing is stubbed: the jar a release ships, started
    // the way a client starts it, driven over hand-written frames. Everything else in these suites
    // runs the server in the test's own JVM, which cannot see a packaging fault -- a missing resource,
    // an excluded class, a shaded dependency.
    val process = new ProcessBuilder(connectionArgv(project)*).directory(project.toFile).start()
    try {
      val out = process.getOutputStream
      val in = new BufferedInputStream(process.getInputStream)

      writeFrame(out, initializeRequest(project))
      assert((awaitId(in, 1) \ "displayName") == JString(BspSession.ServerName), "initialize failed")
      writeFrame(out, """{"jsonrpc":"2.0","method":"build/initialized","params":{}}""")

      val targets = request(out, in, 2, """{"jsonrpc":"2.0","id":2,"method":"workspace/buildTargets","params":{}}""")
      // json4s projects a field of an array element back into an array, so this is a one-element
      // `JArray` rather than the string, however many targets there are.
      val targetUri = (targets \ "targets" \ "id" \ "uri") match {
        case JString(uri) => uri
        case JArray(JString(uri) :: Nil) => uri
        case other => fail(s"no single target in $other")
      }
      val target = s"""{"uri":"$targetUri"}"""

      val sources = request(out, in, 3, s"""{"jsonrpc":"2.0","id":3,"method":"buildTarget/sources","params":{"targets":[$target]}}""")
      assert(JsonMethods.compact(JsonMethods.render(sources)).contains("Main.flix"), s"no sources: $sources")

      val compiled = request(out, in, 4, s"""{"jsonrpc":"2.0","id":4,"method":"buildTarget/compile","params":{"targets":[$target]}}""")
      assert((compiled \ "statusCode") == JInt(1), s"the compile did not succeed: $compiled")

      val ran = request(out, in, 5, s"""{"jsonrpc":"2.0","id":5,"method":"buildTarget/run","params":{"target":$target}}""")
      assert((ran \ "statusCode") == JInt(1), s"the run did not succeed: $ran")

      val tested = request(out, in, 6, s"""{"jsonrpc":"2.0","id":6,"method":"buildTarget/test","params":{"targets":[$target]}}""")
      assert((tested \ "statusCode") == JInt(1), s"the tests did not pass: $tested")

      // And it shuts down when asked, rather than having to be killed.
      request(out, in, 7, """{"jsonrpc":"2.0","id":7,"method":"build/shutdown","params":null}""")
      writeFrame(out, """{"jsonrpc":"2.0","method":"build/exit","params":null}""")
      assert(process.waitFor(Timeout, TimeUnit.SECONDS), "the server did not exit when told to")
    } finally {
      process.destroyForcibly()
      process.waitFor(Timeout, TimeUnit.SECONDS)
    }
  }

  test("a connection file this server did not write is left alone") {
    val project = newProject()
    val file = BspDiscovery.connectionFile(project)
    Files.createDirectories(file.getParent)
    val foreign = """{"name":"someOtherTool","argv":["/bin/true"]}"""
    Files.writeString(file, foreign)

    // `.bsp/` is shared with other build tools, and a file called `flix.json` that something else
    // wrote is still not ours to replace.
    assert(BspDiscovery.install(project, jar = Some(assembly()), force = false).isInstanceOf[ca.uwaterloo.flix.util.Result.Err[?, ?]])
    assert(Files.readString(file) == foreign, "a foreign connection file was overwritten")

    assert(BspDiscovery.install(project, jar = Some(assembly()), force = true).isInstanceOf[ca.uwaterloo.flix.util.Result.Ok[?, ?]])
    assert(Files.readString(file).contains(BspSession.ServerName), "--force did not replace it")
  }

  // ── Harness ──────────────────────────────────────────────────────────────────

  private def assembly(): Path = {
    assert(
      Files.isRegularFile(AssemblyJar),
      s"no assembled jar at $AssemblyJar -- run './mill flix.testBsp', which builds it first")
    AssemblyJar.toAbsolutePath.normalize()
  }

  private def newProject(): Path = {
    val project = Files.createTempDirectory("flix-bsp-process-")
    Bootstrap.init(project)(System.out).unsafeGet
    project
  }

  private def install(project: Path): Unit =
    BspDiscovery.install(project, jar = Some(assembly()), force = false) match {
      case ca.uwaterloo.flix.util.Result.Ok(_) => ()
      case ca.uwaterloo.flix.util.Result.Err(e) => fail(s"could not write the connection file: $e")
    }

  /** Returns the `argv` the connection file names, which is what a client would run. */
  private def connectionArgv(project: Path): List[String] = {
    val document = JsonMethods.parse(Files.readString(BspDiscovery.connectionFile(project)))
    (document \ "argv") match {
      case JArray(values) => values.collect { case JString(s) => s }
      case other => fail(s"the connection file has no argv: $other")
    }
  }

  /**
    * Completes `build/initialize` for `project` and returns the result object.
    *
    * The server refuses a `rootUri` that is not the directory it was started in, so this is also a
    * check that the process really did start where the connection file said.
    */
  private def handshake(project: Path, out: OutputStream, in: InputStream): org.json4s.JValue = {
    writeFrame(out, initializeRequest(project))
    val buffered = new BufferedInputStream(in)
    // Notifications arrive first and they are supposed to: loading the project narrates, and the
    // quarantine turns that narration into `build/logMessage` frames rather than letting it corrupt
    // the stream. So read until the frame carrying this request's id.
    var frames = 0
    while (frames < 50) {
      val message = JsonMethods.parse(readFrame(buffered))
      if ((message \ "id") == org.json4s.JInt(1)) {
        return message \ "result"
      }
      frames += 1
    }
    fail("the server never answered build/initialize")
  }

  /** Sends `body` and returns the result of the response carrying `id`. */
  private def request(out: OutputStream, in: InputStream, id: Int, body: String): org.json4s.JValue = {
    writeFrame(out, body)
    awaitId(in, id)
  }

  /**
    * Reads until the frame answering `id`, and returns its result.
    *
    * Reading until the id is not laziness: a server is entitled to interleave notifications with
    * responses and this one does -- progress tasks, the program's own output, `Bootstrap`'s narration
    * of dependency resolution. A client that expected the next frame to be its answer would fail
    * against a correct server.
    */
  private def awaitId(in: InputStream, id: Int): org.json4s.JValue = {
    var frames = 0
    while (frames < 500) {
      val message = JsonMethods.parse(readFrame(in))
      if ((message \ "id") == JInt(id)) {
        (message \ "error") match {
          case org.json4s.JNothing => return message \ "result"
          case error => fail(s"request $id failed: ${JsonMethods.compact(JsonMethods.render(error))}")
        }
      }
      frames += 1
    }
    fail(s"the server never answered request $id")
  }

  private def initializeRequest(project: Path): String =
    s"""{"jsonrpc":"2.0","id":1,"method":"build/initialize","params":{
       |"displayName":"test-client","version":"1.0","bspVersion":"${ch.epfl.scala.bsp4j.Bsp4j.PROTOCOL_VERSION}",
       |"rootUri":"${BspUri.ofDirectory(project)}","capabilities":{"languageIds":["flix"]}}}""".stripMargin.replace("\n", "")

  private def writeFrame(out: OutputStream, body: String): Unit = {
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    out.write(s"Content-Length: ${bytes.length}\r\n\r\n".getBytes(StandardCharsets.UTF_8))
    out.write(bytes)
    out.flush()
  }

  /** Reads one framed message, header and all. */
  private def readFrame(in: InputStream): String = {
    var length = -1
    var line = readLine(in)
    while (line.nonEmpty) {
      if (line.toLowerCase.startsWith("content-length:")) {
        length = line.split(":")(1).trim.toInt
      }
      line = readLine(in)
    }
    assert(length >= 0, "no Content-Length in the response header")
    val body = new Array[Byte](length)
    var read = 0
    while (read < length) {
      val n = in.read(body, read, length - read)
      assert(n > 0, "the stream ended inside a frame")
      read += n
    }
    new String(body, StandardCharsets.UTF_8)
  }

  /** Reads one CRLF-terminated header line. */
  private def readLine(in: InputStream): String = {
    val builder = new StringBuilder
    var b = in.read()
    while (b != -1 && b != '\n') {
      if (b != '\r') builder.append(b.toChar)
      b = in.read()
    }
    builder.toString
  }
}
