package io.github.edadma.wasm

import java.lang as jl

/** Phase 8.E SIMD dispatch — `stepFd` plus all its shape-aware helpers.
  *
  * Lifted out of `interpreter.scala` once the SIMD code grew past ~700
  * LOC, to keep the top-level interpreter file under ~1700 LOC and
  * within easy navigation reach. The trait mixes into [[Interpreter]]
  * via a self-type and reaches the interpreter's state through the
  * `private[wasm]` accessors declared there (`valueStack`, `frame`,
  * `popI32` / `popV128` / etc., `boundsCheck`, `memArgMemory`,
  * `readU32At`, `fail`). Because both are in the `io.github.edadma.wasm`
  * package, package-private visibility is enough — no member needs to
  * leak past the package boundary.
  *
  * The companion object [[SimdDispatch]] holds a single static helper
  * — `skipSimdImmediates` — that the eager byte-walking decoder in
  * `Interpreter.skipImmediates` calls for the `0xFD` arm.
  *
  * Chunks landed (in order):
  *   - A: foundations + `v128.const` (sub 12).
  *   - B: 14 loads / stores (subs 0..11, 92, 93).
  *   - C: 23 lane-access ops (shuffle, swizzle, 6 splats, 8
  *     extract_lane, 6 replace_lane — subs 13..34).
  *   - D: 29 integer-arithmetic ops (abs / neg / add / sub / mul /
  *     saturating / avgr_u across i8x16, i16x8, i32x4, i64x2 —
  *     subs 0x60..0x7B, 0x80..0x9B, 0xA0..0xB5, 0xC0..0xD5).
  *   - E: 24 shift + min/max ops (shl / shr_s / shr_u on all four
  *     integer shapes; min/max _s/_u on i8x16, i16x8, i32x4 — subs
  *     0x6B..0x6D / 0x76..0x79, 0x8B..0x8D / 0x96..0x99, 0xAB..0xAD /
  *     0xB6..0xB9, 0xCB..0xCD).
  *   - F: 30 float arithmetic ops (rounding ceil/floor/trunc/nearest +
  *     abs/neg/sqrt + add/sub/mul/div + min/max/pmin/pmax — subs
  *     0x67..0x6A, 0x74 / 0x75 / 0x7A / 0x94, 0xE0..0xEB, 0xEC..0xF7).
  *
  * Chunks remaining: G (bitwise + comparisons + reductions), H
  * (narrow/widen + float conversions), I (special — dot product +
  * load_lane / store_lane).
  * Unknown sub-opcodes fall through to `UnknownOpcode(0xfd)`.
  */
