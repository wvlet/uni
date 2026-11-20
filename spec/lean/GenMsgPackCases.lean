import UniSpec.MsgPack

/-!
Writes `MsgPackSpecCases.scala`: the exact bytes of every integer, header, float, and timestamp
encoding at the format boundaries, evaluated from the model in `UniSpec/MsgPack.lean`. `MsgPackSpecTest` replays them
through `OffsetPacker` and `OffsetUnpacker`.

Usage: `lake exe gen-msgpack-cases [output path]`
-/

open UniSpec.MsgPack

def hexByte (b : Nat) : String :=
  let d := Nat.toDigits 16 b
  String.ofList (if d.length < 2 then '0' :: d else d)

def hex (bs : List Nat) : String := String.join (bs.map hexByte)

/-- Each format boundary `2^k`, and the values just inside and outside it on both signs. -/
def intValues : List Int :=
  let bounds : List Int := [32, 128, 256, 32768, 65536, 2147483648, 4294967296,
    9223372036854775808, 18446744073709551616]
  let around := bounds.flatMap fun b => [b - 1, b, -b, -b - 1]
  let vs := [0, 1, -1, 42, -100] ++ around
  let inRange := vs.filter fun v =>
    decide (-9223372036854775808 ≤ v ∧ v < 18446744073709551616)
  (inRange.mergeSort (· ≤ ·)).eraseDups

/-- Header lengths at each format boundary, up to `Int.MaxValue` (the Scala API takes an `Int`). -/
def lengths : List Nat := [0, 1, 15, 16, 31, 32, 255, 256, 65535, 65536, 2147483647]

/-- ext lengths: every `fixext` size and its neighbours, then the wide boundaries. -/
def extLengths : List Nat := [0, 1, 2, 3, 4, 5, 8, 16, 17, 255, 256, 65535, 65536, 2147483647]

/-- float 32 bits: zeros, ones, the subnormal and normal boundaries, the largest finite value, the
infinities, and quiet NaNs with payloads. A signaling float 32 NaN is left out: on Scala.js a
`Float` is a JS number, and converting it to one makes the NaN quiet (`7f800001` reads back as
`7fc00001`). -/
def float32Bits : List Nat := [0x00000000, 0x80000000, 0x3f800000, 0xbf800000, 0x00000001,
  0x007fffff, 0x00800000, 0x7f7fffff, 0x7f800000, 0xff800000, 0x7fc00000, 0x7fc00001, 0xffc00000,
  0x3eaaaaab]

/-- float 64 bits: the same kinds of values, plus values that a float 32 cannot hold exactly, so
reading them as a `Float` rounds: to nearest even, to zero, or to infinity. -/
def float64Bits : List Nat := [0x0000000000000000, 0x8000000000000000, 0x3ff0000000000000,
  0xbff0000000000000, 0x0000000000000001, 0x000fffffffffffff, 0x0010000000000000,
  0x7fefffffffffffff, 0x7ff0000000000000, 0xfff0000000000000, 0x7ff8000000000000,
  0x7ff0000000000001, 0x7ff8000000000001, 0xfff8000000000000, 0x3fd5555555555555,
  0x3ff0000010000000, 0x3ff0000030000000, 0x36a0000000000000, 0x3690000000000000,
  0x3698000000000000, 0x47efffffe0000000, 0x47effffff0000000, 0x7e37e43c8800759c]

/-- Reading a float 32 as a `Double` (`unpackDouble`) and a float 64 as a `Float` (`unpackFloat`)
converts the value as IEEE 754 does. The expected bits come from Lean's `Float32` and `Float`, an
IEEE implementation independent of the JVM, JS, and Native. A NaN stays a NaN, but its payload is
not specified, so these cases check only that it is a NaN. -/
def widen (bits : Nat) : String :=
  let f := Float32.ofBits bits.toUInt32
  if f.isNaN then "NaN" else hex (be 8 f.toFloat.toBits.toNat)

def narrow (bits : Nat) : String :=
  let d := Float.ofBits bits.toUInt64
  if d.isNaN then "NaN" else hex (be 4 d.toFloat32.toBits.toNat)

/-- Seconds at each format boundary, the range of `Instant`, and the range of `Long`. -/
def tsSeconds : List Int := [0, 1, -1, 1700000000, 4294967295, 4294967296, 17179869183,
  17179869184, -17179869184, 31556889864403199, 31556889864403200, -31557014167219200,
  -31557014167219201, 9223372036854775807, -9223372036854775808]

/-- Nanos within a second, and outside it on both sides, up to the range of `Int`. -/
def tsNanos : List Int := [0, 1, 999999999, 1000000000, -1, -1000000000, 2147483647, -2147483648]

def timestampLine (sec nanos : Int) : String :=
  match encodeTimestamp sec nanos with
  | none => s!"timestamp {sec} {nanos} overflow -"
  | some bs =>
    let read :=
      match decodeTimestamp bs with
      | some ((s, n), _) =>
        match instantOf s n with
        | some (s, n) => s!"{s}:{n}"
        | none => "invalid"
      | none => "invalid"
    s!"timestamp {sec} {nanos} {hex bs} {read}"

def caseLines : List String :=
  intValues.map (fun v => s!"int {v} {hex (encodeInt v)}") ++
  lengths.map (fun n => s!"str {n} {hex (encodeStr n)}") ++
  lengths.map (fun n => s!"bin {n} {hex (encodeBin n)}") ++
  lengths.map (fun n => s!"array {n} {hex (encodeArray n)}") ++
  lengths.map (fun n => s!"map {n} {hex (encodeMap n)}") ++
  [1, 255].flatMap (fun t => extLengths.map fun n => s!"ext {t} {n} {hex (encodeExt t n)}") ++
  float32Bits.map (fun b => s!"float32 {hex (be 4 b)} {hex (encodeFloat32 b)} {widen b}") ++
  float64Bits.map (fun b => s!"float64 {hex (be 8 b)} {hex (encodeFloat64 b)} {narrow b}") ++
  tsSeconds.flatMap fun sec => tsNanos.map fun nanos => timestampLine sec nanos

-- Formatted as scalafmt would, so the Code format check passes on the generated file
def scalaSource : String :=
  let lines := caseLines.map fun l => s!"    \"{l}\""
  "\n".intercalate ([
    "// Generated by `lake exe gen-msgpack-cases` in spec/lean from UniSpec/MsgPack.lean. Do not edit.",
    "package wvlet.uni.msgpack.spi",
    "",
    "object MsgPackSpecCases:",
    "  // Each case: kind, value (ext: type byte, then length), expected bytes in hex. A float case",
    "  // adds its bits read at the other width (NaN for any NaN). A timestamp case is seconds, nanos,",
    "  // bytes (overflow when packing throws), and the seconds:nanos read back (invalid outside",
    "  // Instant's range).",
    "  val cases: Seq[String] = Seq("
  ] ++ [",\n".intercalate lines, "  )", "", "end MsgPackSpecCases", ""])

def defaultOutput : System.FilePath :=
  "../../uni/src/test/scala/wvlet/uni/msgpack/spi/MsgPackSpecCases.scala"

def main (args : List String) : IO Unit := do
  let out := args.head?.map System.FilePath.mk |>.getD defaultOutput
  IO.FS.writeFile out scalaSource
  IO.println s!"Wrote {caseLines.length} cases to {out}"
