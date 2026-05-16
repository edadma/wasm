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
  *   - G.1: 15 bitwise + reduction ops (not/and/andnot/or/xor/bitselect
  *     — subs 0x4D..0x52; any_true 0x53; *.all_true 0x63/0x83/0xA3/0xC3;
  *     *.bitmask 0x64/0x84/0xA4/0xC4).
  *   - G.2: 48 comparison ops (eq/ne/lt/gt/le/ge across all four int
  *     shapes — signed + unsigned for i8x16/i16x8/i32x4, signed-only
  *     for i64x2 — and both float shapes. Subs 0x23..0x40 (i8x16/i16x8/i32x4),
  *     0xD6..0xDB (i64x2 signed), 0x41..0x4C (f32x4/f64x2)).
  *   - H: 42 narrow / extend (widen) / extadd_pairwise / extmul / float-int
  *     conv / demote / promote. Saturating narrow (4 subs 0x65/0x66/0x85/0x86);
  *     extend low/high _s/_u across three shape pairs (12 subs 0x87..0x8A,
  *     0xA7..0xAA, 0xC7..0xCA); pairwise widening sum (4 subs 0x7C..0x7F);
  *     widening multiply across three shape pairs (12 subs 0x9C..0x9F,
  *     0xBC..0xBF, 0xDC..0xDF); float↔int conv with NaN/range saturation
  *     and the `_zero` half-fill suffix (8 subs 0xF8..0xFF); f32 ↔ f64
  *     demote/promote with the same half-fill semantics (subs 0x5E/0x5F).
  *   - I: 9 special ops. `i32x4.dot_i16x8_s` (sub 0xBA), the wider-lane
  *     pairwise multiply-add. The 8 `v128.load{8,16,32,64}_lane` /
  *     `v128.store{8,16,32,64}_lane` ops (subs 0x54..0x5B) — partial
  *     memory access with both a memarg and a 1-byte lane immediate.
  *
  * Phase 8.E SIMD is complete with chunk I. Unknown sub-opcodes fall
  * through to `UnknownOpcode(0xfd)`.
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

      case 0x62 =>                                                                        // i8x16.popcnt — bits set per byte lane
        val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16UnOpU(a, x => java.lang.Integer.bitCount(x & 0xff)))

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

      case 0x82 =>                                                                        // i16x8.q15mulr_sat_s — saturating signed Q15 mulr per i16 lane
        // (a * b + 0x4000) >> 15, saturated to [-32768, 32767]. -32768 * -32768
        // would otherwise produce 32768 — the only value that needs the clamp.
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8BinOpS(a, b, (x, y) =>
          val p = (x * y + 0x4000) >> 15
          if p > 32767 then 32767 else if p < -32768 then -32768 else p))

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

      // === Chunk G.1 — bitwise (6) + reductions (9) ===========================
      //
      // Bitwise ops act on the raw 16 bytes regardless of lane shape — five
      // pure byte-level operations (`not`, `and`, `andnot`, `or`, `xor`) plus
      // `bitselect` which takes 3 operands `(a, b, c)` and produces
      // `(a AND c) OR (b AND NOT c)` — c is the selector mask.
      //
      // Reductions collapse a v128 down to an i32 result:
      // `any_true` checks the whole vector for any set bit; `*.all_true` is
      // shape-aware (every lane non-zero); `*.bitmask` packs the MSB of each
      // lane into bit position lane_index of the i32 result.

      // --- bitwise -----------------------------------------------------------

      case 0x4D =>                                                                        // v128.not — bitwise complement of all 16 bytes
        val a = popV128()
        f.pc = p1
        val r = new Array[Byte](16); var i = 0
        while i < 16 do
          r(i) = (~a(i)).toByte
          i += 1
        valueStack += V128(r)

      case 0x4E =>                                                                        // v128.and
        val b = popV128(); val a = popV128()
        f.pc = p1
        val r = new Array[Byte](16); var i = 0
        while i < 16 do
          r(i) = (a(i) & b(i)).toByte
          i += 1
        valueStack += V128(r)

      case 0x4F =>                                                                        // v128.andnot — a AND (NOT b)
        val b = popV128(); val a = popV128()
        f.pc = p1
        val r = new Array[Byte](16); var i = 0
        while i < 16 do
          r(i) = (a(i) & ~b(i)).toByte
          i += 1
        valueStack += V128(r)

      case 0x50 =>                                                                        // v128.or
        val b = popV128(); val a = popV128()
        f.pc = p1
        val r = new Array[Byte](16); var i = 0
        while i < 16 do
          r(i) = (a(i) | b(i)).toByte
          i += 1
        valueStack += V128(r)

      case 0x51 =>                                                                        // v128.xor
        val b = popV128(); val a = popV128()
        f.pc = p1
        val r = new Array[Byte](16); var i = 0
        while i < 16 do
          r(i) = (a(i) ^ b(i)).toByte
          i += 1
        valueStack += V128(r)

      case 0x52 =>                                                                        // v128.bitselect — (a AND c) OR (b AND NOT c); 3 v128 operands
        val c = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        val r = new Array[Byte](16); var i = 0
        while i < 16 do
          r(i) = ((a(i) & c(i)) | (b(i) & ~c(i))).toByte
          i += 1
        valueStack += V128(r)

      // --- reductions --------------------------------------------------------

      case 0x53 =>                                                                        // v128.any_true — any byte non-zero → 1, else 0
        val a = popV128()
        f.pc = p1
        var any = 0; var i = 0
        while i < 16 do
          if a(i) != 0 then any = 1
          i += 1
        pushI32(any)

      case 0x63 =>                                                                        // i8x16.all_true — every byte non-zero
        val a = popV128()
        f.pc = p1
        var all = 1; var i = 0
        while i < 16 do
          if a(i) == 0 then all = 0
          i += 1
        pushI32(all)

      case 0x83 =>                                                                        // i16x8.all_true — every i16 lane non-zero
        val a = popV128()
        f.pc = p1
        var all = 1; var ln = 0
        while ln < 8 do
          val off = ln * 2
          if (a(off) == 0) && (a(off + 1) == 0) then all = 0
          ln += 1
        pushI32(all)

      case 0xA3 =>                                                                        // i32x4.all_true — every i32 lane non-zero
        val a = popV128()
        f.pc = p1
        var all = 1; var ln = 0
        while ln < 4 do
          if readLaneI32(a, ln) == 0 then all = 0
          ln += 1
        pushI32(all)

      case 0xC3 =>                                                                        // i64x2.all_true — every i64 lane non-zero
        val a = popV128()
        f.pc = p1
        var all = 1; var ln = 0
        while ln < 2 do
          if readLaneI64(a, ln) == 0L then all = 0
          ln += 1
        pushI32(all)

      case 0x64 =>                                                                        // i8x16.bitmask — top bit of each byte → 16-bit mask in i32
        val a = popV128()
        f.pc = p1
        var mask = 0; var i = 0
        while i < 16 do
          if (a(i) & 0x80) != 0 then mask |= (1 << i)
          i += 1
        pushI32(mask)

      case 0x84 =>                                                                        // i16x8.bitmask — sign byte is the high (LE) byte of each i16 lane
        val a = popV128()
        f.pc = p1
        var mask = 0; var ln = 0
        while ln < 8 do
          if (a(ln * 2 + 1) & 0x80) != 0 then mask |= (1 << ln)
          ln += 1
        pushI32(mask)

      case 0xA4 =>                                                                        // i32x4.bitmask
        val a = popV128()
        f.pc = p1
        var mask = 0; var ln = 0
        while ln < 4 do
          if (readLaneI32(a, ln) & 0x80000000) != 0 then mask |= (1 << ln)
          ln += 1
        pushI32(mask)

      case 0xC4 =>                                                                        // i64x2.bitmask
        val a = popV128()
        f.pc = p1
        var mask = 0; var ln = 0
        while ln < 2 do
          if (readLaneI64(a, ln) & 0x8000000000000000L) != 0L then mask |= (1 << ln)
          ln += 1
        pushI32(mask)

      // === Chunk G.2 — Comparisons (48 ops) ===================================
      //
      // Every compare op pops two v128s, applies the per-lane predicate, and
      // writes -1 (all bits 1) for true / 0 for false into each output lane.
      // Validator already enforces `binop(V128, V128, V128)` for the whole
      // group; lane width + sign-form are reified in the helper choice here.
      //
      // For i32x4 a single `i32x4Cmp` helper covers both forms — the `_u`
      // sites pass `Integer.compareUnsigned(...)` predicates, mirroring how
      // `i32x4.min_u` / `max_u` already invoke `i32x4BinOp`.

      // --- i8x16 (0x23..0x2C) ------------------------------------------------

      case 0x23 =>                                                                        // i8x16.eq
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpS(a, b, (x, y) => x == y))

      case 0x24 =>                                                                        // i8x16.ne
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpS(a, b, (x, y) => x != y))

      case 0x25 =>                                                                        // i8x16.lt_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpS(a, b, (x, y) => x < y))

      case 0x26 =>                                                                        // i8x16.lt_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpU(a, b, (x, y) => x < y))

      case 0x27 =>                                                                        // i8x16.gt_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpS(a, b, (x, y) => x > y))

      case 0x28 =>                                                                        // i8x16.gt_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpU(a, b, (x, y) => x > y))

      case 0x29 =>                                                                        // i8x16.le_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpS(a, b, (x, y) => x <= y))

      case 0x2A =>                                                                        // i8x16.le_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpU(a, b, (x, y) => x <= y))

      case 0x2B =>                                                                        // i8x16.ge_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpS(a, b, (x, y) => x >= y))

      case 0x2C =>                                                                        // i8x16.ge_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16CmpU(a, b, (x, y) => x >= y))

      // --- i16x8 (0x2D..0x36) -----------------------------------------------

      case 0x2D =>                                                                        // i16x8.eq
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpS(a, b, (x, y) => x == y))

      case 0x2E =>                                                                        // i16x8.ne
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpS(a, b, (x, y) => x != y))

      case 0x2F =>                                                                        // i16x8.lt_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpS(a, b, (x, y) => x < y))

      case 0x30 =>                                                                        // i16x8.lt_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpU(a, b, (x, y) => x < y))

      case 0x31 =>                                                                        // i16x8.gt_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpS(a, b, (x, y) => x > y))

      case 0x32 =>                                                                        // i16x8.gt_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpU(a, b, (x, y) => x > y))

      case 0x33 =>                                                                        // i16x8.le_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpS(a, b, (x, y) => x <= y))

      case 0x34 =>                                                                        // i16x8.le_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpU(a, b, (x, y) => x <= y))

      case 0x35 =>                                                                        // i16x8.ge_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpS(a, b, (x, y) => x >= y))

      case 0x36 =>                                                                        // i16x8.ge_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8CmpU(a, b, (x, y) => x >= y))

      // --- i32x4 (0x37..0x40) -----------------------------------------------

      case 0x37 =>                                                                        // i32x4.eq
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => x == y))

      case 0x38 =>                                                                        // i32x4.ne
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => x != y))

      case 0x39 =>                                                                        // i32x4.lt_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => x < y))

      case 0x3A =>                                                                        // i32x4.lt_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => jl.Integer.compareUnsigned(x, y) < 0))

      case 0x3B =>                                                                        // i32x4.gt_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => x > y))

      case 0x3C =>                                                                        // i32x4.gt_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => jl.Integer.compareUnsigned(x, y) > 0))

      case 0x3D =>                                                                        // i32x4.le_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => x <= y))

      case 0x3E =>                                                                        // i32x4.le_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => jl.Integer.compareUnsigned(x, y) <= 0))

      case 0x3F =>                                                                        // i32x4.ge_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => x >= y))

      case 0x40 =>                                                                        // i32x4.ge_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4Cmp(a, b, (x, y) => jl.Integer.compareUnsigned(x, y) >= 0))

      // --- i64x2 (0xD6..0xDB) — signed-only per spec ------------------------

      case 0xD6 =>                                                                        // i64x2.eq
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2Cmp(a, b, (x, y) => x == y))

      case 0xD7 =>                                                                        // i64x2.ne
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2Cmp(a, b, (x, y) => x != y))

      case 0xD8 =>                                                                        // i64x2.lt_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2Cmp(a, b, (x, y) => x < y))

      case 0xD9 =>                                                                        // i64x2.gt_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2Cmp(a, b, (x, y) => x > y))

      case 0xDA =>                                                                        // i64x2.le_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2Cmp(a, b, (x, y) => x <= y))

      case 0xDB =>                                                                        // i64x2.ge_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2Cmp(a, b, (x, y) => x >= y))

      // --- f32x4 (0x41..0x46) — IEEE: NaN-involving → false (true only ne) --

      case 0x41 =>                                                                        // f32x4.eq
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4Cmp(a, b, (x, y) => x == y))

      case 0x42 =>                                                                        // f32x4.ne
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4Cmp(a, b, (x, y) => x != y))

      case 0x43 =>                                                                        // f32x4.lt
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4Cmp(a, b, (x, y) => x < y))

      case 0x44 =>                                                                        // f32x4.gt
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4Cmp(a, b, (x, y) => x > y))

      case 0x45 =>                                                                        // f32x4.le
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4Cmp(a, b, (x, y) => x <= y))

      case 0x46 =>                                                                        // f32x4.ge
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4Cmp(a, b, (x, y) => x >= y))

      // --- f64x2 (0x47..0x4C) ----------------------------------------------

      case 0x47 =>                                                                        // f64x2.eq
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2Cmp(a, b, (x, y) => x == y))

      case 0x48 =>                                                                        // f64x2.ne
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2Cmp(a, b, (x, y) => x != y))

      case 0x49 =>                                                                        // f64x2.lt
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2Cmp(a, b, (x, y) => x < y))

      case 0x4A =>                                                                        // f64x2.gt
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2Cmp(a, b, (x, y) => x > y))

      case 0x4B =>                                                                        // f64x2.le
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2Cmp(a, b, (x, y) => x <= y))

      case 0x4C =>                                                                        // f64x2.ge
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2Cmp(a, b, (x, y) => x >= y))

      // === Chunk H — narrow / extend / extadd_pairwise / extmul + conv ====
      //
      // Mechanically grouped by op family. All ops have no immediate past
      // the sub-opcode; narrow + extmul pop two v128 operands, everything
      // else pops one.

      // --- narrow (0x65/0x66/0x85/0x86) ----------------------------------

      case 0x65 =>                                                                        // i8x16.narrow_i16x8_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16NarrowI16x8S(a, b))

      case 0x66 =>                                                                        // i8x16.narrow_i16x8_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i8x16NarrowI16x8U(a, b))

      case 0x85 =>                                                                        // i16x8.narrow_i32x4_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8NarrowI32x4S(a, b))

      case 0x86 =>                                                                        // i16x8.narrow_i32x4_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8NarrowI32x4U(a, b))

      // --- extend (= widen) — 12 ops -------------------------------------

      case 0x87 =>                                                                        // i16x8.extend_low_i8x16_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtendI8x16(a, high = false, signed = true))

      case 0x88 =>                                                                        // i16x8.extend_high_i8x16_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtendI8x16(a, high = true,  signed = true))

      case 0x89 =>                                                                        // i16x8.extend_low_i8x16_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtendI8x16(a, high = false, signed = false))

      case 0x8A =>                                                                        // i16x8.extend_high_i8x16_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtendI8x16(a, high = true,  signed = false))

      case 0xA7 =>                                                                        // i32x4.extend_low_i16x8_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtendI16x8(a, high = false, signed = true))

      case 0xA8 =>                                                                        // i32x4.extend_high_i16x8_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtendI16x8(a, high = true,  signed = true))

      case 0xA9 =>                                                                        // i32x4.extend_low_i16x8_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtendI16x8(a, high = false, signed = false))

      case 0xAA =>                                                                        // i32x4.extend_high_i16x8_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtendI16x8(a, high = true,  signed = false))

      case 0xC7 =>                                                                        // i64x2.extend_low_i32x4_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2ExtendI32x4(a, high = false, signed = true))

      case 0xC8 =>                                                                        // i64x2.extend_high_i32x4_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2ExtendI32x4(a, high = true,  signed = true))

      case 0xC9 =>                                                                        // i64x2.extend_low_i32x4_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2ExtendI32x4(a, high = false, signed = false))

      case 0xCA =>                                                                        // i64x2.extend_high_i32x4_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2ExtendI32x4(a, high = true,  signed = false))

      // --- extadd_pairwise (0x7C..0x7F) ----------------------------------

      case 0x7C =>                                                                        // i16x8.extadd_pairwise_i8x16_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtAddPairwiseI8x16(a, signed = true))

      case 0x7D =>                                                                        // i16x8.extadd_pairwise_i8x16_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtAddPairwiseI8x16(a, signed = false))

      case 0x7E =>                                                                        // i32x4.extadd_pairwise_i16x8_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtAddPairwiseI16x8(a, signed = true))

      case 0x7F =>                                                                        // i32x4.extadd_pairwise_i16x8_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtAddPairwiseI16x8(a, signed = false))

      // --- extmul — 12 ops -----------------------------------------------

      case 0x9C =>                                                                        // i16x8.extmul_low_i8x16_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtMulI8x16(a, b, high = false, signed = true))

      case 0x9D =>                                                                        // i16x8.extmul_high_i8x16_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtMulI8x16(a, b, high = true,  signed = true))

      case 0x9E =>                                                                        // i16x8.extmul_low_i8x16_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtMulI8x16(a, b, high = false, signed = false))

      case 0x9F =>                                                                        // i16x8.extmul_high_i8x16_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i16x8ExtMulI8x16(a, b, high = true,  signed = false))

      case 0xBC =>                                                                        // i32x4.extmul_low_i16x8_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtMulI16x8(a, b, high = false, signed = true))

      case 0xBD =>                                                                        // i32x4.extmul_high_i16x8_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtMulI16x8(a, b, high = true,  signed = true))

      case 0xBE =>                                                                        // i32x4.extmul_low_i16x8_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtMulI16x8(a, b, high = false, signed = false))

      case 0xBF =>                                                                        // i32x4.extmul_high_i16x8_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4ExtMulI16x8(a, b, high = true,  signed = false))

      case 0xDC =>                                                                        // i64x2.extmul_low_i32x4_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2ExtMulI32x4(a, b, high = false, signed = true))

      case 0xDD =>                                                                        // i64x2.extmul_high_i32x4_s
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2ExtMulI32x4(a, b, high = true,  signed = true))

      case 0xDE =>                                                                        // i64x2.extmul_low_i32x4_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2ExtMulI32x4(a, b, high = false, signed = false))

      case 0xDF =>                                                                        // i64x2.extmul_high_i32x4_u
        val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(i64x2ExtMulI32x4(a, b, high = true,  signed = false))

      // --- demote / promote (0x5E/0x5F) ----------------------------------

      case 0x5E =>                                                                        // f32x4.demote_f64x2_zero
        val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4DemoteF64x2Zero(a))

      case 0x5F =>                                                                        // f64x2.promote_low_f32x4
        val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2PromoteLowF32x4(a))

      // --- trunc_sat / convert (0xF8..0xFF) ------------------------------

      case 0xF8 =>                                                                        // i32x4.trunc_sat_f32x4_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4TruncSatF32x4S(a))

      case 0xF9 =>                                                                        // i32x4.trunc_sat_f32x4_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4TruncSatF32x4U(a))

      case 0xFA =>                                                                        // f32x4.convert_i32x4_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4ConvertI32x4S(a))

      case 0xFB =>                                                                        // f32x4.convert_i32x4_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4ConvertI32x4U(a))

      case 0xFC =>                                                                        // i32x4.trunc_sat_f64x2_s_zero
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4TruncSatF64x2SZero(a))

      case 0xFD =>                                                                        // i32x4.trunc_sat_f64x2_u_zero
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4TruncSatF64x2UZero(a))

      case 0xFE =>                                                                        // f64x2.convert_low_i32x4_s
        val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2ConvertLowI32x4S(a))

      case 0xFF =>                                                                        // f64x2.convert_low_i32x4_u
        val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2ConvertLowI32x4U(a))

      // === Chunk I — dot product + load_lane / store_lane ====================

      case 0xBA =>                                                                        // i32x4.dot_i16x8_s
        val b = popV128()
        val a = popV128()
        f.pc = p1
        valueStack += V128(i32x4DotI16x8S(a, b))

      case 0x54 => loadLane(f, p1, width = 1)                                             // v128.load8_lane
      case 0x55 => loadLane(f, p1, width = 2)                                             // v128.load16_lane
      case 0x56 => loadLane(f, p1, width = 4)                                             // v128.load32_lane
      case 0x57 => loadLane(f, p1, width = 8)                                             // v128.load64_lane

      case 0x58 => storeLane(f, p1, width = 1)                                            // v128.store8_lane
      case 0x59 => storeLane(f, p1, width = 2)                                            // v128.store16_lane
      case 0x5A => storeLane(f, p1, width = 4)                                            // v128.store32_lane
      case 0x5B => storeLane(f, p1, width = 8)                                            // v128.store64_lane

      // === Chunk J — Relaxed SIMD (extracted) =================================
      //
      // 20 sub-opcodes (0x100..0x113); extracted into [[stepFdRelaxed]] to
      // keep stepFd under the JVM's 64KB method-size ceiling.
      case s if s >= 0x100 && s <= 0x113 =>
        stepFdRelaxed(f, sub, p1)

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

  // === Chunk G.2 — compare lane helpers ===================================
  //
  // Wasm SIMD compare ops produce all-1s on true / all-0s on false per lane —
  // same width as the input shape. These helpers thread a `Boolean` lambda
  // through the existing readers (sign-extended for `_s`, zero-extended for
  // `_u`) and write `-1` / `0` into each output lane. For i32x4 a single
  // helper covers both forms — the call sites pass `_<_` for signed and
  // `Integer.compareUnsigned(_, _) < 0` for unsigned, matching how
  // `i32x4.min_u` / `max_u` already invoke `i32x4BinOp`.

  /** Sign-extending compare on each i8 lane. */
  private def i8x16CmpS(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Boolean): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 16 do
      r(i) = (if op(a(i).toInt, b(i).toInt) then -1 else 0).toByte
      i += 1
    r

  /** Zero-extending compare on each i8 lane (operands 0..255). */
  private def i8x16CmpU(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Boolean): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 16 do
      r(i) = (if op(a(i) & 0xff, b(i) & 0xff) then -1 else 0).toByte
      i += 1
    r

  /** Sign-extending compare on each i16 lane. Result lane is two bytes —
    * `-1` writes `0xFF 0xFF` (Short.MinValue's bit pattern is fine for
    * `0`/`-1` here because we're treating the whole lane as a bitmask). */
  private def i16x8CmpS(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Boolean): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 8 do
      val ar = (a(ln * 2) & 0xff) | ((a(ln * 2 + 1) & 0xff) << 8)
      val br = (b(ln * 2) & 0xff) | ((b(ln * 2 + 1) & 0xff) << 8)
      writeLaneI16(r, ln, if op((ar << 16) >> 16, (br << 16) >> 16) then -1 else 0)
      ln += 1
    r

  /** Zero-extending compare on each i16 lane (operands 0..65535). */
  private def i16x8CmpU(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Boolean): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 8 do
      val au = (a(ln * 2) & 0xff) | ((a(ln * 2 + 1) & 0xff) << 8)
      val bu = (b(ln * 2) & 0xff) | ((b(ln * 2 + 1) & 0xff) << 8)
      writeLaneI16(r, ln, if op(au, bu) then -1 else 0)
      ln += 1
    r

  /** Compare on each i32 lane. Signed call sites pass `_<_` etc.; unsigned
    * sites pass `Integer.compareUnsigned(_, _) < 0` etc. */
  private def i32x4Cmp(a: Array[Byte], b: Array[Byte], op: (Int, Int) => Boolean): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 4 do
      writeLaneI32(r, ln, if op(readLaneI32(a, ln), readLaneI32(b, ln)) then -1 else 0)
      ln += 1
    r

  /** Compare on each i64 lane. Wasm spec only defines signed i64x2 compares
    * (no `_u` forms) — Scala's `<` / `>` / `==` on `Long` is already signed.
    * Scala.js emulates `Long` as a pair of `Int`s, but the comparison
    * operators route through `java.lang.Long.compare`, which is correct
    * across all three platforms. */
  private def i64x2Cmp(a: Array[Byte], b: Array[Byte], op: (Long, Long) => Boolean): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 2 do
      writeLaneI64(r, ln, if op(readLaneI64(a, ln), readLaneI64(b, ln)) then -1L else 0L)
      ln += 1
    r

  /** Compare on each f32 lane. IEEE-754 semantics: any NaN operand makes
    * eq/lt/gt/le/ge false and ne true. Scala's `==`/`<` on `Float` already
    * match this on every backend. The all-1s lane (`-1` Int) is written as
    * a raw int into the lane's 4 bytes — no `*ToRawIntBits` round-trip
    * needed because we're producing a mask, not a float. */
  private def f32x4Cmp(a: Array[Byte], b: Array[Byte], op: (Float, Float) => Boolean): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 4 do
      val x = jl.Float.intBitsToFloat(readLaneI32(a, ln))
      val y = jl.Float.intBitsToFloat(readLaneI32(b, ln))
      writeLaneI32(r, ln, if op(x, y) then -1 else 0)
      ln += 1
    r

  /** Compare on each f64 lane (same NaN semantics as f32x4Cmp). */
  private def f64x2Cmp(a: Array[Byte], b: Array[Byte], op: (Double, Double) => Boolean): Array[Byte] =
    val r  = new Array[Byte](16)
    var ln = 0
    while ln < 2 do
      val x = jl.Double.longBitsToDouble(readLaneI64(a, ln))
      val y = jl.Double.longBitsToDouble(readLaneI64(b, ln))
      writeLaneI64(r, ln, if op(x, y) then -1L else 0L)
      ln += 1
    r

  // === Chunk H — narrow/extend/extadd_pairwise/extmul/conv helpers =========
  //
  // Narrow: pack two source v128s (`a` low half / `b` high half) into one
  // result, clamping each lane to the destination lane width's signed or
  // unsigned range. The spec calls the output ordering "concatenate the
  // saturated lanes of a and b" — bytes 0..7 of the result come from a's 8
  // i16 lanes, bytes 8..15 from b's. Same shape one level up for the
  // i16x8 ← i32x4 narrow.
  //
  // Extend (= widen): pull half the lanes of the source (low half = lanes
  // 0..N/2-1, high half = lanes N/2..N-1) and sign- or zero-extend each
  // into the wider lane width. `low_s` / `low_u` / `high_s` / `high_u` for
  // each shape pair.
  //
  // extadd_pairwise: sum two adjacent narrower lanes (with extension) into
  // one wider output lane. Output lane count is half the input lane count.
  //
  // extmul: like extend + multiply, fused. `extmul_low_*` multiplies the
  // extended low halves of the two operands; `_high_*` the high halves.
  // The result is the full-width product (no overflow at the wider lane).

  /** Read a signed i16 lane (LE) from `a` at lane `ln`. */
  private def readLaneI16Signed(a: Array[Byte], ln: Int): Int =
    val raw = (a(ln * 2) & 0xff) | ((a(ln * 2 + 1) & 0xff) << 8)
    (raw << 16) >> 16

  /** Read an unsigned i16 lane (LE) from `a` at lane `ln`, in 0..65535. */
  private def readLaneI16Unsigned(a: Array[Byte], ln: Int): Int =
    (a(ln * 2) & 0xff) | ((a(ln * 2 + 1) & 0xff) << 8)

  /** Saturating narrow Short → signed Byte (`-128..127`). */
  private inline def satS8(x: Int): Int =
    if x > 127 then 127 else if x < -128 then -128 else x

  /** Saturating narrow Short → unsigned Byte (`0..255`). */
  private inline def satU8(x: Int): Int =
    if x > 255 then 255 else if x < 0 then 0 else x

  /** Saturating narrow Int → signed Short (`-32768..32767`). */
  private inline def satS16(x: Int): Int =
    if x > 32767 then 32767 else if x < -32768 then -32768 else x

  /** Saturating narrow Int → unsigned Short (`0..65535`). */
  private inline def satU16(x: Int): Int =
    if x > 65535 then 65535 else if x < 0 then 0 else x

  /** i8x16.narrow_i16x8_s: a's 8 i16 → bytes 0..7 (signed-clamp), b's → 8..15. */
  private def i8x16NarrowI16x8S(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var i = 0
    while i < 8 do
      r(i)     = satS8(readLaneI16Signed(a, i)).toByte
      r(i + 8) = satS8(readLaneI16Signed(b, i)).toByte
      i += 1
    r

  /** i8x16.narrow_i16x8_u: signed Short → unsigned Byte (negative clamps to 0). */
  private def i8x16NarrowI16x8U(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var i = 0
    while i < 8 do
      r(i)     = satU8(readLaneI16Signed(a, i)).toByte
      r(i + 8) = satU8(readLaneI16Signed(b, i)).toByte
      i += 1
    r

  /** i16x8.narrow_i32x4_s: a's 4 i32 → i16 lanes 0..3, b's → 4..7. */
  private def i16x8NarrowI32x4S(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var i = 0
    while i < 4 do
      writeLaneI16(r, i,     satS16(readLaneI32(a, i)))
      writeLaneI16(r, i + 4, satS16(readLaneI32(b, i)))
      i += 1
    r

  /** i16x8.narrow_i32x4_u: signed Int → unsigned Short (negative clamps to 0). */
  private def i16x8NarrowI32x4U(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var i = 0
    while i < 4 do
      writeLaneI16(r, i,     satU16(readLaneI32(a, i)))
      writeLaneI16(r, i + 4, satU16(readLaneI32(b, i)))
      i += 1
    r

  /** i16x8.extend_low_i8x16_s: bytes 0..7 of `a`, sign-extended to 8 i16 lanes. */
  private def i16x8ExtendI8x16(a: Array[Byte], high: Boolean, signed: Boolean): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    val srcOff = if high then 8 else 0
    while ln < 8 do
      val byte = a(srcOff + ln)
      val v    = if signed then byte.toInt else byte & 0xff
      writeLaneI16(r, ln, v)
      ln += 1
    r

  /** i32x4.extend_low/high_i16x8_s/u. */
  private def i32x4ExtendI16x8(a: Array[Byte], high: Boolean, signed: Boolean): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    val srcLane0 = if high then 4 else 0
    while ln < 4 do
      val v =
        if signed then readLaneI16Signed(a, srcLane0 + ln)
        else readLaneI16Unsigned(a, srcLane0 + ln)
      writeLaneI32(r, ln, v)
      ln += 1
    r

  /** i64x2.extend_low/high_i32x4_s/u. */
  private def i64x2ExtendI32x4(a: Array[Byte], high: Boolean, signed: Boolean): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    val srcLane0 = if high then 2 else 0
    while ln < 2 do
      val srcI32 = readLaneI32(a, srcLane0 + ln)
      val v: Long =
        if signed then srcI32.toLong
        else srcI32.toLong & 0xffffffffL
      writeLaneI64(r, ln, v)
      ln += 1
    r

  /** i16x8.extadd_pairwise_i8x16_{s,u}: pair (a[2k], a[2k+1]) and sum with
    * extension into output i16 lane k. */
  private def i16x8ExtAddPairwiseI8x16(a: Array[Byte], signed: Boolean): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 8 do
      val lo = if signed then a(ln * 2).toInt     else a(ln * 2)     & 0xff
      val hi = if signed then a(ln * 2 + 1).toInt else a(ln * 2 + 1) & 0xff
      writeLaneI16(r, ln, lo + hi)
      ln += 1
    r

  /** i32x4.extadd_pairwise_i16x8_{s,u}. */
  private def i32x4ExtAddPairwiseI16x8(a: Array[Byte], signed: Boolean): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 4 do
      val srcLo = ln * 2
      val srcHi = ln * 2 + 1
      val lo =
        if signed then readLaneI16Signed(a, srcLo)
        else readLaneI16Unsigned(a, srcLo)
      val hi =
        if signed then readLaneI16Signed(a, srcHi)
        else readLaneI16Unsigned(a, srcHi)
      writeLaneI32(r, ln, lo + hi)
      ln += 1
    r

  /** i16x8.extmul_{low,high}_i8x16_{s,u}: extend half the source bytes from
    * each operand, multiply at i32 precision, write back as i16. */
  private def i16x8ExtMulI8x16(a: Array[Byte], b: Array[Byte], high: Boolean, signed: Boolean): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    val srcOff = if high then 8 else 0
    while ln < 8 do
      val ax = if signed then a(srcOff + ln).toInt else a(srcOff + ln) & 0xff
      val bx = if signed then b(srcOff + ln).toInt else b(srcOff + ln) & 0xff
      writeLaneI16(r, ln, ax * bx)
      ln += 1
    r

  /** i32x4.extmul_{low,high}_i16x8_{s,u}. */
  private def i32x4ExtMulI16x8(a: Array[Byte], b: Array[Byte], high: Boolean, signed: Boolean): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    val srcLane0 = if high then 4 else 0
    while ln < 4 do
      val ax =
        if signed then readLaneI16Signed(a, srcLane0 + ln)
        else readLaneI16Unsigned(a, srcLane0 + ln)
      val bx =
        if signed then readLaneI16Signed(b, srcLane0 + ln)
        else readLaneI16Unsigned(b, srcLane0 + ln)
      writeLaneI32(r, ln, ax * bx)
      ln += 1
    r

  /** i64x2.extmul_{low,high}_i32x4_{s,u}: multiply at i64 precision so the
    * full-width product is exact. */
  private def i64x2ExtMulI32x4(a: Array[Byte], b: Array[Byte], high: Boolean, signed: Boolean): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    val srcLane0 = if high then 2 else 0
    while ln < 2 do
      val ax =
        if signed then readLaneI32(a, srcLane0 + ln).toLong
        else readLaneI32(a, srcLane0 + ln).toLong & 0xffffffffL
      val bx =
        if signed then readLaneI32(b, srcLane0 + ln).toLong
        else readLaneI32(b, srcLane0 + ln).toLong & 0xffffffffL
      writeLaneI64(r, ln, ax * bx)
      ln += 1
    r

  /** i32x4.trunc_sat_f32x4_s — per-lane scalar trunc_sat semantics:
    * NaN → 0, below-range → Int.MinValue, above-range → Int.MaxValue. */
  private def i32x4TruncSatF32x4S(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 4 do
      val v = jl.Float.intBitsToFloat(readLaneI32(a, ln))
      val out =
        if jl.Float.isNaN(v)        then 0
        else if v < -2147483648.0f  then Int.MinValue
        else if v >= 2147483648.0f  then Int.MaxValue
        else                             v.toInt
      writeLaneI32(r, ln, out)
      ln += 1
    r

  /** i32x4.trunc_sat_f32x4_u — clamp to UInt32 range (0..0xFFFFFFFF; bit
    * pattern stored as signed Int with -1 = 0xFFFFFFFF). */
  private def i32x4TruncSatF32x4U(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 4 do
      val v = jl.Float.intBitsToFloat(readLaneI32(a, ln))
      val out =
        if jl.Float.isNaN(v)        then 0
        else if v <= -1.0f          then 0
        else if v >= 4294967296.0f  then -1
        else                             v.toLong.toInt
      writeLaneI32(r, ln, out)
      ln += 1
    r

  /** i32x4.trunc_sat_f64x2_s_zero — 2 f64 input lanes → i32 lanes 0..1;
    * lanes 2 + 3 zero-filled per the `_zero` suffix. */
  private def i32x4TruncSatF64x2SZero(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 2 do
      val v = jl.Double.longBitsToDouble(readLaneI64(a, ln))
      val out =
        if jl.Double.isNaN(v)       then 0
        else if v < -2147483648.0   then Int.MinValue
        else if v >= 2147483648.0   then Int.MaxValue
        else                             v.toInt
      writeLaneI32(r, ln, out)
      ln += 1
    r                                                                                   // lanes 2 + 3 stay 0 from `new Array`

  /** i32x4.trunc_sat_f64x2_u_zero. */
  private def i32x4TruncSatF64x2UZero(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 2 do
      val v = jl.Double.longBitsToDouble(readLaneI64(a, ln))
      val out =
        if jl.Double.isNaN(v)       then 0
        else if v <= -1.0           then 0
        else if v >= 4294967296.0   then -1
        else                             v.toLong.toInt
      writeLaneI32(r, ln, out)
      ln += 1
    r

  /** f32x4.convert_i32x4_s. */
  private def f32x4ConvertI32x4S(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 4 do
      val v = readLaneI32(a, ln).toFloat
      writeLaneI32(r, ln, jl.Float.floatToRawIntBits(v))
      ln += 1
    r

  /** f32x4.convert_i32x4_u — zero-extend the signed Int lane to UInt32. */
  private def f32x4ConvertI32x4U(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 4 do
      val u = readLaneI32(a, ln).toLong & 0xffffffffL
      writeLaneI32(r, ln, jl.Float.floatToRawIntBits(u.toFloat))
      ln += 1
    r

  /** f64x2.convert_low_i32x4_s — reads only lanes 0..1 of the source. */
  private def f64x2ConvertLowI32x4S(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 2 do
      val v = readLaneI32(a, ln).toDouble
      writeLaneI64(r, ln, jl.Double.doubleToRawLongBits(v))
      ln += 1
    r

  /** f64x2.convert_low_i32x4_u. */
  private def f64x2ConvertLowI32x4U(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 2 do
      val u = readLaneI32(a, ln).toLong & 0xffffffffL
      writeLaneI64(r, ln, jl.Double.doubleToRawLongBits(u.toDouble))
      ln += 1
    r

  /** f32x4.demote_f64x2_zero — 2 f64 lanes → f32 lanes 0..1; lanes 2/3 zero. */
  private def f32x4DemoteF64x2Zero(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 2 do
      val v = jl.Double.longBitsToDouble(readLaneI64(a, ln)).toFloat
      writeLaneI32(r, ln, jl.Float.floatToRawIntBits(v))
      ln += 1
    r

  /** f64x2.promote_low_f32x4 — read f32 lanes 0..1 of source, widen to f64. */
  private def f64x2PromoteLowF32x4(a: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var ln = 0
    while ln < 2 do
      val v = jl.Float.intBitsToFloat(readLaneI32(a, ln)).toDouble
      writeLaneI64(r, ln, jl.Double.doubleToRawLongBits(v))
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

  // === Chunk I — dot product + load_lane / store_lane helpers ===============
  //
  // The smallest SIMD chunk. dot_i16x8_s is a pairwise multiply-then-add at
  // the wider i32 lane (full i32 product fits exact for the multiply; the
  // pair-sum can overflow i32 only when both products are 2^30 — that's
  // -32768×-32768 + -32768×-32768 = 2^31, which wraps to Int.MinValue per
  // the wasm spec's two's-complement wraparound rule).
  //
  // load_lane / store_lane are memory-touching ops with a (memarg, laneidx)
  // immediate pair. width ∈ {1, 2, 4, 8}; the lane is pre-validated < 16/8/
  // 4/2 by the validator. We can copy raw bytes between memory and the
  // v128's lane offset (lane * width) — both sides are little-endian so the
  // byte layouts match.

  /** i32x4.dot_i16x8_s — for each i32 result lane k:
    * `a[2k]*b[2k] + a[2k+1]*b[2k+1]` with the i16 operand lanes
    * sign-extended to i32 before the multiply. Overflow on the pair-sum
    * wraps (two's complement) per the spec. */
  private def i32x4DotI16x8S(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16); var lane = 0
    while lane < 4 do
      val a0 = readLaneI16Signed(a, lane * 2)
      val a1 = readLaneI16Signed(a, lane * 2 + 1)
      val b0 = readLaneI16Signed(b, lane * 2)
      val b1 = readLaneI16Signed(b, lane * 2 + 1)
      writeLaneI32(r, lane, a0 * b0 + a1 * b1)
      lane += 1
    r

  /** v128.load{8,16,32,64}_lane — read `width` bytes from memory into the
    * v128's lane `laneIdx` (preserving the other lanes). Operand stack:
    * `[i32 addr, v128 src]` → `[v128]`. */
  private def loadLane(f: Frame, memArgPos: Int, width: Int): Unit =
    val body   = f.func.body
    val memArg = readMemArgAt(f, memArgPos)
    val lane   = body(f.pc) & 0xff
    f.pc += 1
    val src  = popV128().clone
    val addr = (popI32() & 0xffffffffL) + memArg.offset
    val mem  = memArgMemory(memArg)
    boundsCheck(mem, addr, width)
    System.arraycopy(mem.data, addr.toInt, src, lane * width, width)
    valueStack += V128(src)

  /** v128.store{8,16,32,64}_lane — write `width` bytes from the v128's
    * lane `laneIdx` to memory. Operand stack: `[i32 addr, v128 src]` →
    * `[]`. */
  private def storeLane(f: Frame, memArgPos: Int, width: Int): Unit =
    val body   = f.func.body
    val memArg = readMemArgAt(f, memArgPos)
    val lane   = body(f.pc) & 0xff
    f.pc += 1
    val src  = popV128()
    val addr = (popI32() & 0xffffffffL) + memArg.offset
    val mem  = memArgMemory(memArg)
    boundsCheck(mem, addr, width)
    System.arraycopy(src, lane * width, mem.data, addr.toInt, width)

  /** Dispatch for the 20 Relaxed-SIMD sub-opcodes (0x100..0x113). Extracted
    * from [[stepFd]] because adding these inline pushed the JVM bytecode
    * past the 64KB per-method ceiling — same reason `stepFc` lives in its
    * own method. The proposal's specs allow more than one valid
    * implementation per op; we pick one deterministic interpretation for
    * each — the same one V8 / wasmtime ship on x86_64. Where a strict-SIMD
    * analogue exists we reuse it directly (relaxed_swizzle ≡ swizzle,
    * relaxed_trunc_* ≡ trunc_sat_*). */
  private def stepFdRelaxed(f: Frame, sub: Int, p1: Int): Unit =
    sub match
      case 0x100 =>                                                                       // i8x16.relaxed_swizzle
        val s = popV128(); val v = popV128()
        f.pc = p1
        val r = new Array[Byte](16); var i = 0
        while i < 16 do
          val si = s(i) & 0xff
          r(i) = if si < 16 then v(si) else 0
          i += 1
        valueStack += V128(r)

      case 0x101 =>                                                                       // i32x4.relaxed_trunc_f32x4_s
        val a = popV128(); f.pc = p1; valueStack += V128(i32x4TruncSatF32x4S(a))
      case 0x102 =>                                                                       // i32x4.relaxed_trunc_f32x4_u
        val a = popV128(); f.pc = p1; valueStack += V128(i32x4TruncSatF32x4U(a))
      case 0x103 =>                                                                       // i32x4.relaxed_trunc_f64x2_s_zero
        val a = popV128(); f.pc = p1; valueStack += V128(i32x4TruncSatF64x2SZero(a))
      case 0x104 =>                                                                       // i32x4.relaxed_trunc_f64x2_u_zero
        val a = popV128(); f.pc = p1; valueStack += V128(i32x4TruncSatF64x2UZero(a))

      case 0x105 =>                                                                       // f32x4.relaxed_madd : (a, b, c) → a*b + c
        val c = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4Madd(a, b, c, neg = false))
      case 0x106 =>                                                                       // f32x4.relaxed_nmadd : (a, b, c) → -(a*b) + c
        val c = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f32x4Madd(a, b, c, neg = true))
      case 0x107 =>                                                                       // f64x2.relaxed_madd
        val c = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2Madd(a, b, c, neg = false))
      case 0x108 =>                                                                       // f64x2.relaxed_nmadd
        val c = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(f64x2Madd(a, b, c, neg = true))

      case 0x109 =>                                                                       // i8x16.relaxed_laneselect (bit 7 of mask)
        val mask = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        val r = new Array[Byte](16); var i = 0
        while i < 16 do
          r(i) = if (mask(i) & 0x80) != 0 then a(i) else b(i)
          i += 1
        valueStack += V128(r)
      case 0x10A =>                                                                       // i16x8.relaxed_laneselect (bit 15)
        val mask = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(laneSelectN(a, b, mask, width = 2))
      case 0x10B =>                                                                       // i32x4.relaxed_laneselect (bit 31)
        val mask = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(laneSelectN(a, b, mask, width = 4))
      case 0x10C =>                                                                       // i64x2.relaxed_laneselect (bit 63)
        val mask = popV128(); val b = popV128(); val a = popV128()
        f.pc = p1
        valueStack += V128(laneSelectN(a, b, mask, width = 8))

      case 0x10D =>                                                                       // f32x4.relaxed_min
        val b = popV128(); val a = popV128(); f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => jl.Math.min(x, y)))
      case 0x10E =>                                                                       // f32x4.relaxed_max
        val b = popV128(); val a = popV128(); f.pc = p1
        valueStack += V128(f32x4BinOp(a, b, (x, y) => jl.Math.max(x, y)))
      case 0x10F =>                                                                       // f64x2.relaxed_min
        val b = popV128(); val a = popV128(); f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => jl.Math.min(x, y)))
      case 0x110 =>                                                                       // f64x2.relaxed_max
        val b = popV128(); val a = popV128(); f.pc = p1
        valueStack += V128(f64x2BinOp(a, b, (x, y) => jl.Math.max(x, y)))

      case 0x111 =>                                                                       // i16x8.relaxed_q15mulr_s
        val b = popV128(); val a = popV128(); f.pc = p1
        valueStack += V128(i16x8Q15MulrS(a, b))

      case 0x112 =>                                                                       // i16x8.relaxed_dot_i8x16_i7x16_s
        val b = popV128(); val a = popV128(); f.pc = p1
        valueStack += V128(i16x8DotI8x16I7x16S(a, b))

      case 0x113 =>                                                                       // i32x4.relaxed_dot_i8x16_i7x16_add_s
        val c = popV128(); val b = popV128(); val a = popV128(); f.pc = p1
        valueStack += V128(i32x4DotI8x16I7x16AddS(a, b, c))

      case _ =>
        fail(WasmError.UnknownOpcode(0xfd))

  // === Chunk J — Relaxed SIMD helpers =======================================

  /** Lane-select on N-byte lanes for the relaxed_laneselect family
    * (i16 / i32 / i64). For each lane, the high bit of `mask`'s
    * corresponding lane selects between `a` and `b` (high bit set → a;
    * clear → b). `width` is the lane size in bytes (2 / 4 / 8); the
    * single-byte case is handled inline since it doesn't need a loop. */
  private def laneSelectN(a: Array[Byte], b: Array[Byte], mask: Array[Byte], width: Int): Array[Byte] =
    val r       = new Array[Byte](16)
    val nLanes  = 16 / width
    var i       = 0
    while i < nLanes do
      val high = mask(i * width + width - 1) & 0x80
      val src  = if high != 0 then a else b
      System.arraycopy(src, i * width, r, i * width, width)
      i += 1
    r

  /** `f32x4.relaxed_madd` and `f32x4.relaxed_nmadd`. Unfused multiply-add
    * — `(a*b) + c` for madd, `(-(a*b)) + c` for nmadd. The proposal
    * permits either fused (FMA) or unfused; unfused is portable across
    * JVM / Scala.js / Scala Native. */
  private def f32x4Madd(a: Array[Byte], b: Array[Byte], c: Array[Byte], neg: Boolean): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 4 do
      val ai = jl.Float.intBitsToFloat(le32(a, i * 4))
      val bi = jl.Float.intBitsToFloat(le32(b, i * 4))
      val ci = jl.Float.intBitsToFloat(le32(c, i * 4))
      val prod = ai * bi
      val out  = (if neg then -prod else prod) + ci
      writeLe32(r, i * 4, jl.Float.floatToRawIntBits(out))
      i += 1
    r

  /** `f64x2.relaxed_madd` / `f64x2.relaxed_nmadd`. Same shape as
    * [[f32x4Madd]] at double width. */
  private def f64x2Madd(a: Array[Byte], b: Array[Byte], c: Array[Byte], neg: Boolean): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 2 do
      val ai = jl.Double.longBitsToDouble(le64(a, i * 8))
      val bi = jl.Double.longBitsToDouble(le64(b, i * 8))
      val ci = jl.Double.longBitsToDouble(le64(c, i * 8))
      val prod = ai * bi
      val out  = (if neg then -prod else prod) + ci
      writeLe64(r, i * 8, jl.Double.doubleToRawLongBits(out))
      i += 1
    r

  /** `i16x8.relaxed_q15mulr_s` — saturating signed Q15 fixed-point
    * multiply with rounding: `sat((a*b + 0x4000) >> 15)` per lane. */
  private def i16x8Q15MulrS(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 8 do
      val ai = le16Signed(a, i * 2)
      val bi = le16Signed(b, i * 2)
      val prod = ai.toLong * bi.toLong
      val rounded = ((prod + 0x4000L) >> 15).toInt
      val sat =
        if rounded >  0x7FFF then  0x7FFF
        else if rounded < -0x8000 then -0x8000
        else rounded
      writeLe16(r, i * 2, sat)
      i += 1
    r

  /** `i16x8.relaxed_dot_i8x16_i7x16_s` — for each of 8 i16 lanes, the
    * pair-sum of two `(signed i8 × unsigned i8)` products from adjacent
    * bytes of `a` and `b`. The "i7" in the name means the second
    * operand's high bit is permitted to be either sign-extended or
    * zero-extended (relaxed); we pick zero-extension (treat `b` as
    * unsigned), which matches V8/wasmtime on x86_64. */
  private def i16x8DotI8x16I7x16S(a: Array[Byte], b: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 8 do
      val a0 = a(i * 2).toInt                  // sign-extended
      val a1 = a(i * 2 + 1).toInt
      val b0 = b(i * 2)     & 0xff             // zero-extended
      val b1 = b(i * 2 + 1) & 0xff
      val sum = a0 * b0 + a1 * b1              // fits in i32; cast back to i16 below
      writeLe16(r, i * 2, sum)
      i += 1
    r

  /** `i32x4.relaxed_dot_i8x16_i7x16_add_s` — for each i32 lane, the
    * pair-sum of FOUR `(signed i8 × unsigned i8)` products from
    * consecutive bytes of `a` and `b`, plus the matching lane of `c`. */
  private def i32x4DotI8x16I7x16AddS(a: Array[Byte], b: Array[Byte], c: Array[Byte]): Array[Byte] =
    val r = new Array[Byte](16)
    var i = 0
    while i < 4 do
      var sum = 0
      var j   = 0
      while j < 4 do
        val ai = a(i * 4 + j).toInt
        val bi = b(i * 4 + j) & 0xff
        sum += ai * bi
        j += 1
      val ci = le32(c, i * 4)
      writeLe32(r, i * 4, sum + ci)
      i += 1
    r

  // --- little-endian byte helpers used by the relaxed-SIMD helpers above ---

  private inline def le16Signed(buf: Array[Byte], pos: Int): Int =
    ((buf(pos) & 0xff) | (buf(pos + 1) << 8))

  private inline def le32(buf: Array[Byte], pos: Int): Int =
    (buf(pos)     & 0xff)        |
    ((buf(pos+1)  & 0xff) <<  8) |
    ((buf(pos+2)  & 0xff) << 16) |
    ((buf(pos+3)  & 0xff) << 24)

  private inline def le64(buf: Array[Byte], pos: Int): Long =
    (buf(pos)     & 0xffL)        |
    ((buf(pos+1)  & 0xffL) <<  8) |
    ((buf(pos+2)  & 0xffL) << 16) |
    ((buf(pos+3)  & 0xffL) << 24) |
    ((buf(pos+4)  & 0xffL) << 32) |
    ((buf(pos+5)  & 0xffL) << 40) |
    ((buf(pos+6)  & 0xffL) << 48) |
    ((buf(pos+7)  & 0xffL) << 56)

  private inline def writeLe16(buf: Array[Byte], pos: Int, v: Int): Unit =
    buf(pos)     = v.toByte
    buf(pos + 1) = (v >> 8).toByte

  private inline def writeLe32(buf: Array[Byte], pos: Int, v: Int): Unit =
    buf(pos)     = v.toByte
    buf(pos + 1) = (v >>  8).toByte
    buf(pos + 2) = (v >> 16).toByte
    buf(pos + 3) = (v >> 24).toByte

  private inline def writeLe64(buf: Array[Byte], pos: Int, v: Long): Unit =
    buf(pos)     = v.toByte
    buf(pos + 1) = (v >>  8).toByte
    buf(pos + 2) = (v >> 16).toByte
    buf(pos + 3) = (v >> 24).toByte
    buf(pos + 4) = (v >> 32).toByte
    buf(pos + 5) = (v >> 40).toByte
    buf(pos + 6) = (v >> 48).toByte
    buf(pos + 7) = (v >> 56).toByte


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
      case 0x60 | 0x61 | 0x62 |                                                   // i8x16  abs / neg / popcnt
           0x6E | 0x6F | 0x70 | 0x71 | 0x72 | 0x73 | 0x7B |                       // i8x16  add/sub/sat/avgr
           0x80 | 0x81 | 0x82 |                                                   // i16x8  abs / neg / q15mulr_sat_s
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

      // Chunk G.1 — bitwise (incl. ternary bitselect) + reductions.
      // All 15 ops are no-immediate; operand count differences are
      // handled at the validator and stepFd levels.
      case 0x4D | 0x4E | 0x4F | 0x50 | 0x51 | 0x52 |                              // v128.not/and/andnot/or/xor/bitselect
           0x53 |                                                                 // v128.any_true
           0x63 | 0x83 | 0xA3 | 0xC3 |                                            // *.all_true
           0x64 | 0x84 | 0xA4 | 0xC4 =>                                           // *.bitmask
        Right(p1)

      // Chunk G.2 — comparisons (48 ops, all v128×v128 → v128, no
      // immediate past the sub-opcode). Grouped by shape:
      //   - i8x16 0x23..0x2C  (10 ops: eq/ne + lt/gt/le/ge × s/u)
      //   - i16x8 0x2D..0x36  (same 10 ops)
      //   - i32x4 0x37..0x40  (same 10 ops)
      //   - i64x2 0xD6..0xDB  (6 ops: eq/ne + lt_s/gt_s/le_s/ge_s — no _u per spec)
      //   - f32x4 0x41..0x46  (6 ops: eq/ne + lt/gt/le/ge)
      //   - f64x2 0x47..0x4C  (same 6 ops)
      case 0x23 | 0x24 | 0x25 | 0x26 | 0x27 | 0x28 |
           0x29 | 0x2A | 0x2B | 0x2C |                                            // i8x16  10 cmps
           0x2D | 0x2E | 0x2F | 0x30 | 0x31 | 0x32 |
           0x33 | 0x34 | 0x35 | 0x36 |                                            // i16x8  10 cmps
           0x37 | 0x38 | 0x39 | 0x3A | 0x3B | 0x3C |
           0x3D | 0x3E | 0x3F | 0x40 |                                            // i32x4  10 cmps
           0xD6 | 0xD7 | 0xD8 | 0xD9 | 0xDA | 0xDB |                              // i64x2   6 cmps (signed-only)
           0x41 | 0x42 | 0x43 | 0x44 | 0x45 | 0x46 |                              // f32x4   6 cmps
           0x47 | 0x48 | 0x49 | 0x4A | 0x4B | 0x4C =>                             // f64x2   6 cmps
        Right(p1)

      // Chunk H — narrow / extend / extadd_pairwise / extmul + float-int
      // conv + demote / promote (42 ops, all no-immediate past the
      // sub-opcode; the validator handles unary vs binary operand stack
      // shape). Grouped:
      //   - 0x65/0x66/0x85/0x86 — narrow (signed/unsigned saturating)
      //   - 0x87..0x8A, 0xA7..0xAA, 0xC7..0xCA — extend low/high _s/_u
      //   - 0x7C..0x7F — extadd_pairwise _s/_u (i16x8 + i32x4)
      //   - 0x9C..0x9F, 0xBC..0xBF, 0xDC..0xDF — extmul low/high _s/_u
      //   - 0x5E/0x5F — f32x4.demote / f64x2.promote
      //   - 0xF8..0xFF — trunc_sat / convert (signed + unsigned, both
      //                   f32x4 and f64x2-low forms)
      case 0x65 | 0x66 | 0x85 | 0x86 |                                            // narrow
           0x87 | 0x88 | 0x89 | 0x8A |                                            // extend i8→i16
           0xA7 | 0xA8 | 0xA9 | 0xAA |                                            // extend i16→i32
           0xC7 | 0xC8 | 0xC9 | 0xCA |                                            // extend i32→i64
           0x7C | 0x7D | 0x7E | 0x7F |                                            // extadd_pairwise
           0x9C | 0x9D | 0x9E | 0x9F |                                            // extmul i8→i16
           0xBC | 0xBD | 0xBE | 0xBF |                                            // extmul i16→i32
           0xDC | 0xDD | 0xDE | 0xDF |                                            // extmul i32→i64
           0x5E | 0x5F |                                                          // demote / promote
           0xF8 | 0xF9 | 0xFA | 0xFB |                                            // trunc_sat / convert (f32x4 forms)
           0xFC | 0xFD | 0xFE | 0xFF =>                                           // trunc_sat / convert (f64x2 forms)
        Right(p1)

      // Chunk I — dot product has no immediate past the sub-opcode.
      case 0xBA =>
        Right(p1)

      // Chunk I — load_lane / store_lane carry a memarg followed by a
      // 1-byte lane index. The lane bound (< 16/8/4/2) is checked at
      // validation time, not here.
      case 0x54 | 0x55 | 0x56 | 0x57 | 0x58 | 0x59 | 0x5A | 0x5B =>
        Interpreter.readMemArg(body, p1) match
          case Left(e) => Left(e)
          case Right((_, pAfter)) =>
            if pAfter + 1 > body.length then
              Left(WasmError.InvalidModule(s"truncated lane immediate at $pc"))
            else Right(pAfter + 1)

      // Relaxed SIMD proposal — 20 sub-opcodes 0x100..0x113, all with no
      // additional immediate past the multi-byte LEB sub-opcode itself.
      //   0x100        i8x16.relaxed_swizzle
      //   0x101..0x104 *.relaxed_trunc_*  (4 forms)
      //   0x105..0x108 f*.relaxed_madd / relaxed_nmadd (4 forms)
      //   0x109..0x10C *.relaxed_laneselect (4 widths)
      //   0x10D..0x110 f*.relaxed_min / relaxed_max (4 forms)
      //   0x111        i16x8.relaxed_q15mulr_s
      //   0x112        i16x8.relaxed_dot_i8x16_i7x16_s
      //   0x113        i32x4.relaxed_dot_i8x16_i7x16_add_s  (ternary)
      case 0x100 | 0x101 | 0x102 | 0x103 | 0x104 |
           0x105 | 0x106 | 0x107 | 0x108 |
           0x109 | 0x10A | 0x10B | 0x10C |
           0x10D | 0x10E | 0x10F | 0x110 |
           0x111 | 0x112 | 0x113 =>
        Right(p1)

      case _ => Left(WasmError.UnknownOpcode(0xfd))
