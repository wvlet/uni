import UniSpec.HttpSmuggle

/-!
# No request smuggling, proven

`no_smuggling`: whenever the reader accepts a request and keeps the connection open after it
(`readerFraming`), every member of the lenient family in `HttpSmuggle.lean` either rejects the
head or finds the same head length and the same framing. `GenHttpReaderCases` checks the same
rule on inputs and their one-edit variants; this proves it for every byte stream.

The proof follows the reader's head line by line:

- Each line the reader accepts ends at a CR LF and holds no CR or LF, so a member finds the same
  lines in both line modes (`nextLine_crlf`), and the same empty line ends the head.
- Each field line the reader accepts starts with a token byte, has a colon, and a name of token
  bytes. A member keeps it as one field with the same name (whitespace before the colon is
  impossible, and obs-fold too), and with the reader's value, plus the trailing whitespace if it
  does not trim (`memberValue_split`).
- So a member sees the same `Transfer-Encoding` and `Content-Length` fields. With a coding, the
  reader keeps the connection open only without a length, so a member frames the body as chunked
  or rejects it. Without one, a member reads the same length, or rejects a value it kept trailing
  whitespace on.
-/

namespace UniSpec.HttpSmuggle

open UniSpec.HttpReader

/-! ## Characters -/

theorem lowerAscii_eq (c : Char) : lowerAscii c = c.toLower := by
  unfold lowerAscii Char.toLower
  by_cases h : c.val ≥ 'A'.val ∧ c.val ≤ 'Z'.val
  · rw [dite_eq_left h]
    have h' : (decide ('A' ≤ c) && decide (c ≤ 'Z')) = true := by
      simp only [Bool.and_eq_true, decide_eq_true_eq]
      exact ⟨h.1, h.2⟩
    rw [ite_eq_left h']
    have hlt : c.toNat + 32 < 0xd800 := by
      have h2 := h.2
      rw [UInt32.le_iff_toNat_le] at h2
      show c.val.toNat + 32 < 55296
      have : ('Z'.val).toNat = 90 := rfl
      omega
    apply Char.ext
    simp [Char.ofNat, Char.ofNatAux, Nat.isValidChar, hlt]
    apply UInt32.toNat_inj.mp
    rfl
  · rw [dite_eq_right h]
    have h' : ¬ (decide ('A' ≤ c) && decide (c ≤ 'Z')) = true := by
      simp only [Bool.and_eq_true, decide_eq_true_eq]
      exact fun ⟨a, b⟩ => h ⟨a, b⟩
    rw [ite_eq_right h']

/-- A member's names match the reader's: both lower-case ASCII letters only. -/
theorem named_eq (n : Bytes) (s : String) : named n s = (lower n == s.toList) := by
  unfold named lower
  rw [show lowerAscii = Char.toLower from funext lowerAscii_eq]

theorem isWs_eq : isWs = isBlank := rfl

theorem tokenChar_not_colon {c : Char} (h : isTokenChar c = true) : c ≠ ':' := by
  rintro rfl
  exact absurd h (by decide)

theorem tokenChar_not_ws {c : Char} (h : isTokenChar c = true) : isWs c = false := by
  by_cases h1 : c = ' '
  · subst h1; exact absurd h (by decide)
  by_cases h2 : c = '\t'
  · subst h2; exact absurd h (by decide)
  simp [isWs, h1, h2]

/-! ## Lists -/

theorem dropWhile_self {p : Char → Bool} {l : Bytes} (h : ∀ c ∈ l, p c = false) :
    l.dropWhile p = l := by
  cases l with
  | nil => rfl
  | cons c t => simp [List.dropWhile, h c (by simp)]

theorem mem_takeWhile {p : Char → Bool} : ∀ {l : Bytes} {c : Char}, c ∈ l.takeWhile p → p c = true
  | [], _, h => by simp at h
  | x :: t, c, h => by
    unfold List.takeWhile at h
    cases hx : p x with
    | false => simp [hx] at h
    | true =>
      simp only [hx, List.mem_cons] at h
      rcases h with rfl | h
      · exact hx
      · exact mem_takeWhile h

/-- A list is its part before the trailing `p` bytes, then those bytes. -/
theorem split_trailing (p : Char → Bool) (u : Bytes) :
    u = (u.reverse.dropWhile p).reverse ++ (u.reverse.takeWhile p).reverse ∧
      ∀ c ∈ (u.reverse.takeWhile p).reverse, p c = true := by
  refine ⟨?_, fun c hc => mem_takeWhile (List.mem_reverse.mp hc)⟩
  have h := congrArg List.reverse (List.takeWhile_append_dropWhile (p := p) (l := u.reverse))
  simp only [List.reverse_append, List.reverse_reverse] at h
  exact h.symm

/-! ## Lines -/

theorem scanLine_true_ok : ∀ {s l r : Bytes}, scanLine true s = .ok l r → l = [] ∧ s = '\n' :: r
  | [], _, _, h => by simp [scanLine] at h
  | c :: t, l, r, h => by
    unfold scanLine at h
    by_cases h1 : (c == '\n') = true
    · simp only [h1, ite_true] at h
      simp only [Line.ok.injEq] at h
      obtain ⟨rfl, rfl⟩ := h
      simp at h1
      simp [h1]
    · simp [h1] at h

/-- A line the reader accepts ends at a CR LF and holds no CR or LF. -/
theorem scanLine_ok : ∀ {s l r : Bytes}, scanLine false s = .ok l r →
    s = l ++ '\r' :: '\n' :: r ∧ ∀ c ∈ l, c ≠ '\r' ∧ c ≠ '\n'
  | [], _, _, h => by simp [scanLine] at h
  | c :: t, l, r, h => by
    unfold scanLine at h
    by_cases h1 : (c == '\n') = true
    · simp [h1] at h
    · simp only [h1, Bool.false_eq_true, ite_false] at h
      by_cases h3 : (c == '\r') = true
      · simp only [h3, ite_true] at h
        obtain ⟨rfl, rfl⟩ := scanLine_true_ok h
        simp at h3
        subst h3
        simp
      · simp only [h3, Bool.false_eq_true, ite_false] at h
        cases hs : scanLine false t with
        | ok l' r' =>
          rw [hs] at h
          simp only [Line.ok.injEq] at h
          obtain ⟨rfl, rfl⟩ := h
          obtain ⟨e, hl⟩ := scanLine_ok hs
          simp at h1 h3
          refine ⟨by simp [e], ?_⟩
          intro x hx
          simp only [List.mem_cons] at hx
          rcases hx with rfl | hx
          · exact ⟨h3, h1⟩
          · exact hl x hx
        | bad => rw [hs] at h; simp at h
        | incomplete => rw [hs] at h; simp at h

/-- A member finds the same line, in either line mode. -/
theorem nextLine_crlf (lf : Bool) : ∀ (l r : Bytes), (∀ c ∈ l, c ≠ '\r' ∧ c ≠ '\n') →
    nextLine lf (l ++ '\r' :: '\n' :: r) = some (l, r)
  | [], r, _ => by simp [nextLine]
  | c :: l, r, h => by
    have hc := h c (by simp)
    have ih := nextLine_crlf lf l r (fun x hx => h x (by simp [hx]))
    rw [List.cons_append]
    unfold nextLine
    split
    · simp_all
    · rename_i heq
      simp only [List.cons.injEq] at heq
      exact absurd heq.1 hc.1
    · rename_i heq
      simp only [List.cons.injEq] at heq
      obtain ⟨rfl, rfl⟩ := heq
      have : (lf && c == '\n') = false := by simp [hc.2]
      simp only [this, Bool.false_eq_true, ite_false, ih]
      rfl

/-! ## Fields -/

/-- The name of a header line: the bytes before its first colon. -/
def rawName (line : Bytes) : Bytes := line.takeWhile (· ≠ ':')

/-- The value of a header line as sent: the bytes after its first colon. -/
def rawValue (line : Bytes) : Bytes := (line.dropWhile (· ≠ ':')).drop 1

/-- The field the reader keeps for a line it accepts. -/
def readerField (line : Bytes) : Bytes × Bytes := (rawName line, trim (rawValue line))

/-- The value member `o` keeps for a field. -/
def memberValue (o : Opts) (v : Bytes) : Bytes :=
  let v := v.dropWhile isWs
  if o.trimValue then dropTrailingWs v else v

/-- A field line the reader accepts: what the reader keeps, a name of token bytes, a first byte
that is no whitespace, and a colon. -/
theorem parseField_ok {line : Bytes} {f : Bytes × Bytes} (h : parseField line = .ok f) :
    f = readerField line ∧ (∀ c ∈ rawName line, isTokenChar c = true) ∧
      ∃ c t x v, line = c :: t ∧ isBlank c = false ∧ line.dropWhile (· ≠ ':') = x :: v := by
  cases line with
  | nil => simp [parseField] at h
  | cons c t =>
    unfold parseField at h
    simp only at h
    split at h
    · simp at h
    · rename_i hb
      split at h
      · simp at h
      · rename_i x v hd
        split at h
        · simp at h
        · rename_i hn
          split at h
          · simp at h
          · simp only [Except.ok.injEq] at h
            subst h
            have hall : (List.takeWhile (fun x => decide (x ≠ ':')) (c :: t)).all isTokenChar = true := by
              cases h' : (List.takeWhile (fun x => decide (x ≠ ':')) (c :: t)).all isTokenChar
              · rw [h'] at hn; simp at hn
              · rfl
            refine ⟨?_, fun y hy => List.all_eq_true.mp hall y hy, c, t, x, v, rfl, by simpa using hb, hd⟩
            unfold readerField rawName rawValue
            rw [hd]
            rfl

/-- A member keeps a field line the reader accepts as one field, with the same name. -/
theorem addLine_ok (o : Opts) (acc : List (Bytes × Bytes)) {line : Bytes} {f : Bytes × Bytes}
    (h : parseField line = .ok f) :
    addLine o acc line = (rawName line, memberValue o (rawValue line)) :: acc := by
  obtain ⟨-, htok, c, t, x, v, rfl, hb, hd⟩ := parseField_ok h
  have hname : dropTrailingWs (rawName (c :: t)) = rawName (c :: t) := by
    unfold dropTrailingWs
    rw [dropWhile_self (fun y hy => tokenChar_not_ws (htok y (List.mem_reverse.mp hy)))]
    simp
  unfold addLine
  simp only [← isWs_eq] at hb
  simp only [hb, Bool.false_eq_true, ite_false]
  rw [hd]
  simp only [rawValue, hd, List.drop_succ_cons, List.drop_zero, memberValue]
  split
  · exact congrArg (fun n => (n, _) :: acc) hname
  · rfl

theorem foldl_addLine (o : Opts) : ∀ (lines : List Bytes) (acc : List (Bytes × Bytes)),
    (∀ l ∈ lines, parseField l = .ok (readerField l)) →
    lines.foldl (addLine o) acc =
      (lines.map fun l => (rawName l, memberValue o (rawValue l))).reverse ++ acc
  | [], _, _ => by simp
  | l :: ls, acc, h => by
    simp only [List.foldl_cons]
    rw [addLine_ok o acc (h l (by simp)), foldl_addLine o ls _ (fun l' hl' => h l' (by simp [hl']))]
    simp

/-- The member's value is the reader's, then whitespace the member did not trim. -/
theorem memberValue_split (o : Opts) (v : Bytes) :
    ∃ w, memberValue o v = trim v ++ w ∧ ∀ c ∈ w, isWs c = true := by
  unfold memberValue trim
  simp only [← isWs_eq]
  split
  · exact ⟨[], by simp [dropTrailingWs], by simp⟩
  · obtain ⟨e, hw⟩ := split_trailing isWs (v.dropWhile isWs)
    exact ⟨_, e, hw⟩

/-! ## Heads -/

/-- The fields the reader reads, as the lines a member reads up to the same empty line. -/
theorem readFields_lines (lf : Bool) : ∀ (fuel fuel' : Nat) (s : Bytes) {fs : List (Bytes × Bytes)}
    {rest : Bytes}, readFields fuel s = .ok fs rest → fuel ≤ fuel' →
    ∃ lines, headLines lf fuel' s = some (lines, rest) ∧
      (∀ l ∈ lines, parseField l = .ok (readerField l)) ∧ fs = lines.map readerField
  | 0, _, _, _, _, h, _ => by simp [readFields] at h
  | _ + 1, 0, _, _, _, _, hle => by omega
  | fuel + 1, fuel' + 1, s, fs, rest, h, hle => by
    unfold readFields at h
    cases hl : scanLine false s with
    | incomplete => simp [hl] at h
    | bad => simp [hl] at h
    | ok line r =>
      obtain ⟨hs, hnl⟩ := scanLine_ok hl
      simp only [hl] at h
      cases line with
      | nil =>
        simp only [Fields.ok.injEq] at h
        obtain ⟨rfl, rfl⟩ := h
        refine ⟨[], ?_, by simp, by simp⟩
        unfold headLines
        rw [hs, nextLine_crlf lf [] r (by simp)]
      | cons c l =>
        simp only at h
        cases hp : parseField (c :: l) with
        | error e => simp [hp] at h
        | ok f =>
          simp only [hp] at h
          cases hr : readFields fuel r with
          | ok fs' rest' =>
            simp only [hr, Fields.ok.injEq] at h
            obtain ⟨rfl, rfl⟩ := h
            obtain ⟨lines, hh, hall, hfs⟩ := readFields_lines lf fuel fuel' r hr (by omega)
            have hf := (parseField_ok hp).1
            refine ⟨(c :: l) :: lines, ?_, ?_, ?_⟩
            · unfold headLines
              rw [hs, nextLine_crlf lf (c :: l) r hnl]
              simp [hh]
            · intro x hx
              simp only [List.mem_cons] at hx
              rcases hx with rfl | hx
              · rw [hp, hf]
              · exact hall x hx
            · simp [hfs, hf]
          | bad m => simp [hr] at h
          | incomplete => simp [hr] at h

/-- The head the reader reads, as the lines a member reads: the request line, then one line per
field. -/
theorem readHead_lines (lf : Bool) {s : Bytes} {hd : Head} (h : readHead s = .ok hd) :
    ∃ lines, headLines lf ((s.dropWhile isNewline).length + 1) (s.dropWhile isNewline) =
        some (lines, hd.after) ∧
      (∀ l ∈ lines.drop 1, parseField l = .ok (readerField l)) ∧
      hd.headers = (lines.drop 1).map readerField := by
  unfold readHead at h
  generalize hd0 : s.dropWhile isNewline = d at h ⊢
  simp only at h
  cases hl : scanLine false d with
  | incomplete => simp [hl] at h
  | bad => simp [hl] at h
  | ok line afterLine =>
    obtain ⟨hs, hnl⟩ := scanLine_ok hl
    simp only [hl] at h
    cases hp : parseRequestLine line with
    | error e => obtain ⟨_, _⟩ := e; simp [hp] at h
    | ok v =>
      obtain ⟨method, target, http10⟩ := v
      simp only [hp] at h
      cases hf : readFields (afterLine.length + 1) afterLine with
      | incomplete => simp [hf] at h
      | bad m => simp [hf] at h
      | ok headers after =>
        simp only [hf, HeadResult.ok.injEq] at h
        subst h
        have hlen : afterLine.length + 1 ≤ d.length := by rw [hs]; simp; omega
        obtain ⟨lines, hh, hall, hfs⟩ := readFields_lines lf _ d.length afterLine hf (by omega)
        cases line with
        | nil =>
          rw [show parseRequestLine [] = .error (400, "Malformed request line") from rfl] at hp
          cases hp
        | cons c l =>
          refine ⟨(c :: l) :: lines, ?_, by simpa using hall, by simpa using hfs⟩
          conv => lhs; arg 3; rw [hs]
          unfold headLines
          rw [nextLine_crlf lf (c :: l) afterLine hnl]
          simp only
          rw [show d.length = d.length from rfl] at hh
          simp [hh]

/-! ## Framing headers -/

/-- The field lines named `n`, ignoring ASCII case. -/
def linesNamed (lines : List Bytes) (n : String) : List Bytes :=
  lines.filter fun l => lower (rawName l) == n.toList

theorem values_eq (lines : List Bytes) (n : String) :
    values (lines.map readerField) n = (linesNamed lines n).map fun l => trim (rawValue l) := by
  unfold values linesNamed
  rw [List.filter_map, List.map_map]
  rfl

theorem member_values (o : Opts) (lines : List Bytes) (n : String) :
    ((lines.map fun l => (rawName l, memberValue o (rawValue l))).filter (named ·.1 n)).map (·.2) =
      (linesNamed lines n).map fun l => memberValue o (rawValue l) := by
  unfold linesNamed
  rw [List.filter_map, List.map_map]
  simp only [Function.comp_def, named_eq]

theorem frame_chunked {maxSize : Nat} {http10 : Bool} {headers : List (Bytes × Bytes)}
    (h : frame maxSize http10 headers = .ok .chunked) : values headers "transfer-encoding" ≠ [] := by
  intro hte
  unfold frame at h
  rw [hte] at h
  simp only [List.isEmpty_nil, Bool.not_true, Bool.false_eq_true, ite_false] at h
  repeat' split at h
  all_goals simp_all

theorem frame_length {maxSize n : Nat} {http10 : Bool} {headers : List (Bytes × Bytes)}
    (h : frame maxSize http10 headers = .ok (.length n)) :
    values headers "transfer-encoding" = [] ∧
      ((values headers "content-length" = [] ∧ n = 0) ∨
        ∃ v, values headers "content-length" = [v] ∧ v ≠ [] ∧ v.all Char.isDigit = true ∧
          n = digitsValue v) := by
  cases hte : values headers "transfer-encoding" with
  | cons _ _ => exact absurd h (transfer_encoding_wins (by simp [hte]))
  | nil =>
    refine ⟨rfl, ?_⟩
    unfold frame at h
    rw [hte] at h
    simp only [List.isEmpty_nil, Bool.not_true, Bool.false_eq_true, ite_false] at h
    cases hcl : values headers "content-length" with
    | nil =>
      rw [hcl] at h
      simp only [Except.ok.injEq, Framing.length.injEq] at h
      exact Or.inl ⟨rfl, h.symm⟩
    | cons v vs =>
      cases vs with
      | cons _ _ => rw [hcl] at h; simp at h
      | nil =>
        rw [hcl] at h
        simp only at h
        split at h
        · simp at h
        · split at h
          · simp at h
          · rename_i h2
            split at h
            · simp at h
            · split at h
              · simp at h
              · simp only [Except.ok.injEq, Framing.length.injEq] at h
                simp only [Bool.or_eq_true, Bool.not_eq_eq_eq_not, Bool.not_true, not_or] at h2
                refine Or.inr ⟨v, rfl, by simpa using h2.1, ?_, h.symm⟩
                cases h' : v.all Char.isDigit
                · rw [h'] at h2; simp at h2
                · rfl

theorem keepAlive_chunked {http10 : Bool} {headers : List (Bytes × Bytes)}
    (h : keepAlive http10 true headers = true) : values headers "content-length" = [] := by
  cases hcl : values headers "content-length" with
  | nil => rfl
  | cons _ _ => rw [chunked_with_length_closes (by simp [hcl])] at h; cases h

theorem readBody_keep {maxSize : Nat} {hd : Head} {m t : String} {b rest : Bytes} {fr : Framing}
    (h : readBody maxSize hd = (.req m t true b, rest))
    (hf : frame maxSize hd.http10 hd.headers = .ok fr) :
    keepAlive hd.http10 (fr == .chunked) hd.headers = true := by
  unfold readBody at h
  simp only [hf] at h
  repeat' split at h
  all_goals simp_all

theorem decimal_append {v w : Bytes} (hv : v ≠ []) (hd : v.all Char.isDigit = true)
    (hw : ∀ c ∈ w, isWs c = true) :
    decimal (v ++ w) = none ∨ decimal (v ++ w) = some (digitsValue v) := by
  have hdig : Char.isDigit = fun c => '0' ≤ c && c ≤ '9' := by
    funext c; simp [Char.isDigit, Char.le_def]
  cases w with
  | nil =>
    right
    simp only [List.append_nil]
    unfold decimal
    rw [hdig] at hd
    have : v.isEmpty = false := by cases v <;> simp_all
    simp only [this, hd, Bool.not_true, Bool.or_false, Bool.false_eq_true, ite_false]
    rfl
  | cons x w =>
    left
    unfold decimal
    have hx := hw x (by simp)
    have : ('0' ≤ x && x ≤ '9') = false := by
      simp only [isWs, Bool.or_eq_true, beq_iff_eq] at hx
      rcases hx with rfl | rfl <;> decide
    have hall : (v ++ x :: w).all (fun c => '0' ≤ c && c ≤ '9') = false := by
      rw [List.all_append]
      simp [this]
    simp [hall]

/-! ## The theorem -/

/-- A member reads the head the reader accepted: it rejects, or agrees on the head length and the
framing, given that the reader keeps the connection open. -/
theorem members_agree (o : Opts) {maxSize : Nat} {s : Bytes} {hd : Head} {fr : Framing}
    (hh : readHead s = .ok hd) (hf : frame maxSize hd.http10 hd.headers = .ok fr)
    (hk : keepAlive hd.http10 (fr == .chunked) hd.headers = true) :
    interp o s = none ∨ interp o s = some (s.length - hd.after.length, fr) := by
  obtain ⟨lines, hl, hall, hhd⟩ := readHead_lines o.lfLines hh
  have hl' : headLines o.lfLines ((s.dropWhile fun c => c == '\r' || c == '\n').length + 1)
      (s.dropWhile fun c => c == '\r' || c == '\n') = some (lines, hd.after) := hl
  have hfields : ((lines.drop 1).foldl (addLine o) []).reverse =
      (lines.drop 1).map fun l => (rawName l, memberValue o (rawValue l)) := by
    rw [foldl_addLine o _ [] hall]
    simp
  have mte := member_values o (lines.drop 1) "transfer-encoding"
  have mcl := member_values o (lines.drop 1) "content-length"
  have rte : values hd.headers "transfer-encoding" =
      (linesNamed (lines.drop 1) "transfer-encoding").map fun l => trim (rawValue l) := by
    rw [hhd, values_eq]
  have rcl : values hd.headers "content-length" =
      (linesNamed (lines.drop 1) "content-length").map fun l => trim (rawValue l) := by
    rw [hhd, values_eq]
  unfold interp
  simp only [hl', Option.bind_some, bind, hfields, mte, mcl]
  generalize linesNamed (lines.drop 1) "transfer-encoding" = TE at rte ⊢
  generalize linesNamed (lines.drop 1) "content-length" = CL at rcl ⊢
  cases fr with
  | chunked =>
    have hte := frame_chunked hf
    have hcl := keepAlive_chunked (by simpa using hk)
    rw [rcl, List.map_eq_nil_iff] at hcl
    subst hcl
    cases TE with
    | nil => simp at rte; exact absurd rte hte
    | cons l ls =>
      simp only [List.map_cons, List.isEmpty_cons, List.map_nil, List.isEmpty_nil]
      split <;> simp
  | length n =>
    obtain ⟨hte, hcl⟩ := frame_length hf
    rw [rte, List.map_eq_nil_iff] at hte
    subst hte
    simp only [List.map_nil, List.isEmpty_nil]
    rcases hcl with ⟨hcl, rfl⟩ | ⟨v, hcl, hv, hdig, rfl⟩
    · rw [rcl, List.map_eq_nil_iff] at hcl
      subst hcl
      simp
    · rw [rcl] at hcl
      match CL, hcl with
      | [l], hcl =>
        simp only [List.map_cons, List.map_nil, List.cons.injEq, and_true] at hcl
        obtain ⟨w, hw, hws⟩ := memberValue_split o (rawValue l)
        rw [hcl] at hw
        simp only [List.map_cons, List.map_nil, List.getLast?_singleton, List.head?_cons, ite_self, hw]
        rcases decimal_append hv hdig hws with h | h <;> simp [h]

/-- No request smuggling, relative to the family: whenever the reader accepts a request and keeps
the connection open after it, every member of the family rejects the head or finds the same head
length and the same framing (`agrees`). -/
theorem no_smuggling (maxSize : Nat) (s : Bytes) (r : Nat × Framing) (o : Opts)
    (h : readerFraming maxSize s = some r) : agrees r o s = true := by
  unfold readerFraming at h
  cases hh : readHead s with
  | fail _ => simp [hh] at h
  | ok hd =>
    simp only [hh] at h
    have hro : readOne maxSize s = readBody maxSize hd := by simp [readOne, hh]
    rw [hro] at h
    cases hb : readBody maxSize hd with
    | mk out rest =>
      rw [hb] at h
      cases hf : frame maxSize hd.http10 hd.headers with
      | error e => rw [hf] at h; split at h <;> simp_all
      | ok fr =>
        rw [hf] at h
        match out, h with
        | .req m t true b, h =>
          simp only [Option.some.injEq] at h
          subst h
          have hk := readBody_keep hb hf
          unfold agrees
          rcases members_agree o hh hf hk with e | e <;> simp [e]
        | .req _ _ false _, h => simp at h
        | .bad _ _, h => simp at h
        | .incomplete _, h => simp at h
        | .closed, h => simp at h

/-- The hypothesis holds for real requests: the reader frames this one by its length and keeps the
connection open, so `no_smuggling` applies to it. -/
example : readerFraming 100 "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 2\r\n\r\nok".toList =
    some (47, .length 2) := by
  decide

end UniSpec.HttpSmuggle
