package io.github.edadma.wasm.spec

import scala.collection.mutable

/** Tiny zero-dep JSON parser for the wast2json manifest format.
  *
  * The interpreter library deliberately has no JSON dependency; the test
  * tree should match. This parser supports just enough JSON to read the
  * shape `wast2json` emits: nested objects + arrays of strings, numbers
  * (integers only — the spec corpus never produces fractional JSON
  * literals; numeric wasm values are always quoted decimal bit
  * patterns), booleans, and nulls.
  *
  * The AST is a plain Scala `Any` (`Map[String, Any] | Vector[Any] |
  * String | Long | Boolean | Null`) — small and easy to pattern-match
  * downstream.
  */
private[spec] object MiniJson:

  type JValue = Any

  final case class JsonError(msg: String, pos: Int) extends RuntimeException(s"$msg @ $pos")

  def parse(input: String): JValue =
    val p = new Parser(input)
    p.skipWs()
    val v = p.parseValue()
    p.skipWs()
    if p.pos < input.length then throw JsonError(s"trailing input '${input(p.pos)}'", p.pos)
    v

  /** Same as parse, but expected at the top level to be an object. */
  def parseObject(input: String): Map[String, Any] = parse(input) match
    case m: Map[?, ?] => m.asInstanceOf[Map[String, Any]]
    case other        => throw JsonError(s"expected JSON object, got ${other.getClass.getSimpleName}", 0)

  private final class Parser(s: String):
    var pos: Int = 0

    def skipWs(): Unit =
      while pos < s.length && (s.charAt(pos) match
            case ' ' | '\t' | '\n' | '\r' => true
            case _                        => false
          )
      do pos += 1

    def peek(): Char =
      if pos >= s.length then throw JsonError("unexpected EOF", pos)
      else s.charAt(pos)

    def parseValue(): Any =
      skipWs()
      peek() match
        case '{'                         => parseObject()
        case '['                         => parseArray()
        case '"'                         => parseString()
        case 't' | 'f'                   => parseBool()
        case 'n'                         => parseNull()
        case c if c == '-' || c.isDigit  => parseNumber()
        case other => throw JsonError(s"unexpected '$other'", pos)

    def parseObject(): Map[String, Any] =
      expect('{')
      skipWs()
      val m = mutable.LinkedHashMap.empty[String, Any]
      if peek() == '}' then
        pos += 1
        return m.toMap
      while true do
        skipWs()
        val key = parseString()
        skipWs()
        expect(':')
        val v = parseValue()
        m += (key -> v)
        skipWs()
        peek() match
          case ',' => pos += 1
          case '}' => pos += 1; return m.toMap
          case c   => throw JsonError(s"expected ',' or '}' in object, got '$c'", pos)
      sys.error("unreachable")

    def parseArray(): Vector[Any] =
      expect('[')
      skipWs()
      val buf = mutable.ArrayBuffer.empty[Any]
      if peek() == ']' then
        pos += 1
        return buf.toVector
      while true do
        buf += parseValue()
        skipWs()
        peek() match
          case ',' => pos += 1
          case ']' => pos += 1; return buf.toVector
          case c   => throw JsonError(s"expected ',' or ']' in array, got '$c'", pos)
      sys.error("unreachable")

    def parseString(): String =
      expect('"')
      val sb = new StringBuilder
      while pos < s.length do
        val c = s.charAt(pos)
        if c == '"' then
          pos += 1
          return sb.toString
        else if c == '\\' then
          pos += 1
          if pos >= s.length then throw JsonError("EOF in string escape", pos)
          s.charAt(pos) match
            case '"'  => sb += '"'
            case '\\' => sb += '\\'
            case '/'  => sb += '/'
            case 'b'  => sb += '\b'
            case 'f'  => sb += '\f'
            case 'n'  => sb += '\n'
            case 'r'  => sb += '\r'
            case 't'  => sb += '\t'
            case 'u'  =>
              if pos + 4 >= s.length then throw JsonError("EOF in \\u escape", pos)
              val hex = s.substring(pos + 1, pos + 5)
              sb += Integer.parseInt(hex, 16).toChar
              pos += 4
            case other => throw JsonError(s"bad escape \\$other", pos)
          pos += 1
        else
          sb += c
          pos += 1
      throw JsonError("EOF in string", pos)

    def parseBool(): Boolean =
      if s.startsWith("true", pos) then { pos += 4; true }
      else if s.startsWith("false", pos) then { pos += 5; false }
      else throw JsonError("bad literal", pos)

    def parseNull(): Any =
      if s.startsWith("null", pos) then { pos += 4; null }
      else throw JsonError("bad literal", pos)

    def parseNumber(): Long =
      val start = pos
      if peek() == '-' then pos += 1
      while pos < s.length && s.charAt(pos).isDigit do pos += 1
      if pos < s.length && (s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E') then
        throw JsonError("fractional JSON numbers not supported (wast2json output should not contain any)", start)
      s.substring(start, pos).toLong

    def expect(c: Char): Unit =
      if pos >= s.length || s.charAt(pos) != c then
        throw JsonError(s"expected '$c'", pos)
      pos += 1
