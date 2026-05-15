package io.github.edadma.wasm

import TestSupport.*

/** Tests for the `name` custom section (Section 0 named "name"), subsection 1
  * (function names). The parser surfaces it on `WasmModule.funcNames`; the
  * validator threads names through `validateFunction` and prints them in
  * diagnostic messages as `function <N> (name): ...` when present.
  *
  * Subsections 0 (module name), 2 (local names), and any later additions are
  * intentionally skipped — they don't help the user-facing diagnostics that
  * are this codebase's only call site.
  */
object NameSectionTests:

  def run(): Unit =
    parseFunctionNames()
    errorMessageIncludesFunctionName()
    malformedNameSectionIgnored()

  // === Minimal-module bytes ================================================
  //
  // A no-op function (() -> ()) at funcidx 0. Header + type + function +
  // code = the shortest valid module that defines a single function we can
  // name. We construct the bytes by hand so the test stays free of fixture
  // dependencies.

  /** Build a module with the given trailing bytes appended after the core
    * sections — used to add a `name` section without spinning up `wat2wasm`. */
  private def minimalModule(extraSections: Array[Byte] = Array.empty): Array[Byte] =
    val typeSection = b(0x01, 0x04, 0x01, 0x60, 0x00, 0x00)                 // 1 type: () -> ()
    val funcSection = b(0x03, 0x02, 0x01, 0x00)                             // 1 func: type 0
    val codeSection = b(0x0a, 0x04, 0x01, 0x02, 0x00, 0x0b)                 // 1 body: locals 0, end
    Header ++ typeSection ++ funcSection ++ codeSection ++ extraSections

  /** Build a Section 0 ("custom") body declaring `name`'s subsection 1 from
    * the given `funcIdx -> name` pairs. */
  private def nameSection(entries: Seq[(Int, String)]): Array[Byte] =
    // Subsection 1 vec content: count + (funcidx + length-prefixed name) per entry.
    val vecHeader = b(entries.length)
    val vecBody   = entries.flatMap { case (idx, name) =>
      val nb = name.getBytes("UTF-8")
      b(idx, nb.length) ++ nb.toSeq
    }.toArray
    val sub1Body = vecHeader ++ vecBody
    val sub1     = b(0x01, sub1Body.length) ++ sub1Body                     // subsection kind=1
    val nameStr  = b(0x04, 'n'.toInt, 'a'.toInt, 'm'.toInt, 'e'.toInt)      // section name "name"
    val secBody  = nameStr ++ sub1
    b(0x00, secBody.length) ++ secBody                                      // section 0 + size + body

  // === Tests ===============================================================

  private def parseFunctionNames(): Unit =
    test("name section: parser surfaces function names on WasmModule.funcNames") {
      val bytes = minimalModule(nameSection(Seq(0 -> "f0")))
      Parser.parse(bytes) match
        case Right(m) =>
          check(m.funcNames.get(0).contains("f0"),
            s"expected funcNames(0) = 'f0', got ${m.funcNames}")
        case Left(err) => check(false, s"parse failed: $err")
    }

    test("name section: empty subsection is parsed without error") {
      val bytes = minimalModule(nameSection(Seq.empty))
      Parser.parse(bytes) match
        case Right(m) =>
          check(m.funcNames.isEmpty, s"funcNames should be empty: ${m.funcNames}")
        case Left(err) => check(false, s"parse failed: $err")
    }

    test("name section: multiple funcidx -> name pairs all surface") {
      // A module with three functions; subsection 1 lists names for 0 and 2 only.
      val typeSection = b(0x01, 0x04, 0x01, 0x60, 0x00, 0x00)
      val funcSection = b(0x03, 0x04, 0x03, 0x00, 0x00, 0x00)                 // 3 funcs, all type 0
      val codeSection = b(0x0a, 0x0a, 0x03,
                          0x02, 0x00, 0x0b,
                          0x02, 0x00, 0x0b,
                          0x02, 0x00, 0x0b)                                    // 3 bodies, each locals 0 + end
      val bytes = Header ++ typeSection ++ funcSection ++ codeSection ++
                  nameSection(Seq(0 -> "first", 2 -> "third"))
      Parser.parse(bytes) match
        case Right(m) =>
          check(m.funcNames.get(0).contains("first"),  s"funcNames(0): ${m.funcNames}")
          check(m.funcNames.get(2).contains("third"),  s"funcNames(2): ${m.funcNames}")
          check(!m.funcNames.contains(1),              s"funcNames should NOT contain 1: ${m.funcNames}")
        case Left(err) => check(false, s"parse failed: $err")
    }

  private def errorMessageIncludesFunctionName(): Unit =
    test("name section: validator error message includes the function name in parentheses") {
      // Body is `i32.add end` with an empty stack — the validator pops two
      // i32s and finds underflow, surfacing a TypeMismatch via fail(). The
      // error message format is "function <N> (name): byte offset 0x..: …".
      val typeSection = b(0x01, 0x04, 0x01, 0x60, 0x00, 0x00)
      val funcSection = b(0x03, 0x02, 0x01, 0x00)
      val codeSection = b(0x0a, 0x05, 0x01, 0x03, 0x00, 0x6a, 0x0b)            // body: locals 0, i32.add, end
      val bytes = Header ++ typeSection ++ funcSection ++ codeSection ++
                  nameSection(Seq(0 -> "badFunc"))
      Runtime.instantiate(bytes, Seq(EnvModule.default)) match
        case Left(WasmError.InvalidModule(msg)) =>
          check(msg.contains("function 0") && msg.contains("(badFunc)"),
            s"expected message to mention `function 0 (badFunc)`, got: $msg")
        case Left(other) => check(false, s"expected InvalidModule, got: $other")
        case Right(_)    => check(false, "expected instantiate to fail with InvalidModule")
    }

  private def malformedNameSectionIgnored(): Unit =
    test("name section: truncated subsection 1 leaves funcNames empty (no crash)") {
      // Outer section is 9 bytes (1 + 5 + 1 + 1 + 2 = 5 + 4 after the size
      // byte). Inside, subsection 1 claims 100 bytes of payload but only
      // 2 are present before the outer section ends; the parser should
      // silently drop the subsection rather than crash the whole load.
      val truncated = b(0x00, 0x09,                                       // section 0, size 9
                        0x04, 'n'.toInt, 'a'.toInt, 'm'.toInt, 'e'.toInt, // name "name"  (5 bytes)
                        0x01, 0x64,                                       // subsec 1, size 100 (lie)
                        0x00, 0x00)                                       // 2 payload bytes
      val bytes = minimalModule(truncated)
      Parser.parse(bytes) match
        case Right(m) =>
          check(m.funcNames.isEmpty,
            s"funcNames should be empty after dropping a malformed section: ${m.funcNames}")
        case Left(err) => check(false, s"parse should not fail on a malformed name section: $err")
    }

    test("name section: unknown subsection kinds are skipped (forward-compat)") {
      // Subsection 99 is unspecified; the parser must walk past it without
      // touching funcNames. We follow it with a valid subsection 1 to
      // confirm parsing continues.
      val sub99Body = b(0xaa, 0xbb)                                       // arbitrary 2 bytes
      val sub99     = b(0x63, sub99Body.length) ++ sub99Body              // kind=99
      val sub1Body  = b(0x01, 0x00, 0x02, 'g'.toInt, '0'.toInt)           // 1 entry: idx 0, name "g0"
      val sub1      = b(0x01, sub1Body.length) ++ sub1Body
      val nameStr   = b(0x04, 'n'.toInt, 'a'.toInt, 'm'.toInt, 'e'.toInt)
      val secBody   = nameStr ++ sub99 ++ sub1
      val sec       = b(0x00, secBody.length) ++ secBody
      val bytes     = minimalModule(sec)
      Parser.parse(bytes) match
        case Right(m) =>
          check(m.funcNames.get(0).contains("g0"),
            s"expected funcNames(0) = 'g0' after skipping unknown subsection: ${m.funcNames}")
        case Left(err) => check(false, s"parse failed: $err")
    }