private[wasm] trait SimdDispatch:
  self: Interpreter =>

  import Interpreter.*

  /** Dispatch one 0xFD sub-opcode. Same structural pattern as `stepFc`
    * over in interpreter.scala — read the LEB sub-opcode, branch on it,
    * advance `f.pc` past the immediate (where present), push the
    * result. Sub-opcodes >= 0x80 encode as 2-byte LEBs in the binary;
    * `readU32At` already handles that transparently. */
  private[wasm] def stepFd(f: Frame): Unit =
    val body = f.func.body
    val (sub, p1) = readU32At(f, f.pc + 1)
    // Advance past the LEB-encoded sub-opcode so subsequent immediate
    // reads (memarg, lane index, raw literal, ...) start at `f.pc + 0`.
    // `readMemArgAt` below uses `p1` directly to keep the entry pattern
    // identical for every chunk-B-and-beyond opcode.
    sub match
      case 12 =>                                                                          // v128.const
        // Encoding: 0xFD 0x0C followed by 16 raw bytes (little-endian).
        // The wat-side `i32x4 1 2 3 4` is just an annotation — the
        // binary form is always 16 opaque bytes.
        val end = p1 + 16
        if end > body.length then
          fail(WasmError.InvalidModule(s"truncated v128.const literal at ${f.pc}"))
        val bits = new Array[Byte](16)
        System.arraycopy(body, p1, bits, 0, 16)
        f.pc = end
        valueStack += V128(bits)

      // === Chunk B — loads ====================================================

      case 0 =>                                                                           // v128.load : 16-byte aligned load
        val memArg = readMemArgAt(f, p1)
        val mem    = memArgMemory(memArg)
        val addr   = (popI32() & 0xffffffffL) + memArg.offset
        boundsCheck(mem, addr, 16)
        val bits = new Array[Byte](16)
        System.arraycopy(mem.data, addr.toInt, bits, 0, 16)
        valueStack += V128(bits)

      case 1 =>                                                                           // v128.load8x8_s
        loadExtPair(f, p1, width = 1, signed = true,  outLaneBytes = 2)
      case 2 =>                                                                           // v128.load8x8_u
        loadExtPair(f, p1, width = 1, signed = false, outLaneBytes = 2)
      case 3 =>                                                                           // v128.load16x4_s
        loadExtPair(f, p1, width = 2, signed = true,  outLaneBytes = 4)
      case 4 =>                                                                           // v128.load16x4_u
        loadExtPair(f, p1, width = 2, signed = false, outLaneBytes = 4)
      case 5 =>                                                                           // v128.load32x2_s
        loadExtPair(f, p1, width = 4, signed = true,  outLaneBytes = 8)
      case 6 =>                                                                           // v128.load32x2_u
        loadExtPair(f, p1, width = 4, signed = false, outLaneBytes = 8)

      case 7 =>                                                                           // v128.load8_splat
        loadSplat(f, p1, width = 1)
      case 8 =>                                                                           // v128.load16_splat
        loadSplat(f, p1, width = 2)
      case 9 =>                                                                           // v128.load32_splat
        loadSplat(f, p1, width = 4)
      case 10 =>                                                                          // v128.load64_splat
        loadSplat(f, p1, width = 8)

      case 92 =>                                                                          // v128.load32_zero
        loadZero(f, p1, width = 4)
      case 93 =>                                                                          // v128.load64_zero
        loadZero(f, p1, width = 8)

      // === Chunk B — store ====================================================

      case 11 =>                                                                          // v128.store : 16-byte aligned store
        val memArg = readMemArgAt(f, p1)
        val mem    = memArgMemory(memArg)
        val bits   = popV128()
        val addr   = (popI32() & 0xffffffffL) + memArg.offset
        boundsCheck(mem, addr, 16)
        System.arraycopy(bits, 0, mem.data, addr.toInt, 16)

      // === Chunk C — shuffle + swizzle ========================================

      case 13 =>                                                                          // i8x16.shuffle : 16-byte laneidx immediate
        // Each immediate byte picks a source lane: c < 16 means lane c
        // of `a` (deeper on stack); c >= 16 means lane (c-16) of `b`
        // (top of stack). Indices are pre-validated < 32 by the
        // validator, so the dispatch below is exhaustive.
        val idx = body
        val ip  = p1
        val b   = popV128()
        val a   = popV128()
        val r   = new Array[Byte](16)
        var i   = 0
        while i < 16 do
          val c = idx(ip + i) & 0xff
          r(i) = if c < 16 then a(c) else b(c - 16)
          i += 1
        f.pc = p1 + 16
        valueStack += V128(r)

      case 14 =>                                                                          // i8x16.swizzle : dynamic shuffle
        // `s` (top) holds 16 lane indices; `v` (below) is the source.
        // Each result lane is `v[s[i]]` for `s[i] < 16`, else 0. Indices
        // ≥ 16 are *not* a trap — they just emit zero.
        val s = popV128()
        val v = popV128()
        val r = new Array[Byte](16)
        var i = 0
        while i < 16 do
          val si = s(i) & 0xff
          r(i) = if si < 16 then v(si) else 0
          i += 1
        f.pc = p1
        valueStack += V128(r)

      // === Chunk C — splats ===================================================

      case 15 =>                                                                          // i8x16.splat : broadcast low 8 bits to 16 lanes
        val v    = popI32().toByte
        val bits = new Array[Byte](16)
        var i    = 0
        while i < 16 do { bits(i) = v; i += 1 }
        f.pc = p1
        valueStack += V128(bits)

      case 16 =>                                                                          // i16x8.splat : broadcast low 16 bits LE
        splatNarrow(f, p1, popI32().toLong & 0xffffL, width = 2)

      case 17 =>                                                                          // i32x4.splat
        splatNarrow(f, p1, popI32().toLong & 0xffffffffL, width = 4)

      case 18 =>                                                                          // i64x2.splat
        splatNarrow(f, p1, popI64(), width = 8)

      case 19 =>                                                                          // f32x4.splat : raw bit pattern, no NaN canonicalisation
        splatNarrow(f, p1, jl.Float.floatToRawIntBits(popF32()).toLong & 0xffffffffL, width = 4)

      case 20 =>                                                                          // f64x2.splat
        splatNarrow(f, p1, jl.Double.doubleToRawLongBits(popF64()), width = 8)

      // === Chunk C — extract_lane =============================================
      //
      // Each variant reads a 1-byte lane index, pops the v128, and
      // pushes the scalar lane value. i8x16/i16x8 have `_s/_u` forms
      // that differ only in sign-extension; the wider shapes have a
      // single (signed-irrelevant) form.

      case 21 =>                                                                          // i8x16.extract_lane_s
        val lane = body(p1) & 0xff
        val v    = popV128()
        pushI32(v(lane).toInt)                                                            // Byte → Int is sign-extending
        f.pc = p1 + 1

      case 22 =>                                                                          // i8x16.extract_lane_u
        val lane = body(p1) & 0xff
        val v    = popV128()
        pushI32(v(lane) & 0xff)
        f.pc = p1 + 1

      case 24 =>                                                                          // i16x8.extract_lane_s
        val lane = body(p1) & 0xff
        val v    = popV128()
        val o    = lane * 2
        val raw  = (v(o) & 0xff) | ((v(o + 1) & 0xff) << 8)
        pushI32((raw << 16) >> 16)                                                        // sign-extend i16 → i32
        f.pc = p1 + 1

      case 25 =>                                                                          // i16x8.extract_lane_u
        val lane = body(p1) & 0xff
        val v    = popV128()
        val o    = lane * 2
        pushI32((v(o) & 0xff) | ((v(o + 1) & 0xff) << 8))
        f.pc = p1 + 1

      case 27 =>                                                                          // i32x4.extract_lane
        val lane = body(p1) & 0xff
        val v    = popV128()
        pushI32(readLaneI32(v, lane))
        f.pc = p1 + 1

      case 29 =>                                                                          // i64x2.extract_lane
        val lane = body(p1) & 0xff
        val v    = popV128()
        pushI64(readLaneI64(v, lane))
        f.pc = p1 + 1

      case 31 =>                                                                          // f32x4.extract_lane
        val lane = body(p1) & 0xff
        val v    = popV128()
        pushF32(jl.Float.intBitsToFloat(readLaneI32(v, lane)))
        f.pc = p1 + 1

      case 33 =>                                                                          // f64x2.extract_lane
        val lane = body(p1) & 0xff
        val v    = popV128()
        pushF64(jl.Double.longBitsToDouble(readLaneI64(v, lane)))
        f.pc = p1 + 1

      // === Chunk C — replace_lane =============================================
      //
      // Scalar is popped first (stack-top), then the v128 below. The
      // popped backing array MUST be cloned before mutation (per
      // popV128's contract — it returns the value's own backing store).

      case 23 =>                                                                          // i8x16.replace_lane
        val lane = body(p1) & 0xff
        val s    = popI32()
        val v    = popV128().clone
        v(lane)  = s.toByte
        f.pc = p1 + 1
        valueStack += V128(v)

      case 26 =>                                                                          // i16x8.replace_lane
        val lane = body(p1) & 0xff
        val s    = popI32()
        val v    = popV128().clone
        writeLaneI16(v, lane, s)
        f.pc = p1 + 1
        valueStack += V128(v)

      case 28 =>                                                                          // i32x4.replace_lane
        val lane = body(p1) & 0xff
        val s    = popI32()
        val v    = popV128().clone
        writeLaneI32(v, lane, s)
        f.pc = p1 + 1
        valueStack += V128(v)

      case 30 =>                                                                          // i64x2.replace_lane
        val lane = body(p1) & 0xff
        val s    = popI64()
        val v    = popV128().clone
        writeLaneI64(v, lane, s)
        f.pc = p1 + 1
        valueStack += V128(v)

      case 32 =>                                                                          // f32x4.replace_lane
        val lane = body(p1) & 0xff
        val raw  = jl.Float.floatToRawIntBits(popF32())
        val v    = popV128().clone
        writeLaneI32(v, lane, raw)
        f.pc = p1 + 1
        valueStack += V128(v)

      case 34 =>                                                                          // f64x2.replace_lane
        val lane = body(p1) & 0xff
        val raw  = jl.Double.doubleToRawLongBits(popF64())
        val v    = popV128().clone
        writeLaneI64(v, lane, raw)
        f.pc = p1 + 1
        valueStack += V128(v)

      // === Chunk D — integer arithmetic =======================================
      //
      // Every op is `[v128 v128] -> [v128]` (binary) or `[v128] -> [v128]`
      // (unary). No immediates. Sub-opcodes >= 0x80 are 2-byte LEBs in the
      // binary; the LEB decode at the top of stepFd already handles that.
      // The shape-aware un/bin-op helpers (below, near the lane helpers)
      // build a fresh 16-byte result; `_sat_s/_u` variants saturate at the
      // signed/unsigned lane bounds, everything else wraps mod 2^lane_width
      // (the `writeLaneIxx` byte truncation handles that for free).

      // --- i8x16 --------------------------------------------------------------

      case 0x60 =>                                                                        // i8x16.abs
        val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16UnOpS(a, x => if x < 0 then -x else x))

      case 0x61 =>                                                                        // i8x16.neg
        val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16UnOpS(a, x => -x))

      case 0x6E =>                                                                        // i8x16.add
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpS(a, b, (x, y) => x + y))

      case 0x6F =>                                                                        // i8x16.add_sat_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpS(a, b, (x, y) =>
          val s = x + y
          if s > 127 then 127 else if s < -128 then -128 else s))

      case 0x70 =>                                                                        // i8x16.add_sat_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpU(a, b, (x, y) =>
          val s = x + y
          if s > 255 then 255 else s))

      case 0x71 =>                                                                        // i8x16.sub
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpS(a, b, (x, y) => x - y))

      case 0x72 =>                                                                        // i8x16.sub_sat_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpS(a, b, (x, y) =>
          val s = x - y
          if s > 127 then 127 else if s < -128 then -128 else s))

      case 0x73 =>                                                                        // i8x16.sub_sat_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpU(a, b, (x, y) =>
          val s = x - y
          if s < 0 then 0 else s))

      case 0x7B =>                                                                        // i8x16.avgr_u : (a+b+1)/2 per lane
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpU(a, b, (x, y) => (x + y + 1) >>> 1))

      // --- i16x8 --------------------------------------------------------------

      case 0x80 =>                                                                        // i16x8.abs
        val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8UnOpS(a, x => if x < 0 then -x else x))

      case 0x81 =>                                                                        // i16x8.neg
        val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8UnOpS(a, x => -x))

      case 0x8E =>                                                                        // i16x8.add
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpS(a, b, (x, y) => x + y))

      case 0x8F =>                                                                        // i16x8.add_sat_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpS(a, b, (x, y) =>
          val s = x + y
          if s > 32767 then 32767 else if s < -32768 then -32768 else s))

      case 0x90 =>                                                                        // i16x8.add_sat_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpU(a, b, (x, y) =>
          val s = x + y
          if s > 65535 then 65535 else s))

      case 0x91 =>                                                                        // i16x8.sub
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpS(a, b, (x, y) => x - y))

      case 0x92 =>                                                                        // i16x8.sub_sat_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpS(a, b, (x, y) =>
          val s = x - y
          if s > 32767 then 32767 else if s < -32768 then -32768 else s))

      case 0x93 =>                                                                        // i16x8.sub_sat_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpU(a, b, (x, y) =>
          val s = x - y
          if s < 0 then 0 else s))

      case 0x95 =>                                                                        // i16x8.mul : low 16 bits of full-width int product
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpS(a, b, (x, y) => x * y))

      case 0x9B =>                                                                        // i16x8.avgr_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpU(a, b, (x, y) => (x + y + 1) >>> 1))

      // --- i32x4 --------------------------------------------------------------

      case 0xA0 =>                                                                        // i32x4.abs : abs(Int.MinValue) = Int.MinValue
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4UnOp(a, x => if x < 0 then -x else x))

      case 0xA1 =>                                                                        // i32x4.neg
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4UnOp(a, x => -x))

      case 0xAE =>                                                                        // i32x4.add
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4BinOp(a, b, (x, y) => x + y))

      case 0xB1 =>                                                                        // i32x4.sub
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4BinOp(a, b, (x, y) => x - y))

      case 0xB5 =>                                                                        // i32x4.mul
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4BinOp(a, b, (x, y) => x * y))

      // --- i64x2 --------------------------------------------------------------
      //
      // JS Long emulation cost — Scala.js represents Long as a pair of 32-bit
      // halves, so the lane mul here is a non-trivial JS function call. Still
      // correct; tests should pass on every backend.

      case 0xC0 =>                                                                        // i64x2.abs
        val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2UnOp(a, x => if x < 0L then -x else x))

      case 0xC1 =>                                                                        // i64x2.neg
        val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2UnOp(a, x => -x))

      case 0xCE =>                                                                        // i64x2.add
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2BinOp(a, b, (x, y) => x + y))

      case 0xD1 =>                                                                        // i64x2.sub
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2BinOp(a, b, (x, y) => x - y))

      case 0xD5 =>                                                                        // i64x2.mul
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2BinOp(a, b, (x, y) => x * y))

      // === Chunk E — shifts + min/max =========================================
      //
      // Shifts pop an i32 count first (top of stack), then a v128 base.
      // The shift count is taken `cnt mod lane_width` per the spec, so
      // `i8x16.shl(_, 8)` is the identity. `shl` is signedness-irrelevant
      // (the low bits of the result match either way); `shr_s` needs the
      // signed lane reader; `shr_u` needs the zero-extending lane reader
      // (`i8x16UnOpU` / `i16x8UnOpU`, added next to the chunk-D helpers).
      // For i32x4 / i64x2 the lane already fits a Java Int / Long, so
      // Java's `>>>` is logical for free.
      //
      // Min/max are per-lane signed (`_s`) or unsigned (`_u`) min or max.
      // i8x16 / i16x8 reuse the chunk-D `BinOpS` / `BinOpU` helpers; i32x4
      // has no zero-extending wide-lane reader (Int is already wide), so
      // the unsigned variants compare with `jl.Integer.compareUnsigned`
      // inside the lambda. i64x2 has no min/max — the SIMD spec excludes
      // them (i32x4 is the widest shape with min/max).

      // --- i8x16 --------------------------------------------------------------

      case 0x6B =>                                                                        // i8x16.shl
        val cnt = popI32() & 7
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i8x16UnOpS(a, x => x << cnt))

      case 0x6C =>                                                                        // i8x16.shr_s
        val cnt = popI32() & 7
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i8x16UnOpS(a, x => x >> cnt))

      case 0x6D =>                                                                        // i8x16.shr_u
        val cnt = popI32() & 7
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i8x16UnOpU(a, x => x >>> cnt))

      case 0x76 =>                                                                        // i8x16.min_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpS(a, b, (x, y) => if x < y then x else y))

      case 0x77 =>                                                                        // i8x16.min_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpU(a, b, (x, y) => if x < y then x else y))

      case 0x78 =>                                                                        // i8x16.max_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpS(a, b, (x, y) => if x > y then x else y))

      case 0x79 =>                                                                        // i8x16.max_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16BinOpU(a, b, (x, y) => if x > y then x else y))

      // --- i16x8 --------------------------------------------------------------

      case 0x8B =>                                                                        // i16x8.shl
        val cnt = popI32() & 15
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i16x8UnOpS(a, x => x << cnt))

      case 0x8C =>                                                                        // i16x8.shr_s
        val cnt = popI32() & 15
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i16x8UnOpS(a, x => x >> cnt))

      case 0x8D =>                                                                        // i16x8.shr_u
        val cnt = popI32() & 15
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i16x8UnOpU(a, x => x >>> cnt))

      case 0x96 =>                                                                        // i16x8.min_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpS(a, b, (x, y) => if x < y then x else y))

      case 0x97 =>                                                                        // i16x8.min_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpU(a, b, (x, y) => if x < y then x else y))

      case 0x98 =>                                                                        // i16x8.max_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpS(a, b, (x, y) => if x > y then x else y))

      case 0x99 =>                                                                        // i16x8.max_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpU(a, b, (x, y) => if x > y then x else y))

      // --- i32x4 --------------------------------------------------------------

      case 0xAB =>                                                                        // i32x4.shl
        val cnt = popI32() & 31
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i32x4UnOp(a, x => x << cnt))

      case 0xAC =>                                                                        // i32x4.shr_s
        val cnt = popI32() & 31
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i32x4UnOp(a, x => x >> cnt))

      case 0xAD =>                                                                        // i32x4.shr_u
        val cnt = popI32() & 31
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i32x4UnOp(a, x => x >>> cnt))

      case 0xB6 =>                                                                        // i32x4.min_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4BinOp(a, b, (x, y) => if x < y then x else y))

      case 0xB7 =>                                                                        // i32x4.min_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4BinOp(a, b, (x, y) =>
          if jl.Integer.compareUnsigned(x, y) < 0 then x else y))

      case 0xB8 =>                                                                        // i32x4.max_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4BinOp(a, b, (x, y) => if x > y then x else y))

      case 0xB9 =>                                                                        // i32x4.max_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4BinOp(a, b, (x, y) =>
          if jl.Integer.compareUnsigned(x, y) > 0 then x else y))

      // --- i64x2 (shifts only; no min/max in the SIMD spec) -------------------

      case 0xCB =>                                                                        // i64x2.shl
        val cnt = popI32() & 63
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i64x2UnOp(a, x => x << cnt))

      case 0xCC =>                                                                        // i64x2.shr_s
        val cnt = popI32() & 63
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i64x2UnOp(a, x => x >> cnt))

      case 0xCD =>                                                                        // i64x2.shr_u
        val cnt = popI32() & 63
        val a   = popV128()
        f.pc = p1
        valueStack += V128(i64x2UnOp(a, x => x >>> cnt))

      // === Chunk F — float arithmetic =========================================
      //
      // f32x4 / f64x2 per-lane unary + binary ops. Unary covers rounding
      // (ceil/floor/trunc/nearest) and IEEE absolute / negate / sqrt. Binary
      // covers add/sub/mul/div + min/max (NaN → canonical, matches scalar
      // f32.min/f64.min) + pmin/pmax (NaN propagates from operand a per the
      // wasm SIMD spec's `if b < a then b else a` form). abs/neg are
      // bit-twiddles to preserve NaN payloads exactly per IEEE-754, so they
      // route through the i32x4 / i64x2 lane helpers instead of float
      // arithmetic.

      // --- f32x4 unary --------------------------------------------------------

      case 0x67 =>                                                                        // f32x4.ceil
        val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4UnOp(a, v => jl.Math.ceil(v.toDouble).toFloat))

      case 0x68 =>                                                                        // f32x4.floor
        val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4UnOp(a, v => jl.Math.floor(v.toDouble).toFloat))

      case 0x69 =>                                                                        // f32x4.trunc — round toward zero
        val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4UnOp(a, v =>
          if jl.Float.isNaN(v) || jl.Float.isInfinite(v) then v
          else if v < 0.0f then jl.Math.ceil(v.toDouble).toFloat
          else jl.Math.floor(v.toDouble).toFloat))

      case 0x6A =>                                                                        // f32x4.nearest (round half to even)
        val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4UnOp(a, v => jl.Math.rint(v.toDouble).toFloat))

      case 0xE0 =>                                                                        // f32x4.abs — clear sign bit
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4UnOp(a, b => b & 0x7fffffff))

      case 0xE1 =>                                                                        // f32x4.neg — flip sign bit
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4UnOp(a, b => b ^ 0x80000000))

      case 0xE3 =>                                                                        // f32x4.sqrt
        val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4UnOp(a, v => jl.Math.sqrt(v.toDouble).toFloat))

      // --- f32x4 binary -------------------------------------------------------

      case 0xE4 =>                                                                        // f32x4.add
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => x + y))

      case 0xE5 =>                                                                        // f32x4.sub
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => x - y))

      case 0xE6 =>                                                                        // f32x4.mul
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => x * y))

      case 0xE7 =>                                                                        // f32x4.div
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => x / y))

      case 0xE8 =>                                                                        // f32x4.min — IEEE min; NaN-in → NaN-out; -0 < +0
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => jl.Math.min(x, y)))

      case 0xE9 =>                                                                        // f32x4.max
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => jl.Math.max(x, y)))

      case 0xEA =>                                                                        // f32x4.pmin — `if b < a then b else a` (NaN-involving compare → a)
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => if y < x then y else x))

      case 0xEB =>                                                                        // f32x4.pmax — `if a < b then b else a`
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => if x < y then y else x))

      // --- f64x2 unary --------------------------------------------------------

      case 0x74 =>                                                                        // f64x2.ceil
        val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2UnOp(a, v => jl.Math.ceil(v)))

      case 0x75 =>                                                                        // f64x2.floor
        val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2UnOp(a, v => jl.Math.floor(v)))

      case 0x7A =>                                                                        // f64x2.trunc
        val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2UnOp(a, v =>
          if jl.Double.isNaN(v) || jl.Double.isInfinite(v) then v
          else if v < 0.0 then jl.Math.ceil(v)
          else jl.Math.floor(v)))

      case 0x94 =>                                                                        // f64x2.nearest
        val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2UnOp(a, v => jl.Math.rint(v)))

      case 0xEC =>                                                                        // f64x2.abs — clear sign bit
        val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2UnOp(a, b => b & 0x7fffffffffffffffL))

      case 0xED =>                                                                        // f64x2.neg — flip sign bit
        val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2UnOp(a, b => b ^ 0x8000000000000000L))

      case 0xEF =>                                                                        // f64x2.sqrt
        val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2UnOp(a, v => jl.Math.sqrt(v)))

      // --- f64x2 binary -------------------------------------------------------

      case 0xF0 =>                                                                        // f64x2.add
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => x + y))

      case 0xF1 =>                                                                        // f64x2.sub
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => x - y))

      case 0xF2 =>                                                                        // f64x2.mul
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => x * y))

      case 0xF3 =>                                                                        // f64x2.div
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => x / y))

      case 0xF4 =>                                                                        // f64x2.min
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => jl.Math.min(x, y)))

      case 0xF5 =>                                                                        // f64x2.max
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => jl.Math.max(x, y)))

      case 0xF6 =>                                                                        // f64x2.pmin
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => if y < x then y else x))

      case 0xF7 =>                                                                        // f64x2.pmax
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => if x < y then y else x))

      case _ =>
        fail(WasmError.UnknownOpcode(0xfd))

  /** Read a memarg starting at byte position `pos` in the current body,
    * advance `f.pc` past it, and return the immediate. Used by stepFd
    * after the LEB sub-opcode has already been decoded. */
  private inline def readMemArgAt(f: Frame, pos: Int): MemArg =
    Interpreter.readMemArg(f.func.body, pos) match
      case Left(e) => fail(e)
      case Right((memArg, p)) =>
        f.pc = p
        memArg

  /** Splat load: read `width` bytes from memory, broadcast across the
    * resulting 16-byte vector (16/width copies). `width` ∈ {1,2,4,8}.
    * `memArgPos` is the byte position right after the LEB sub-opcode,
    * where the memarg immediate starts. */
  private def loadSplat(f: Frame, memArgPos: Int, width: Int): Unit =
    val memArg = readMemArgAt(f, memArgPos)
    val mem    = memArgMemory(memArg)
    val addr   = (popI32() & 0xffffffffL) + memArg.offset
    boundsCheck(mem, addr, width)
    val bits = new Array[Byte](16)
    val a    = addr.toInt
    var i    = 0
    while i < 16 do
      bits(i) = mem.data(a + (i % width))
      i += 1
    valueStack += V128(bits)

  /** Zero-extending lane load: read `width` bytes from memory into lane 0
    * of the result vector; the remaining 16 − width bytes are zero.
    * `width` ∈ {4, 8}. */
  private def loadZero(f: Frame, memArgPos: Int, width: Int): Unit =
    val memArg = readMemArgAt(f, memArgPos)
    val mem    = memArgMemory(memArg)
    val addr   = (popI32() & 0xffffffffL) + memArg.offset
    boundsCheck(mem, addr, width)
    val bits = new Array[Byte](16)
    System.arraycopy(mem.data, addr.toInt, bits, 0, width)
    valueStack += V128(bits)

  /** Phase 8.E.C splat helper for widths 2/4/8. The low `width` bytes of
    * `src` (little-endian) are written into lane 0 and then replicated
    * across the remaining `16/width - 1` lanes. Used by every splat
    * except i8x16 (which inlines the single-byte fast path). Takes `f`
    * explicitly (was using `frame.pc` before the trait split — passing
    * the frame is clearer and avoids depending on `frame` inlining
    * across the file boundary). */
  private def splatNarrow(f: Frame, p1: Int, src: Long, width: Int): Unit =
    val bits  = new Array[Byte](16)
    val lanes = 16 / width
    var lane  = 0
    while lane < lanes do
      val off = lane * width
      var k   = 0
      while k < width do
        bits(off + k) = ((src >>> (k * 8)) & 0xffL).toByte
        k += 1
      lane += 1
    f.pc = p1
    valueStack += V128(bits)

  /** Read 4 little-endian bytes from `v` at byte offset `lane * 4` as a
    * signed i32. Used by both `i32x4.extract_lane` and the raw-bit
    * surface of `f32x4.extract_lane`. */
  private inline def readLaneI32(v: Array[Byte], lane: Int): Int =
    val o = lane * 4
    (v(o)     & 0xff)        |
    ((v(o + 1) & 0xff) <<  8) |
    ((v(o + 2) & 0xff) << 16) |
    ((v(o + 3) & 0xff) << 24)

  /** Read 8 little-endian bytes from `v` at byte offset `lane * 8` as a
    * signed i64. Used by `i64x2.extract_lane` and the raw-bit surface
    * of `f64x2.extract_lane`. */
  private inline def readLaneI64(v: Array[Byte], lane: Int): Long =
    val o = lane * 8
    var k = 0
    var r = 0L
    while k < 8 do
      r |= (v(o + k).toLong & 0xffL) << (k * 8)
      k += 1
    r

  /** Write the low 16 bits of `value` into `v` at byte offset
    * `lane * 2`, little-endian. */
  private inline def writeLaneI16(v: Array[Byte], lane: Int, value: Int): Unit =
    val o = lane * 2
    v(o)     = (value & 0xff).toByte
    v(o + 1) = ((value >>> 8) & 0xff).toByte

  /** Write all 32 bits of `value` into `v` at byte offset `lane * 4`,
    * little-endian. Used by i32x4 + f32x4 replace_lane (the float form
    * passes the raw bit pattern). */
  private inline def writeLaneI32(v: Array[Byte], lane: Int, value: Int): Unit =
    val o = lane * 4
    v(o)     = (value & 0xff).toByte
    v(o + 1) = ((value >>> 8)  & 0xff).toByte
    v(o + 2) = ((value >>> 16) & 0xff).toByte
    v(o + 3) = ((value >>> 24) & 0xff).toByte

  /** Write all 64 bits of `value` into `v` at byte offset `lane * 8`,
    * little-endian. Used by i64x2 + f64x2 replace_lane. */
  private inline def writeLaneI64(v: Array[Byte], lane: Int, value: Long): Unit =
    val o = lane * 8
    var k = 0
    while k < 8 do
      v(o + k) = ((value >>> (k * 8)) & 0xffL).toByte
      k += 1

  // === Chunk D — shape-aware un/bin-op helpers ============================
  //
  // Each helper allocates a fresh 16-byte result, reads one or two source
  // values per lane in the requested sign-form, and writes the op's return
  // value back with the lane-shape's writer (which truncates to the lane
  // width — wrap mod 2^N comes for free). The `_S` / `_U` suffix only
  // matters for i8x16 and i16x8 (where lane width is narrower than Int);
  // i32x4 and i64x2 store the entire lane as Int / Long so no suffix.

  /** Sign-extending unary op on each i8 lane. */
  private def i8x16UnOpS(a: Array[Byte], op: Int => Int): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 16 do
      r(i) = op(a(i).toInt).toByte
      i += 1
    r

  /** Sign-extending binary op on each i8 lane. */
  private def i8x16BinOpS(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Int): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 16 do
      r(i) = op(a(i).toInt, b(i).toInt).toByte
      i += 1
    r

  /** Zero-extending binary op on each i8 lane (operands 0..255). */
  private def i8x16BinOpU(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Int): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 16 do
      r(i) = op(a(i) & 0xff, b(i) & 0xff).toByte
      i += 1
    r

  /** Zero-extending unary op on each i8 lane (operand 0..255). Used by
    * `i8x16.shr_u`, where the lane bits must be zero-extended before the
    * logical right shift; the signed reader would propagate the sign bit
    * into the high bits and give the wrong low-byte result. */
  private def i8x16UnOpU(a: Array[Byte], op: Int => Int): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 16 do
      r(i) = op(a(i) & 0xff).toByte
      i += 1
    r

  /** Sign-extending unary op on each i16 lane. */
  private def i16x8UnOpS(a: Array[Byte], op: Int => Int): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 8 do
      val raw = (a(ln * 2) & 0xff) | ((a(ln * 2 + 1) & 0xff) << 8)
      writeLaneI16(r, ln, op((raw << 16) >> 16))                                          // sign-extend i16 → i32
      ln += 1
    r

  /** Sign-extending binary op on each i16 lane. */
  private def i16x8BinOpS(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Int): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 8 do
      val ar = (a(ln * 2) & 0xff) | ((a(ln * 2 + 1) & 0xff) << 8)
      val br = (b(ln * 2) & 0xff) | ((b(ln * 2 + 1) & 0xff) << 8)
      writeLaneI16(r, ln, op((ar << 16) >> 16, (br << 16) >> 16))
      ln += 1
    r

  /** Zero-extending binary op on each i16 lane (operands 0..65535). */
  private def i16x8BinOpU(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Int): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 8 do
      val au = (a(ln * 2) & 0xff) | ((a(ln * 2 + 1) & 0xff) << 8)
      val bu = (b(ln * 2) & 0xff) | ((b(ln * 2 + 1) & 0xff) << 8)
      writeLaneI16(r, ln, op(au, bu))
      ln += 1
    r

  /** Zero-extending unary op on each i16 lane (operand 0..65535). Used by
    * `i16x8.shr_u` for the same reason `i8x16UnOpU` is needed. */
  private def i16x8UnOpU(a: Array[Byte], op: Int => Int): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 8 do
      val au = (a(ln * 2) & 0xff) | ((a(ln * 2 + 1) & 0xff) << 8)
      writeLaneI16(r, ln, op(au))
      ln += 1
    r

  /** Unary op on each i32 lane (lane already wide enough for Int). */
  private def i32x4UnOp(a: Array[Byte], op: Int => Int): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 4 do
      writeLaneI32(r, ln, op(readLaneI32(a, ln)))
      ln += 1
    r

  /** Binary op on each i32 lane. */
  private def i32x4BinOp(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Int): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 4 do
      writeLaneI32(r, ln, op(readLaneI32(a, ln), readLaneI32(b, ln)))
      ln += 1
    r

  /** Unary op on each i64 lane. */
  private def i64x2UnOp(a: Array[Byte], op: Long => Long): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 2 do
      writeLaneI64(r, ln, op(readLaneI64(a, ln)))
      ln += 1
    r

  /** Binary op on each i64 lane. */
  private def i64x2BinOp(a: Array[Byte], b: Array[Byte], op: (Long, Long) => Long): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 2 do
      writeLaneI64(r, ln, op(readLaneI64(a, ln), readLaneI64(b, ln)))
      ln += 1
    r

  // === Chunk F — float lane helpers =======================================
  //
  // Read each lane's raw bits, decode to Float/Double, apply the op, and
  // store the result back via `*ToRawIntBits` / `*ToRawLongBits` so NaN
  // payload bits round-trip unchanged. The wasm spec for SIMD float
  // arithmetic says "if any operand is NaN, the result is a NaN" — the
  // bit pattern is implementation-defined, same as for scalar f32/f64
  // (where this codebase also doesn't canonicalise). JVM `Float.NaN`
  // arithmetic already produces the canonical 0x7FC00000 pattern in
  // practice, and the raw-bits writers preserve whatever the JVM hands
  // back without disturbing it.
  //
  // For abs/neg we bit-twiddle the sign bit directly instead of routing
  // through Float arithmetic — IEEE-754 specifies these as bitwise
  // operations that preserve NaN payloads exactly, including signaling
  // NaNs that arithmetic could otherwise turn quiet.

  /** Unary op on each f32 lane (4 lanes, raw bits round-trip). */
  private def f32x4UnOp(a: Array[Byte], op: Float => Float): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 4 do
      val v = jl.Float.intBitsToFloat(readLaneI32(a, ln))
      writeLaneI32(r, ln, jl.Float.floatToRawIntBits(op(v)))
      ln += 1
    r

  /** Binary op on each f32 lane. */
  private def f32x4BinOp(a: Array[Byte], b: Array[Byte], op: (Float, Float) => Float): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 4 do
      val x = jl.Float.intBitsToFloat(readLaneI32(a, ln))
      val y = jl.Float.intBitsToFloat(readLaneI32(b, ln))
      writeLaneI32(r, ln, jl.Float.floatToRawIntBits(op(x, y)))
      ln += 1
    r

  /** Unary op on each f64 lane (2 lanes, raw bits round-trip). */
  private def f64x2UnOp(a: Array[Byte], op: Double => Double): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 2 do
      val v = jl.Double.longBitsToDouble(readLaneI64(a, ln))
      writeLaneI64(r, ln, jl.Double.doubleToRawLongBits(op(v)))
      ln += 1
    r

  /** Binary op on each f64 lane. */
  private def f64x2BinOp(a: Array[Byte], b: Array[Byte], op: (Double, Double) => Double): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 2 do
      val x = jl.Double.longBitsToDouble(readLaneI64(a, ln))
      val y = jl.Double.longBitsToDouble(readLaneI64(b, ln))
      writeLaneI64(r, ln, jl.Double.doubleToRawLongBits(op(x, y)))
      ln += 1
    r

  /** Sign/zero-extending pair load: read 8 bytes from memory, treat them as
    * 8/width source lanes, and widen each into a `outLaneBytes`-byte
    * destination lane. `width` ∈ {1,2,4}, `outLaneBytes = width * 2`. */
  private def loadExtPair(f: Frame, memArgPos: Int, width: Int, signed: Boolean, outLaneBytes: Int): Unit =
    val memArg = readMemArgAt(f, memArgPos)
    val mem    = memArgMemory(memArg)
    val addr   = (popI32() & 0xffffffffL) + memArg.offset
    boundsCheck(mem, addr, 8)
    val bits     = new Array[Byte](16)
    val a        = addr.toInt
    val laneCount = 8 / width
    var lane     = 0
    while lane < laneCount do
      // Read this lane's `width` bytes as a signed/unsigned integer, then
      // splat back out into `outLaneBytes` little-endian bytes.
      val srcOff = a + lane * width
      val v: Long =
        width match
          case 1 =>
            val b = mem.data(srcOff) & 0xff
            if signed then (b << 24 >> 24).toLong else b.toLong
          case 2 =>
            val raw = (mem.data(srcOff) & 0xff) | ((mem.data(srcOff + 1) & 0xff) << 8)
            if signed then ((raw << 16) >> 16).toLong else (raw & 0xffffL)
          case 4 =>
            val raw =
              (mem.data(srcOff)     & 0xff)        |
              ((mem.data(srcOff + 1) & 0xff) <<  8) |
              ((mem.data(srcOff + 2) & 0xff) << 16) |
              ((mem.data(srcOff + 3) & 0xff) << 24)
            if signed then raw.toLong else (raw.toLong & 0xffffffffL)
          case _ =>
            // Defensive: only 1/2/4 are valid pair-load widths.
            fail(WasmError.InvalidModule(s"invalid pair-load width $width"))
      val dstOff = lane * outLaneBytes
      var i      = 0
      while i < outLaneBytes do
        bits(dstOff + i) = ((v >>> (i * 8)) & 0xff).toByte
        i += 1
      lane += 1
    valueStack += V128(bits)


