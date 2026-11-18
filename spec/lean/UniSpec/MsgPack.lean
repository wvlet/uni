/-!
# A model of MessagePack integer and header encoding

Bytes are natural numbers, and the `*_lt_256` theorems show each one fits in a byte. Each encoder
is an if-chain that mirrors its `OffsetPacker` method (`packLong`, `packRawStringHeader`,
`packBinaryHeader`, `packArrayHeader`, `packMapHeader`, `packExtTypeHeader`). The theorems show that
decoding inverts encoding and that the chosen format is the shortest the MessagePack spec allows.
`GenMsgPackCases.lean` evaluates the encoders to generate the cases `MsgPackSpecTest` replays.
-/

namespace UniSpec.MsgPack

-- The header proofs share one simp set; not every lemma fires in every branch
set_option linter.unusedSimpArgs false

/-! ## Big-endian bytes -/

/-- The low `k` bytes of `n`, most significant first. -/
def be : Nat → Nat → List Nat
  | 0, _ => []
  | k + 1, n => (n / 256 ^ k) % 256 :: be k n

/-- The number a big-endian byte list denotes. -/
def ofBE : List Nat → Nat
  | [] => 0
  | b :: bs => b * 256 ^ bs.length + ofBE bs

theorem be_length (k n : Nat) : (be k n).length = k := by
  induction k with
  | zero => rfl
  | succ k ih => simp [be, ih]

theorem be_lt_256 (k n : Nat) : ∀ b ∈ be k n, b < 256 := by
  induction k with
  | zero => simp [be]
  | succ k ih =>
    intro b hb
    simp only [be, List.mem_cons] at hb
    rcases hb with rfl | hb
    · exact Nat.mod_lt _ (by decide)
    · exact ih b hb

theorem ofBE_be (k n : Nat) : ofBE (be k n) = n % 256 ^ k := by
  induction k with
  | zero => simp [be, ofBE, Nat.mod_one]
  | succ k ih =>
    simp only [be, ofBE, be_length, ih]
    rw [Nat.mod_pow_succ, Nat.mul_comm]
    omega

/-- Read `k` big-endian bytes. -/
def readBE (k : Nat) (bs : List Nat) : Option (Nat × List Nat) :=
  if k ≤ bs.length then some (ofBE (bs.take k), bs.drop k) else none

theorem readBE_be (k n : Nat) (rest : List Nat) (h : n < 256 ^ k) :
    readBE k (be k n ++ rest) = some (n, rest) := by
  have hlen := be_length k n
  simp only [readBE, List.length_append, hlen, Nat.le_add_right, ↓reduceIte]
  rw [List.take_left' hlen, List.drop_left' hlen, ofBE_be, Nat.mod_eq_of_lt h]

/-! ## Integers -/

/-- Integers MessagePack can encode: `[-2^63, 2^64)`. -/
def IntInRange (v : Int) : Prop := -9223372036854775808 ≤ v ∧ v < 18446744073709551616

/-- `OffsetPacker.packLong` (and `packBigInteger` above `Long.MaxValue`). A negative value in an
`int*` format of `k` bytes is stored as its two's complement `v + 256^k`. -/
def encodeInt (v : Int) : List Nat :=
  if v < -32 then
    if v < -32768 then
      if v < -2147483648 then 0xd3 :: be 8 (v + 18446744073709551616).toNat
      else 0xd2 :: be 4 (v + 4294967296).toNat
    else if v < -128 then 0xd1 :: be 2 (v + 65536).toNat
    else 0xd0 :: be 1 (v + 256).toNat
  else if v < 0 then [(v + 256).toNat]
  else if v < 128 then [v.toNat]
  else if v < 256 then 0xcc :: be 1 v.toNat
  else if v < 65536 then 0xcd :: be 2 v.toNat
  else if v < 4294967296 then 0xce :: be 4 v.toNat
  else 0xcf :: be 8 v.toNat

/-- Read `k` bytes as an unsigned value. -/
def readUnsigned (k : Nat) (bs : List Nat) : Option (Int × List Nat) :=
  (readBE k bs).map fun (u, rest) => ((u : Int), rest)

