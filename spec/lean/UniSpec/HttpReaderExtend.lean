import UniSpec.HttpReader

/-!
# More bytes never change a decided outcome

`HttpReaderSpecTest` feeds each byte stream to `HttpConnectionReader` in reads of several sizes and
expects the outcomes `readAll` gives for the whole stream. That only makes sense if an outcome is
fixed by the bytes before it: once the model has decided a request or a rejection, the bytes after
it cannot change that decision. This file proves it.

`Outcome.atEof` marks the outcomes the end of the input decides: `closed` and `incomplete`. Every
other outcome stays the same when more bytes follow (`readOne_extend`), and a request leaves the
extra bytes for the next one. So `readAll` gives the same outcomes for `s ++ t` as for `s` whenever
its last outcome on `s` was not decided by the end of `s` (`readAll_extend`).

Each scanner gets one lemma: if its result on `s` is not `incomplete`, its result on `s ++ t` is
the same, with `t` after the bytes it left (`extend t`).
-/

namespace UniSpec.HttpReader

/-! ## Results with more bytes after them -/

def Line.extend (t : Bytes) : Line → Line
  | .ok l r => .ok l (r ++ t)
  | o => o

def Fields.extend (t : Bytes) : Fields → Fields
  | .ok fs r => .ok fs (r ++ t)
  | o => o

def Size.extend (t : Bytes) : Size → Size
  | .ok size digits r => .ok size digits (r ++ t)
  | .tooLarge r => .tooLarge (r ++ t)
  | o => o

def Ext.extend (t : Bytes) : Ext → Ext
  | .ok r => .ok (r ++ t)
  | o => o

def Body.extend (t : Bytes) : Body → Body
  | .ok body r => .ok body (r ++ t)
  | o => o

def HeadResult.extend (t : Bytes) : HeadResult → HeadResult
  | .ok hd => .ok { hd with after := hd.after ++ t }
  | o => o

/-! ## Lines and fields -/

theorem scanLine_extend : ∀ (cr : Bool) (left : Nat) (s t : Bytes),
    scanLine cr left s ≠ .incomplete → scanLine cr left (s ++ t) = (scanLine cr left s).extend t
  | _, _, [], _, h => by simp [scanLine] at h
  | cr, left, c :: s, t, h => by
    rw [List.cons_append]
    unfold scanLine at h ⊢
    by_cases h1 : (c == '\n') = true
    · simp only [h1, ite_true] at h ⊢
      split <;> rfl
    · simp only [h1, Bool.false_eq_true, ite_false] at h ⊢
      by_cases h2 : cr = true
      · simp only [h2, ite_true]; rfl
      · simp only [h2, Bool.false_eq_true, ite_false] at h ⊢
        by_cases h3 : (c == '\r') = true
        · simp only [h3, ite_true] at h ⊢
          exact scanLine_extend true _ s t h
        · simp only [h3, Bool.false_eq_true, ite_false] at h ⊢
          by_cases h4 : (left == 0) = true
          · simp only [h4, ite_true]; rfl
          · simp only [h4, Bool.false_eq_true, ite_false] at h ⊢
            cases hs : scanLine false (left - 1) s with
            | incomplete => simp [hs] at h
            | _ => rw [scanLine_extend false _ s t (by simp [hs]), hs]; rfl