/** Static helper called by [[Interpreter.skipImmediates]] for the `0xFD`
  * arm. Returns the byte position right after the 0xFD opcode's
  * immediate (where present), or an error for truncated immediates and
  * unknown sub-opcodes.
  *
  * `sub` is the LEB-decoded sub-opcode, `p1` is the byte position right
  * after that sub-opcode (where memargs / lane indices / raw literals
  * begin); the SimdDispatch step path uses the same convention. */
private[wasm] object SimdDispatch:

  def skipSimdImmediates(
      body: Array[Byte],
      pc:   Int,
      sub:  Int,
      p1:   Int,
  ): Either[WasmError, Int] =
    sub match
      case 12 =>                                                                  // v128.const : 16 raw bytes
        val end = p1 + 16
        if end > body.length then
          Left(WasmError.InvalidModule(s"truncated v128.const literal at $pc"))
        else Right(end)

      // Chunk B — every load + store carries a memarg.
      case 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 92 | 93 =>
        Interpreter.readMemArg(body, p1) match
          case Left(e)             => Left(e)
          case Right((_, pAfter))  => Right(pAfter)

      // Chunk C — `i8x16.shuffle` carries a 16-byte laneidx imm.
      case 13 =>
        val end = p1 + 16
        if end > body.length then
          Left(WasmError.InvalidModule(s"truncated i8x16.shuffle immediate at $pc"))
        else Right(end)

      // Chunk C — `i8x16.swizzle` and `*.splat` have no operand
      // past the sub-opcode.
      case 14 | 15 | 16 | 17 | 18 | 19 | 20 =>
        Right(p1)

      // Chunk C — `*.extract_lane` (signed/unsigned variants)
      // and `*.replace_lane` each carry a 1-byte lane index.
      case 21 | 22 | 23 | 24 | 25 | 26 | 27 | 28 | 29 | 30 | 31 | 32 | 33 | 34 =>
        if p1 + 1 > body.length then
          Left(WasmError.InvalidModule(s"truncated lane immediate at $pc"))
        else Right(p1 + 1)

      // Chunk D — integer arithmetic (all unary or binary on v128
      // → v128, no immediate past the sub-opcode).
      case 0x60 | 0x61 |                                                          // i8x16  abs / neg
           0x6E | 0x6F | 0x70 | 0x71 | 0x72 | 0x73 | 0x7B |                       // i8x16  add/sub/sat/avgr
           0x80 | 0x81 |                                                          // i16x8  abs / neg
           0x8E | 0x8F | 0x90 | 0x91 | 0x92 | 0x93 | 0x95 | 0x9B |                // i16x8  add/sub/sat/mul/avgr
           0xA0 | 0xA1 | 0xAE | 0xB1 | 0xB5 |                                     // i32x4  abs/neg/add/sub/mul
           0xC0 | 0xC1 | 0xCE | 0xD1 | 0xD5 =>                                    // i64x2  abs/neg/add/sub/mul
        Right(p1)

      // Chunk E — shifts + min/max. Shifts read the i32 count from the
      // operand stack (not an immediate), min/max are v128×v128 → v128.
      // Either way, nothing follows the sub-opcode byte.
      case 0x6B | 0x6C | 0x6D |                                                   // i8x16  shl / shr_s / shr_u
           0x76 | 0x77 | 0x78 | 0x79 |                                            // i8x16  min/max  _s/_u
           0x8B | 0x8C | 0x8D |                                                   // i16x8  shl / shr_s / shr_u
           0x96 | 0x97 | 0x98 | 0x99 |                                            // i16x8  min/max  _s/_u
           0xAB | 0xAC | 0xAD |                                                   // i32x4  shl / shr_s / shr_u
           0xB6 | 0xB7 | 0xB8 | 0xB9 |                                            // i32x4  min/max  _s/_u
           0xCB | 0xCC | 0xCD =>                                                  // i64x2  shl / shr_s / shr_u
        Right(p1)

      // Chunk F — float arithmetic (no immediate past the sub-opcode).
      case 0x67 | 0x68 | 0x69 | 0x6A |                                            // f32x4  ceil/floor/trunc/nearest
           0x74 | 0x75 | 0x7A | 0x94 |                                            // f64x2  ceil/floor/trunc/nearest
           0xE0 | 0xE1 | 0xE3 |                                                   // f32x4  abs/neg/sqrt
           0xE4 | 0xE5 | 0xE6 | 0xE7 |                                            // f32x4  add/sub/mul/div
           0xE8 | 0xE9 | 0xEA | 0xEB |                                            // f32x4  min/max/pmin/pmax
           0xEC | 0xED | 0xEF |                                                   // f64x2  abs/neg/sqrt
           0xF0 | 0xF1 | 0xF2 | 0xF3 |                                            // f64x2  add/sub/mul/div
           0xF4 | 0xF5 | 0xF6 | 0xF7 =>                                           // f64x2  min/max/pmin/pmax
        Right(p1)

      case _ => Left(WasmError.UnknownOpcode(0xfd))