/-- Read `k` bytes as a two's complement value. -/
def readSigned (k : Nat) (bs : List Nat) : Option (Int × List Nat) :=
  (readBE k bs).map fun (u, rest) =>
    (if u < 256 ^ k / 2 then (u : Int) else (u : Int) - ((256 ^ k : Nat) : Int), rest)

/-- `OffsetUnpacker.unpackLong` / `unpackBigInteger`. -/
def decodeInt : List Nat → Option (Int × List Nat)
  | [] => none
  | b :: rest =>
    if b < 0x80 then some (b, rest)
    else if 0xe0 ≤ b then some ((b : Int) - 256, rest)
    else if b = 0xcc then readUnsigned 1 rest
    else if b = 0xcd then readUnsigned 2 rest
    else if b = 0xce then readUnsigned 4 rest
    else if b = 0xcf then readUnsigned 8 rest
    else if b = 0xd0 then readSigned 1 rest
    else if b = 0xd1 then readSigned 2 rest
    else if b = 0xd2 then readSigned 4 rest
    else if b = 0xd3 then readSigned 8 rest
    else none

theorem readUnsigned_be (k : Nat) (v : Int) (rest : List Nat) (h0 : 0 ≤ v)
    (h : v.toNat < 256 ^ k) : readUnsigned k (be k v.toNat ++ rest) = some (v, rest) := by
  simp only [readUnsigned, readBE_be k v.toNat rest h, Option.map_some, Option.some.injEq,
    Prod.mk.injEq, and_true]
  omega

theorem readSigned_be (k m : Nat) (v : Int) (rest : List Nat) (hm : 256 ^ k = m)
    (h1 : -((m / 2 : Nat) : Int) ≤ v) (h2 : v < 0) (hu : (v + m).toNat < m) :
    readSigned k (be k (v + m).toNat ++ rest) = some (v, rest) := by
  rw [readSigned, readBE_be k _ rest (by omega)]
  simp only [Option.map_some, Option.some.injEq, Prod.mk.injEq, and_true, hm]
  split <;> omega

/-- Decoding the encoding of any encodable integer gives it back and consumes exactly its bytes. -/
theorem decodeInt_encodeInt (v : Int) (rest : List Nat) (h : IntInRange v) :
    decodeInt (encodeInt v ++ rest) = some (v, rest) := by
  unfold IntInRange at h
  unfold encodeInt
  repeat' split
  all_goals simp only [List.cons_append, List.nil_append, decodeInt, ↓reduceIte, Nat.reduceLT,
    Nat.reduceLeDiff, Nat.reduceEqDiff]
  · exact readSigned_be 8 18446744073709551616 v rest (by decide) (by omega) (by omega) (by omega)
  · exact readSigned_be 4 4294967296 v rest (by decide) (by omega) (by omega) (by omega)
  · exact readSigned_be 2 65536 v rest (by decide) (by omega) (by omega) (by omega)
  · exact readSigned_be 1 256 v rest (by decide) (by omega) (by omega) (by omega)
  · rw [ite_eq_right (by omega), ite_eq_left (by omega)]; simp only [Option.some.injEq, Prod.mk.injEq,
      and_true]; omega
  · rw [ite_eq_left (by omega)]; simp only [Option.some.injEq, Prod.mk.injEq, and_true]; omega
  · exact readUnsigned_be 1 v rest (by omega) (by omega)
  · exact readUnsigned_be 2 v rest (by omega) (by omega)
  · exact readUnsigned_be 4 v rest (by omega) (by omega)
  · exact readUnsigned_be 8 v rest (by omega) (by omega)

/-- Every byte of an integer encoding fits in a byte. -/
theorem encodeInt_lt_256 (v : Int) (h : IntInRange v) : ∀ b ∈ encodeInt v, b < 256 := by
  unfold IntInRange at h
  intro b hb
  unfold encodeInt at hb
  repeat' split at hb
  all_goals simp only [List.mem_cons, List.not_mem_nil, or_false] at hb
  all_goals first
    | omega
    | (rcases hb with rfl | hb
       · decide
       · exact be_lt_256 _ _ b hb)

/-- The formats the spec allows for integers: value range `[lo, hi)` and encoded size. -/
def intFormats : List (Int × Int × Nat) :=
  [ (0, 128, 1), (-32, 0, 1),                        -- positive and negative fixint
    (0, 256, 2), (0, 65536, 3), (0, 4294967296, 5), (0, 18446744073709551616, 9),  -- uint*
    (-128, 128, 2), (-32768, 32768, 3), (-2147483648, 2147483648, 5),               -- int*
    (-9223372036854775808, 9223372036854775808, 9) ]