theorem readFields_extend : ∀ (fuel fuel' left : Nat) (s t : Bytes), fuel ≤ fuel' →
    readFields fuel left s ≠ .incomplete →
    readFields fuel' left (s ++ t) = (readFields fuel left s).extend t
  | 0, _, _, _, _, _, h => by simp [readFields] at h
  | _ + 1, 0, _, _, _, hle, _ => by omega
  | fuel + 1, fuel' + 1, left, s, t, hle, h => by
    unfold readFields at h ⊢
    cases hl : scanLine false left s with
    | incomplete => simp [hl] at h
    | bad => rw [scanLine_extend false left s t (by simp [hl]), hl]; rfl
    | tooLong => rw [scanLine_extend false left s t (by simp [hl]), hl]; rfl
    | ok line rest =>
      rw [scanLine_extend false left s t (by simp [hl]), hl]
      simp only [hl] at h
      cases line with
      | nil => rfl
      | cons c l =>
        simp only [Line.extend] at h ⊢
        cases hp : parseField (c :: l) with
        | error e => simp only; rfl
        | ok f =>
          simp only [hp] at h ⊢
          generalize left - ((c :: l).length + 2) = left' at h ⊢
          cases hr : readFields fuel left' rest with
          | incomplete => rw [hr] at h; simp at h
          | _ =>
            rw [readFields_extend fuel fuel' _ rest t (by omega) (by rw [hr]; simp), hr]; rfl

/-! ## Chunk sizes and extensions -/

theorem restOfSize_extend : ∀ (left size : Nat) (s t : Bytes), restOfSize left size s ≠ .incomplete →
    restOfSize left size (s ++ t) = (restOfSize left size s).extend t
  | _, _, [], _, h => by simp [restOfSize] at h
  | left, size, c :: s, t, h => by
    rw [List.cons_append]
    unfold restOfSize at h ⊢
    cases hv : hexValue c with
    | none => simp only [hv] at h ⊢; split <;> rfl
    | some v =>
      simp only [hv] at h ⊢
      split
      · rfl
      · rename_i h0
        simp only [h0, Bool.false_eq_true, ite_false] at h
        exact restOfSize_extend _ _ s t h

theorem restOfSize_tooLarge_ne_nil : ∀ {left size : Nat} {s r : Bytes},
    restOfSize left size s = .tooLarge r → r ≠ []
  | _, _, [], _, h => by simp [restOfSize] at h
  | left, size, c :: s, r, h => by
    unfold restOfSize at h
    cases hv : hexValue c with
    | none =>
      simp only [hv] at h
      split at h
      · simp at h
      · simp only [Size.tooLarge.injEq] at h; subst h; simp
    | some v =>
      simp only [hv] at h
      split at h
      · simp at h
      · exact restOfSize_tooLarge_ne_nil h

theorem readSize_extend : ∀ (maxLeft left size digits : Nat) (s t : Bytes),
    readSize maxLeft left size digits s ≠ .incomplete →
    readSize maxLeft left size digits (s ++ t) = (readSize maxLeft left size digits s).extend t
  | _, _, _, _, [], _, h => by simp [readSize] at h
  | maxLeft, left, size, digits, c :: s, t, h => by
    rw [List.cons_append]
    unfold readSize at h ⊢
    cases hv : hexValue c with
    | none => simp only; rfl
    | some v =>
      simp only [hv] at h ⊢
      split
      · rfl
      · rename_i h0
        simp only [h0, Bool.false_eq_true, ite_false] at h
        split
        · rename_i hgt
          simp only [hgt, ite_true] at h
          exact restOfSize_extend _ _ s t h
        · rename_i hgt
          simp only [hgt, ite_false] at h
          exact readSize_extend maxLeft _ _ _ s t h

/-- The bytes after a size are never empty: the size ends at a byte that is not a hex digit. -/
theorem readSize_rest_ne_nil : ∀ {maxLeft left size digits : Nat} {s : Bytes},
    (∀ sz d r, readSize maxLeft left size digits s = .ok sz d r → r ≠ []) ∧
      (∀ r, readSize maxLeft left size digits s = .tooLarge r → r ≠ [])
  | _, _, _, _, [] => by simp [readSize]
  | maxLeft, left, size, digits, c :: s => by
    unfold readSize
    cases hv : hexValue c with
    | none => simp
    | some v =>
      simp only
      split
      · simp
      · split
        · exact ⟨fun _ _ _ h => absurd h restOfSize_not_ok, fun _ h => restOfSize_tooLarge_ne_nil h⟩
        · exact readSize_rest_ne_nil

theorem skipExt_extend : ∀ (left : Nat) (s t : Bytes), skipExt left s ≠ .incomplete →
    skipExt left (s ++ t) = (skipExt left s).extend t
  | _, [], _, h => by simp [skipExt] at h
  | left, c :: s, t, h => by
    rw [List.cons_append]
    unfold skipExt at h ⊢
    split
    · rfl
    · rename_i h1
      simp only [h1] at h
      split
      · rfl
      · rename_i h2
        simp only [h2] at h
        split
        · rfl
        · rename_i h3
          simp only [h3] at h
          exact skipExt_extend _ s t h

/-- `skipChunkExt []` is `ok []`, which a `;` after it would change, so the lemma needs a byte. -/
theorem skipChunkExt_extend {left : Nat} {s : Bytes} (t : Bytes) (hs : s ≠ [])
    (h : skipChunkExt left s ≠ .incomplete) :
    skipChunkExt left (s ++ t) = (skipChunkExt left s).extend t := by
  match s, hs with
  | c :: s, _ =>
    rw [List.cons_append]
    by_cases hc : c = ';'
    · subst hc
      simp only [skipChunkExt] at h ⊢
      exact skipExt_extend _ s t h
    · have e1 : skipChunkExt left (c :: (s ++ t)) = .ok (c :: (s ++ t)) := by
        unfold skipChunkExt; split
        · rename_i heq; simp at heq; exact absurd heq.1 hc
        · rfl
      have e2 : skipChunkExt left (c :: s) = .ok (c :: s) := by
        unfold skipChunkExt; split
        · rename_i heq; simp at heq; exact absurd heq.1 hc
        · rfl
      rw [e1, e2]; rfl

theorem crlf_extend (r t : Bytes) (h : crlf r ≠ .incomplete) : crlf (r ++ t) = (crlf r).extend t := by
  match r, h with
  | a :: b :: r, _ => simp only [List.cons_append, crlf]; split <;> rfl
  | [_], h => simp [crlf] at h
  | [], h => simp [crlf] at h

theorem crlf_not_tooLong (r : Bytes) : crlf r ≠ .tooLong := by
  unfold crlf; split
  · split <;> simp
  · simp

theorem chunkTooLarge_extend {left : Nat} {r : Bytes} (t : Bytes) (hr : r ≠ [])
    (h : chunkTooLarge left r ≠ .incomplete) :
    chunkTooLarge left (r ++ t) = chunkTooLarge left r := by
  unfold chunkTooLarge at h ⊢
  cases he : skipChunkExt left r with
  | incomplete => simp [he] at h
  | ok r2 =>
    rw [skipChunkExt_extend t hr (by simp [he]), he]
    simp only [he] at h
    simp only [Ext.extend]
    cases hc : crlf r2 with
    | incomplete => simp [hc] at h
    | tooLong => exact absurd hc (crlf_not_tooLong r2)
    | _ => rw [crlf_extend r2 t (by simp [hc]), hc]; rfl
  | _ => rw [skipChunkExt_extend t hr (by simp [he]), he]; rfl

/-! ## Chunked bodies -/

theorem readChunks_extend (lim : Limits) :
    ∀ (fuel fuel' total : Nat) (acc s t : Bytes), fuel ≤ fuel' →
      readChunks lim fuel total acc s ≠ .incomplete →
      readChunks lim fuel' total acc (s ++ t) = (readChunks lim fuel total acc s).extend t
  | 0, _, _, _, _, _, _, h => by simp [readChunks] at h
  | _ + 1, 0, _, _, _, _, hle, _ => by omega
  | fuel + 1, fuel' + 1, total, acc, s, t, hle, h => by
    unfold readChunks at h ⊢
    simp only at h ⊢
    have ⟨hok, htl⟩ := readSize_rest_ne_nil (maxLeft := lim.maxBody - total) (left := lim.maxLine)
      (size := 0) (digits := 0) (s := s)
    have hleft : ∀ r : Bytes, (s ++ t).length - (r ++ t).length = s.length - r.length := by
      intro r; simp only [List.length_append]; omega
    cases hs : readSize (lim.maxBody - total) lim.maxLine 0 0 s with
    | incomplete => simp [hs] at h
    | overflow => rw [readSize_extend _ _ _ _ s t (by simp [hs]), hs]; rfl
    | tooLong => rw [readSize_extend _ _ _ _ s t (by simp [hs]), hs]; rfl
    | tooLarge r =>
      rw [readSize_extend _ _ _ _ s t (by simp [hs]), hs]
      simp only [hs] at h
      simp only [Size.extend, hleft]
      rw [chunkTooLarge_extend t (htl r hs) h]
      cases hc : chunkTooLarge (lim.maxLine - (s.length - r.length)) r with
      | ok b r' => exact absurd hc chunkTooLarge_not_ok
      | _ => rfl
    | ok size digits r =>
      rw [readSize_extend _ _ _ _ s t (by simp [hs]), hs]
      simp only [hs] at h
      simp only [Size.extend, hleft]
      split
      · rfl
      · rename_i hd
        simp only [hd] at h
        have hr := hok size digits r hs
        cases he : skipChunkExt (lim.maxLine - (s.length - r.length)) r with
        | incomplete => simp [he] at h
        | ok r2 =>
          rw [skipChunkExt_extend t hr (by simp [he]), he]
          simp only [he] at h
          simp only [Ext.extend]
          cases hc : crlf r2 with
          | incomplete => simp [hc] at h
          | tooLong => exact absurd hc (crlf_not_tooLong r2)
          | bad => rw [crlf_extend r2 t (by simp [hc]), hc]; rfl
          | ok r3 =>
            rw [crlf_extend r2 t (by simp [hc]), hc]
            simp only [hc] at h
            simp only [Ext.extend]
            by_cases hz : (size == 0) = true
            · simp only [hz, ite_true] at h ⊢
              cases hf : readFields (r3.length + 1) lim.maxHeader r3 with
              | incomplete => simp [hf] at h
              | _ =>
                rw [readFields_extend (r3.length + 1) ((r3 ++ t).length + 1) _ r3 t (by simp)
                  (by simp [hf]), hf]; rfl
            · simp only [hz, Bool.false_eq_true, ite_false] at h ⊢
              by_cases hlen : r3.length < size + 2
              · simp [hlen] at h
              · have hlen' : ¬ (r3 ++ t).length < size + 2 := by simp; omega
                simp only [hlen, hlen', ite_false] at h ⊢
                have e1 : ((r3 ++ t).drop size).take 2 = (r3.drop size).take 2 := by
                  rw [List.drop_append_of_le_length (by omega),
                    List.take_append_of_le_length (by simp; omega)]
                have e2 : (r3 ++ t).take size = r3.take size :=
                  List.take_append_of_le_length (by omega)
                have e3 : (r3 ++ t).drop (size + 2) = r3.drop (size + 2) ++ t :=
                  List.drop_append_of_le_length (by omega)
                rw [e1, e2, e3]
                by_cases hc2 : ((r3.drop size).take 2 == ['\r', '\n']) = true
                · simp only [hc2, ite_true] at h ⊢
                  exact readChunks_extend lim fuel fuel' _ _ _ t (by omega) h
                · simp only [hc2, Bool.false_eq_true, ite_false]; rfl
        | _ => rw [skipChunkExt_extend t hr (by simp [he]), he]; rfl

/-! ## Requests -/

theorem readHead_extend (lim : Limits) (s t : Bytes)
    (h : ∀ o, readHead lim s = .fail o → o.atEof = false) :
    readHead lim (s ++ t) = (readHead lim s).extend t := by
  unfold readHead at h ⊢
  rw [List.dropWhile_append]
  have htw := List.takeWhile_append (p := isNewline) (xs := s) (ys := t)
  have hsplit := List.takeWhile_append_dropWhile (p := isNewline) (l := s)
  generalize hd : s.dropWhile isNewline = d at h ⊢ hsplit
  cases d with
  | nil =>
    -- Only CR and LF so far: a bare CR among them stays bare, and otherwise the end decided
    simp only [List.append_nil] at hsplit
    rw [hsplit] at htw h
    simp only [ite_true] at htw
    rw [htw, hsplit]
    simp only [List.isEmpty_nil, ite_true, List.take_nil, List.append_nil] at h ⊢
    by_cases hb : bareCR s = true
    · simp only [hb, bareCR_append _ (bareCR_append _ hb), ite_true]; rfl
    · have := h .closed (by simp [hb, scanLine])
      simp [Outcome.atEof] at this
  | cons c d =>
    have hne : (s.takeWhile isNewline).length ≠ s.length := by
      have := congrArg List.length hsplit
      simp at this
      omega
    simp only [hne, ite_false] at htw
    simp only [List.isEmpty_cons, Bool.false_eq_true, ite_false]
    rw [htw, show List.take 1 ((c :: d) ++ t) = [c] from rfl]
    simp only at h ⊢
    rw [show List.take 1 (c :: d) = [c] from rfl] at h ⊢
    by_cases hb : bareCR (s.takeWhile isNewline ++ [c]) = true
    · simp only [hb, ite_true]; rfl
    · simp only [hb, Bool.false_eq_true, ite_false] at h ⊢
      cases hl : scanLine false lim.maxLine (c :: d) with
      | incomplete =>
        simp only [hl] at h
        have := h _ rfl
        split at this <;> simp [Outcome.atEof] at this
      | bad => rw [scanLine_extend false _ _ t (by simp [hl]), hl]; rfl
      | tooLong => rw [scanLine_extend false _ _ t (by simp [hl]), hl]; rfl
      | ok line after =>
        rw [scanLine_extend false _ _ t (by simp [hl]), hl]
        simp only [hl] at h
        simp only [Line.extend]
        cases hp : parseRequestLine line with
        | error e => obtain ⟨_, _⟩ := e; simp only; rfl
        | ok v =>
          obtain ⟨method, target, http10⟩ := v
          simp only [hp] at h ⊢
          cases hf : readFields (after.length + 1) lim.maxHeader after with
          | incomplete =>
            simp only [hf] at h
            have := h _ rfl
            simp [Outcome.atEof] at this
          | _ =>
            rw [readFields_extend (after.length + 1) ((after ++ t).length + 1) _ after t (by simp)
              (by simp [hf]), hf]; rfl

theorem readBody_extend (lim : Limits) (hd : Head) (t : Bytes)
    (h : (readBody lim hd).1.atEof = false) :
    readBody lim { hd with after := hd.after ++ t } =
      ((readBody lim hd).1,
        if (readBody lim hd).1.isReq then (readBody lim hd).2 ++ t
        else (readBody lim hd).2) := by
  unfold readBody at h ⊢
  simp only at h ⊢
  by_cases hh : (values hd.headers "host").length > 1
  · simp [hh, Outcome.isReq]
  · simp only [hh, ite_false] at h ⊢
    cases hf : frame lim.maxBody hd.http10 hd.headers with
    | error e => obtain ⟨_, _⟩ := e; simp [Outcome.isReq]
    | ok fr =>
      simp only [hf] at h ⊢
      cases hm : methodOf hd.method with
      | none => simp [Outcome.isReq]
      | some m =>
        simp only [hm] at h ⊢
        cases he : checkExpect hd.http10 hd.headers with
        | error e => obtain ⟨_, _⟩ := e; simp [Outcome.isReq]
        | ok u =>
          simp only [he] at h ⊢
          cases fr with
          | length n =>
            simp only at h ⊢
            by_cases hn : hd.after.length < n
            · simp [hn, Outcome.atEof] at h
            · have hn' : ¬ (hd.after ++ t).length < n := by simp; omega
              simp only [hn, hn', ite_false, Outcome.isReq, ite_true]
              rw [List.take_append_of_le_length (by omega), List.drop_append_of_le_length (by omega)]
          | chunked =>
            simp only at h ⊢
            cases hc : readChunks lim (hd.after.length + 1) 0 [] hd.after with
            | incomplete => simp [hc, Outcome.atEof] at h
            | bad c msg =>
              rw [readChunks_extend lim (hd.after.length + 1) ((hd.after ++ t).length + 1) 0 []
                hd.after t (by simp) (by simp [hc]), hc]
              simp [Body.extend, Outcome.isReq]
            | ok body rest =>
              rw [readChunks_extend lim (hd.after.length + 1) ((hd.after ++ t).length + 1) 0 []
                hd.after t (by simp) (by simp [hc]), hc]
              simp [Body.extend, Outcome.isReq]

/-- An outcome not decided by the end of the input stays the same when more bytes follow, and a
request leaves them after the bytes it left for the next request. -/
theorem readOne_extend (lim : Limits) (s t : Bytes) (h : (readOne lim s).1.atEof = false) :
    readOne lim (s ++ t) =
      ((readOne lim s).1,
        if (readOne lim s).1.isReq then (readOne lim s).2 ++ t else (readOne lim s).2) := by
  unfold readOne at h ⊢
  cases hh : readHead lim s with
  | fail o =>
    simp only [hh] at h
    rw [readHead_extend lim s t (by intro o' ho'; rw [hh] at ho'; cases ho'; exact h), hh]
    simp [HeadResult.extend, readHead_fail hh]
  | ok hd =>
    simp only [hh] at h
    rw [readHead_extend lim s t (by intro o' ho'; rw [hh] at ho'; cases ho'), hh]
    simp only [HeadResult.extend]
    exact readBody_extend lim hd t h

theorem readAllAux_ne_nil {lim : Limits} :
    ∀ {fuel : Nat} {s : Bytes}, 0 < fuel → readAllAux lim fuel s ≠ []
  | fuel + 1, s, _ => by unfold readAllAux; split <;> simp

theorem readAllAux_extend (lim : Limits) :
    ∀ (fuel fuel' : Nat) (s t : Bytes), s.length < fuel → fuel ≤ fuel' →
      (∀ o, (readAllAux lim fuel s).getLast? = some o → o.atEof = false) →
      readAllAux lim fuel' (s ++ t) = readAllAux lim fuel s
  | 0, _, _, _, hlen, _, _ => by omega
  | _ + 1, 0, _, _, _, hle, _ => by omega
  | fuel + 1, fuel' + 1, s, t, hlen, hle, h => by
    unfold readAllAux at h ⊢
    cases hr : readOne lim s with
    | mk o rest =>
      simp only [hr] at h
      have ho : o.atEof = false := by
        cases o with
        | req => rfl
        | bad => rfl
        | incomplete msg => simp [Outcome.atEof] at h
        | closed => simp [Outcome.atEof] at h
      have hx := readOne_extend lim s t (by rw [hr]; exact ho)
      rw [hr] at hx
      rw [hx]
      cases o with
      | req m tg k b =>
        cases k with
        | true =>
          simp only [Outcome.isReq, ite_true]
          have hshort := readOne_req_shorter hr
          have hne := readAllAux_ne_nil (lim := lim) (fuel := fuel) (s := rest) (by omega)
          have h' : ∀ o, (readAllAux lim fuel rest).getLast? = some o → o.atEof = false := by
            intro o ho
            apply h o
            rw [List.getLast?_cons, ho]
            rfl
          rw [readAllAux_extend lim fuel fuel' rest t (by omega) (by omega) h']
        | false => rfl
      | bad => rfl
      | incomplete => rfl
      | closed => rfl

/-- Bytes after a stream never change its outcomes, unless its last outcome came from its end
(`closed`, or an incomplete request). -/
theorem readAll_extend {lim : Limits} {s : Bytes} (t : Bytes)
    (h : ∀ o, (readAll lim s).getLast? = some o → o.atEof = false) :
    readAll lim (s ++ t) = readAll lim s := by
  unfold readAll at h ⊢
  exact readAllAux_extend lim _ _ s t (by omega) (by simp) h

/-- The hypothesis holds for real streams: a request then a rejection stay the same whatever
follows. -/
example (t : Bytes) :
    readAll ⟨64, 128, 100⟩ ("GET / HTTP/1.1\r\n\r\nGET\t/ HTTP/1.1\r\n\r\n".toList ++ t) =
      [.req "GET" "/" true [], .bad 400 "Malformed request line"] := by
  rw [readAll_extend t (by decide)]
  decide

end UniSpec.HttpReader
