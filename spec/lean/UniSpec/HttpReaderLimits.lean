import UniSpec.HttpReaderExtend
import UniSpec.HttpSmuggleProof

/-!
# The size limits bound what the reader buffers

`readHead_reads`: once `maxLine + maxHeader + 6` bytes follow the empty lines before a request,
the reader has decided its head, and more bytes never change that decision. So a client cannot make
the reader hold more of a head than that, however slowly it sends it.

The `6` is the CR LF after the request line, plus up to four bytes past the header limit: the CR LF
of a last line that ends at the limit and the CR LF of the empty line, which the limit allows.

`requestLine_tooLong`: a request line longer than `maxLine` gets 414, whatever follows it.
-/

namespace UniSpec.HttpReader

open UniSpec.HttpSmuggle (scanLine_ok)

/-- With `left + 2` bytes, a line scan has decided: a line, a bad line end, or a line too long. -/
theorem scanLine_decided : ∀ (cr : Bool) (left : Nat) (s : Bytes), left + 2 ≤ s.length →
    scanLine cr left s ≠ .incomplete
  | _, _, [], h => by simp at h
  | cr, left, c :: t, h => by
    unfold scanLine
    by_cases h1 : (c == '\n') = true
    · simp only [h1, ite_true]; split <;> simp
    · simp only [h1, Bool.false_eq_true, ite_false]
      cases cr
      · simp only [Bool.false_eq_true, ite_false]
        by_cases h3 : (c == '\r') = true
        · simp only [h3, ite_true]
          cases t with
          | nil => simp at h
          | cons d u =>
            unfold scanLine
            by_cases h5 : (d == '\n') = true
            · simp [h5]
            · simp [h5]
        · simp only [h3, Bool.false_eq_true, ite_false]
          by_cases h4 : (left == 0) = true
          · simp [h4]
          · simp only [h4, Bool.false_eq_true, ite_false]
            have hl : left ≠ 0 := by simpa using h4
            have := scanLine_decided false (left - 1) t (by simp at h; omega)
            cases hs : scanLine false (left - 1) t with
            | incomplete => exact absurd hs this
            | _ => simp
      · simp

/-- A line fits in the bytes before the limit. -/
theorem scanLine_ok_length : ∀ {left : Nat} {s l r : Bytes}, scanLine false left s = .ok l r →
    l.length ≤ left
  | _, [], _, _, h => by simp [scanLine] at h
  | left, c :: t, l, r, h => by
    unfold scanLine at h
    by_cases h1 : (c == '\n') = true
    · simp [h1] at h
    · simp only [h1, Bool.false_eq_true, ite_false] at h
      by_cases h3 : (c == '\r') = true
      · simp only [h3, ite_true] at h
        obtain ⟨rfl, -⟩ := UniSpec.HttpSmuggle.scanLine_true_ok h
        simp
      · simp only [h3, Bool.false_eq_true, ite_false] at h
        by_cases h4 : (left == 0) = true
        · simp [h4] at h
        · simp only [h4, Bool.false_eq_true, ite_false] at h
          have hl : left ≠ 0 := by simpa using h4
          cases hs : scanLine false (left - 1) t with
          | ok l' r' =>
            rw [hs] at h
            simp only [Line.ok.injEq] at h
            obtain ⟨rfl, rfl⟩ := h
            have := scanLine_ok_length hs
            simp; omega
          | _ => rw [hs] at h; simp at h

/-- Header lines with `left` bytes before the limit have decided after `left + 4` bytes, or after
two once the limit is reached: past it, only the CR LF that ends a line and the empty line may
come. -/
theorem readFields_decided : ∀ (fuel left : Nat) (s : Bytes), s.length < fuel →
    (left + 4 ≤ s.length ∨ (left = 0 ∧ 2 ≤ s.length)) → readFields fuel left s ≠ .incomplete
  | 0, _, _, hf, _ => by omega
  | fuel + 1, left, s, hf, hb => by
    have hs : left + 2 ≤ s.length := by omega
    unfold readFields
    have hd := scanLine_decided false left s hs
    cases hl : scanLine false left s with
    | incomplete => exact absurd hl hd
    | bad => simp
    | tooLong => simp
    | ok line rest =>
      obtain ⟨e, -⟩ := scanLine_ok hl
      have hlen := scanLine_ok_length hl
      have hsl : s.length = line.length + 2 + rest.length := by rw [e]; simp; omega
      cases line with
      | nil => simp
      | cons c l =>
        simp only
        cases hp : parseField (c :: l) with
        | error _ => simp
        | ok f =>
          simp only
          have hlt : rest.length < fuel := by simp at hsl; omega
          have hne := readFields_decided fuel (left - ((c :: l).length + 2)) rest hlt (by
            simp only [List.length_cons] at hsl hlen ⊢
            omega)
          cases hr : readFields fuel (left - ((c :: l).length + 2)) rest with
          | incomplete => exact absurd hr hne
          | _ => simp