/-- The encoding is never longer than any format the spec allows for the value. -/
theorem encodeInt_shortest (v : Int) (lo hi : Int) (size : Nat) (hf : (lo, hi, size) ∈ intFormats)
    (hlo : lo ≤ v) (hhi : v < hi) : (encodeInt v).length ≤ size := by
  simp only [intFormats, List.mem_cons, Prod.mk.injEq, List.not_mem_nil, or_false] at hf
  unfold encodeInt
  repeat' split
  all_goals simp only [List.length_cons, be_length, List.length_nil]
  all_goals omega

/-- Non-negative values never use an `int*` format (`0xd0` to `0xd3`). -/
theorem encodeInt_nonneg_unsigned (v : Int) (h : 0 ≤ v) :
    (encodeInt v).head?.all (fun b => b < 0xd0 ∨ 0xd3 < b) := by
  unfold encodeInt
  repeat' split
  all_goals simp
  all_goals omega

/-! ## str, bin, array, and map headers -/

/-- `OffsetPacker.packRawStringHeader` -/
def encodeStr (n : Nat) : List Nat :=
  if n < 32 then [0xa0 + n]
  else if n < 256 then [0xd9, n]
  else if n < 65536 then 0xda :: be 2 n
  else 0xdb :: be 4 n

/-- `OffsetPacker.packBinaryHeader`: binary has no fix format. -/
def encodeBin (n : Nat) : List Nat :=
  if n < 256 then [0xc4, n]
  else if n < 65536 then 0xc5 :: be 2 n
  else 0xc6 :: be 4 n

/-- `OffsetPacker.packArrayHeader` -/
def encodeArray (n : Nat) : List Nat :=
  if n < 16 then [0x90 + n]
  else if n < 65536 then 0xdc :: be 2 n
  else 0xdd :: be 4 n

/-- `OffsetPacker.packMapHeader` -/
def encodeMap (n : Nat) : List Nat :=
  if n < 16 then [0x80 + n]
  else if n < 65536 then 0xde :: be 2 n
  else 0xdf :: be 4 n

def decodeStr : List Nat → Option (Nat × List Nat)
  | [] => none
  | b :: rest =>
    if 0xa0 ≤ b ∧ b < 0xc0 then some (b - 0xa0, rest)
    else if b = 0xd9 then readBE 1 rest
    else if b = 0xda then readBE 2 rest
    else if b = 0xdb then readBE 4 rest
    else none

def decodeBin : List Nat → Option (Nat × List Nat)
  | [] => none
  | b :: rest =>
    if b = 0xc4 then readBE 1 rest
    else if b = 0xc5 then readBE 2 rest
    else if b = 0xc6 then readBE 4 rest
    else none

def decodeArray : List Nat → Option (Nat × List Nat)
  | [] => none
  | b :: rest =>
    if 0x90 ≤ b ∧ b < 0xa0 then some (b - 0x90, rest)
    else if b = 0xdc then readBE 2 rest
    else if b = 0xdd then readBE 4 rest
    else none

def decodeMap : List Nat → Option (Nat × List Nat)
  | [] => none
  | b :: rest =>
    if 0x80 ≤ b ∧ b < 0x90 then some (b - 0x80, rest)
    else if b = 0xde then readBE 2 rest
    else if b = 0xdf then readBE 4 rest
    else none

/-- Lengths a header can encode: below `2^32`. -/
def LenInRange (n : Nat) : Prop := n < 4294967296

theorem decodeStr_encodeStr (n : Nat) (rest : List Nat) (h : LenInRange n) :
    decodeStr (encodeStr n ++ rest) = some (n, rest) := by
  unfold LenInRange at h
  unfold encodeStr
  repeat' split
  all_goals simp only [List.cons_append, List.nil_append, decodeStr, ↓reduceIte, Nat.reduceLeDiff,
    Nat.reduceLT, Nat.reduceEqDiff, and_false, and_true, true_and]
  · rw [ite_eq_left (by omega)]; simp
  · simp [readBE, ofBE]
  · exact readBE_be 2 n rest (by show n < 65536; omega)
  · exact readBE_be 4 n rest (by show n < 4294967296; omega)

