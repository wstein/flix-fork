/*
 * Copyright 2021 Matthew Lutze
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
package ca.uwaterloo.flix.util

import java.net.{URL, URLClassLoader}

/**
  * A class loader to which JARs can be added dynamically.
  *
  * We pass the platform class loader as the parent to avoid it delegating to the system classloader
  * (otherwise compiled Flix code has access to all classes within the compiler)
  */
class ExternalJarLoader extends URLClassLoader(Array.empty, ClassLoader.getPlatformClassLoader) {
  /**
    * Adds the URL to the class loader.
    */
  override def addURL(url: URL): Unit = {
    // just reimplements the superclass, but makes it public
    super.addURL(url)
  }

  override def findClass(name: String): Class[? <: Object] = {
    try {
      super.findClass(name)
    } catch {
      case e: ClassNotFoundException =>
        // Special case for dev.flix.runtime.Global
        // This is never used at runtime, but we need to be able to load it at compile
        // time in order to check method signatures
        if (name == "dev.flix.runtime.Global")
          findCompilerClass(name, e)
        // Special case for testing to allow us to load test classes
        else if (name.startsWith(("dev.flix.test.")))
          findCompilerClass(name, e)
        else
          throw e
    }
  }

  /**
    * Loads one of the whitelisted names above from wherever *the compiler itself* was loaded.
    *
    * These were previously looked up with `findSystemClass`, which searches the class loader
    * built from `java.class.path`. That is the same loader that holds the compiler only when
    * Flix is launched as `java -jar flix.jar`. When Flix is *embedded* -- a build tool calling
    * `Bootstrap` in a worker, an IDE, a test harness -- the system loader holds the host's
    * bootstrap classpath and the compiler sits in a child loader, so the lookup failed and every
    * compilation died in the standard library with
    * `Undefined Java class 'dev.flix.runtime.Global'` before reaching any user code.
    *
    * Resolving against this class's own loader is correct in both cases, because
    * `dev.flix.runtime` ships in the same artifact as this class.
    *
    * The platform parent above is deliberate and is left alone: it stops compiled Flix code
    * reaching the compiler's classes in general. This widens only the two names that were
    * already whitelisted, and only to the artifact they are already part of.
    */
  private def findCompilerClass(name: String, notFound: ClassNotFoundException): Class[? <: Object] = {
    val compilerLoader = classOf[ExternalJarLoader].getClassLoader
    if (compilerLoader == null) {
      // Only when the compiler was loaded by the bootstrap loader, which has no `java.class.path`
      // to consult either. Preserve the previous behaviour rather than inventing one.
      return super.findSystemClass(name)
    }
    try {
      // `initialize = false`: loading a class to inspect its signatures must not run its static
      // initialisers, which is the same reason `Resolver.lookupJvmClass` passes false.
      Class.forName(name, false, compilerLoader)
    } catch {
      case _: ClassNotFoundException => throw notFound
    }
  }
}