/-- With `maxLine + maxHeader + 6` bytes after the empty lines before it, the head has decided. -/
theorem readHead_decided (lim : Limits) (s : Bytes)
    (h : lim.maxLine + lim.maxHeader + 6 ≤ (s.dropWhile isNewline).length) :
    ∀ o, readHead lim s = .fail o → o.atEof = false := by
  intro o ho
  unfold readHead at ho
  generalize hd : s.dropWhile isNewline = d at h ho
  simp only at ho
  split at ho
  · simp only [HeadResult.fail.injEq] at ho; subst ho; rfl
  have hdec := scanLine_decided false lim.maxLine d (by omega)
  cases hl : scanLine false lim.maxLine d with
  | incomplete => exact absurd hl hdec
  | bad => rw [hl] at ho; simp only [HeadResult.fail.injEq] at ho; subst ho; rfl
  | tooLong => rw [hl] at ho; simp only [HeadResult.fail.injEq] at ho; subst ho; rfl
  | ok line after =>
    rw [hl] at ho
    obtain ⟨e, -⟩ := scanLine_ok hl
    have hlen := scanLine_ok_length hl
    have hdl : d.length = line.length + 2 + after.length := by rw [e]; simp; omega
    simp only at ho
    cases hp : parseRequestLine line with
    | error err =>
      obtain ⟨_, _⟩ := err
      rw [hp] at ho; simp only [HeadResult.fail.injEq] at ho; subst ho; rfl
    | ok v =>
      obtain ⟨method, target, http10⟩ := v
      rw [hp] at ho
      simp only at ho
      have hne := readFields_decided (after.length + 1) lim.maxHeader after (by omega)
        (Or.inl (by omega))
      cases hf : readFields (after.length + 1) lim.maxHeader after with
      | incomplete => exact absurd hf hne
      | ok _ _ => rw [hf] at ho; simp at ho
      | _ => rw [hf] at ho; simp only [HeadResult.fail.injEq] at ho; subst ho; rfl

/-- The reader decides a head within `maxLine + maxHeader + 6` bytes after the empty lines before
it, and bytes after them never change the decision. -/
theorem readHead_reads (lim : Limits) (s t : Bytes)
    (h : lim.maxLine + lim.maxHeader + 6 ≤ (s.dropWhile isNewline).length) :
    readHead lim (s ++ t) = (readHead lim s).extend t :=
  readHead_extend lim s t (readHead_decided lim s h)

/-- A line longer than the limit is too long, whatever follows it. -/
theorem scanLine_tooLong : ∀ (left : Nat) (l t : Bytes), (∀ c ∈ l, c ≠ '\r' ∧ c ≠ '\n') →
    left < l.length → scanLine false left (l ++ t) = .tooLong
  | _, [], _, _, h => by simp at h
  | left, c :: l, t, hc, h => by
    have ⟨h1, h2⟩ := hc c (by simp)
    rw [List.cons_append]
    unfold scanLine
    have e1 : (c == '\n') = false := by simp [h2]
    have e3 : (c == '\r') = false := by simp [h1]
    simp only [e1, e3, Bool.false_eq_true, ite_false]
    by_cases h4 : (left == 0) = true
    · simp [h4]
    · simp only [h4, Bool.false_eq_true, ite_false]
      have hl : left ≠ 0 := by simpa using h4
      rw [scanLine_tooLong (left - 1) l t (fun x hx => hc x (by simp [hx])) (by simp at h; omega)]

/-- A request line longer than `maxLine`, after empty lines only, gets 414, whatever follows it. -/
theorem requestLine_tooLong (lim : Limits) (s l t : Bytes)
    (hs : s.dropWhile isNewline = l ++ t) (hc : ∀ c ∈ l, c ≠ '\r' ∧ c ≠ '\n')
    (hlen : lim.maxLine < l.length)
    (hcr : bareCR (s.takeWhile isNewline ++ (l ++ t).take 1) = false) :
    readHead lim s = .fail (.bad 414 "Request line too long") := by
  unfold readHead
  simp only [hs, hcr, Bool.false_eq_true, ite_false, scanLine_tooLong lim.maxLine l t hc hlen]

end UniSpec.HttpReader