theorem decodeBin_encodeBin (n : Nat) (rest : List Nat) (h : LenInRange n) :
    decodeBin (encodeBin n ++ rest) = some (n, rest) := by
  unfold LenInRange at h
  unfold encodeBin
  repeat' split
  all_goals simp only [List.cons_append, List.nil_append, decodeBin, ↓reduceIte, Nat.reduceLeDiff,
    Nat.reduceLT, Nat.reduceEqDiff, and_false, and_true, true_and]
  · simp [readBE, ofBE]
  · exact readBE_be 2 n rest (by show n < 65536; omega)
  · exact readBE_be 4 n rest (by show n < 4294967296; omega)

theorem decodeArray_encodeArray (n : Nat) (rest : List Nat) (h : LenInRange n) :
    decodeArray (encodeArray n ++ rest) = some (n, rest) := by
  unfold LenInRange at h
  unfold encodeArray
  repeat' split
  all_goals simp only [List.cons_append, List.nil_append, decodeArray, ↓reduceIte, Nat.reduceLeDiff,
    Nat.reduceLT, Nat.reduceEqDiff, and_false, and_true, true_and]
  · rw [ite_eq_left (by omega)]; simp
  · exact readBE_be 2 n rest (by show n < 65536; omega)
  · exact readBE_be 4 n rest (by show n < 4294967296; omega)

theorem decodeMap_encodeMap (n : Nat) (rest : List Nat) (h : LenInRange n) :
    decodeMap (encodeMap n ++ rest) = some (n, rest) := by
  unfold LenInRange at h
  unfold encodeMap
  repeat' split
  all_goals simp only [List.cons_append, List.nil_append, decodeMap, ↓reduceIte, Nat.reduceLeDiff,
    Nat.reduceLT, Nat.reduceEqDiff, and_false, and_true, true_and]
  · rw [ite_eq_left (by omega)]; simp
  · exact readBE_be 2 n rest (by show n < 65536; omega)
  · exact readBE_be 4 n rest (by show n < 4294967296; omega)

/-- The formats the spec allows for each header kind: exclusive length limit and size. -/
def strFormats : List (Nat × Nat) := [(32, 1), (256, 2), (65536, 3), (4294967296, 5)]
def binFormats : List (Nat × Nat) := [(256, 2), (65536, 3), (4294967296, 5)]
def arrayFormats : List (Nat × Nat) := [(16, 1), (65536, 3), (4294967296, 5)]
def mapFormats : List (Nat × Nat) := arrayFormats

macro "header_shortest" : tactic => `(tactic| (
  simp only [List.mem_cons, Prod.mk.injEq, List.not_mem_nil, or_false] at *
  repeat' split
  all_goals simp only [List.length_cons, be_length, List.length_singleton, List.length_nil]
  all_goals omega))

theorem encodeStr_shortest (n limit size : Nat) (hf : (limit, size) ∈ strFormats)
    (h : n < limit) : (encodeStr n).length ≤ size := by
  unfold strFormats at hf; unfold encodeStr; header_shortest

theorem encodeBin_shortest (n limit size : Nat) (hf : (limit, size) ∈ binFormats)
    (h : n < limit) : (encodeBin n).length ≤ size := by
  unfold binFormats at hf; unfold encodeBin; header_shortest

theorem encodeArray_shortest (n limit size : Nat) (hf : (limit, size) ∈ arrayFormats)
    (h : n < limit) : (encodeArray n).length ≤ size := by
  unfold arrayFormats at hf; unfold encodeArray; header_shortest

theorem encodeMap_shortest (n limit size : Nat) (hf : (limit, size) ∈ mapFormats)
    (h : n < limit) : (encodeMap n).length ≤ size := by
  unfold mapFormats arrayFormats at hf; unfold encodeMap; header_shortest

/-! ## ext headers

The header carries the payload length and a type byte. Lengths 1, 2, 4, 8, and 16 use `fixext*`,
which has no length bytes. -/

/-- `OffsetPacker.packExtTypeHeader` for type byte `t` and payload length `n`. -/
def encodeExt (t n : Nat) : List Nat :=
  if n = 1 then [0xd4, t]
  else if n = 2 then [0xd5, t]
  else if n = 4 then [0xd6, t]
  else if n = 8 then [0xd7, t]
  else if n = 16 then [0xd8, t]
  else if n < 256 then [0xc7, n, t]
  else if n < 65536 then 0xc8 :: be 2 n ++ [t]
  else 0xc9 :: be 4 n ++ [t]

/-- Read a type byte after the length. -/
def readType : Option (Nat × List Nat) → Option ((Nat × Nat) × List Nat)
  | some (n, t :: rest) => some ((t, n), rest)
  | _ => none

/-- `OffsetUnpacker.unpackExtTypeHeader`: (type byte, payload length). -/
def decodeExt : List Nat → Option ((Nat × Nat) × List Nat)
  | [] => none
  | b :: rest =>
    match rest with
    | [] => none
    | t :: rest' =>
      if b = 0xd4 then some ((t, 1), rest')
      else if b = 0xd5 then some ((t, 2), rest')
      else if b = 0xd6 then some ((t, 4), rest')
      else if b = 0xd7 then some ((t, 8), rest')
      else if b = 0xd8 then some ((t, 16), rest')
      else if b = 0xc7 then readType (readBE 1 rest)
      else if b = 0xc8 then readType (readBE 2 rest)
      else if b = 0xc9 then readType (readBE 4 rest)
      else none

/-- An `ext 8/16/32` header: format byte, `k` length bytes, then the type byte. -/
theorem decodeExt_wide (c k n t : Nat) (rest : List Nat)
    (hc : (c = 0xc7 ∧ k = 1) ∨ (c = 0xc8 ∧ k = 2) ∨ (c = 0xc9 ∧ k = 4)) (hn : n < 256 ^ k) :
    decodeExt (c :: (be k n ++ t :: rest)) = some ((t, n), rest) := by
  have hr := readBE_be k n (t :: rest) hn
  rcases hc with ⟨rfl, rfl⟩ | ⟨rfl, rfl⟩ | ⟨rfl, rfl⟩ <;>
    simp only [be, List.cons_append, List.nil_append] at hr ⊢ <;> simp only [decodeExt, ↓reduceIte,
      Nat.reduceEqDiff] <;> rw [hr] <;> rfl

theorem decodeExt_encodeExt (t n : Nat) (rest : List Nat) (h : LenInRange n) :
    decodeExt (encodeExt t n ++ rest) = some ((t, n), rest) := by
  unfold LenInRange at h
  unfold encodeExt
  repeat' split
  · subst_vars; rfl
  · subst_vars; rfl
  · subst_vars; rfl
  · subst_vars; rfl
  · subst_vars; rfl
  · have key : [0xc7, n, t] ++ rest = 0xc7 :: (be 1 n ++ t :: rest) := by simp [be]; omega
    rw [key]; exact decodeExt_wide _ 1 n t rest (by simp) (by omega)
  · have key : (0xc8 :: be 2 n ++ [t]) ++ rest = 0xc8 :: (be 2 n ++ t :: rest) := by simp
    rw [key]; exact decodeExt_wide _ 2 n t rest (by simp) (by omega)
  · have key : (0xc9 :: be 4 n ++ [t]) ++ rest = 0xc9 :: (be 4 n ++ t :: rest) := by simp
    rw [key]; exact decodeExt_wide _ 4 n t rest (by simp) (by omega)

/-- The encoding is never longer than any format the spec allows for length `n`:
`fixext*` (2 bytes) for lengths 1, 2, 4, 8, 16, then `ext 8/16/32` (3, 4, 6 bytes). -/
theorem encodeExt_shortest (t n : Nat) :
    ((n = 1 ∨ n = 2 ∨ n = 4 ∨ n = 8 ∨ n = 16) → (encodeExt t n).length ≤ 2) ∧
    (n < 256 → (encodeExt t n).length ≤ 3) ∧
    (n < 65536 → (encodeExt t n).length ≤ 4) ∧
    (n < 4294967296 → (encodeExt t n).length ≤ 6) := by
  unfold encodeExt
  repeat' split
  all_goals simp only [List.length_cons, List.length_append, be_length, List.length_nil]
  all_goals omega

end UniSpec.MsgPack
