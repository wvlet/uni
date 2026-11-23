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

theorem scanLine_extend : ∀ (cr : Bool) (s t : Bytes), scanLine cr s ≠ .incomplete →
    scanLine cr (s ++ t) = (scanLine cr s).extend t
  | _, [], _, h => by simp [scanLine] at h
  | cr, c :: s, t, h => by
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
          exact scanLine_extend true s t h
        · simp only [h3, Bool.false_eq_true, ite_false] at h ⊢
          cases hs : scanLine false s with
          | ok l r => rw [scanLine_extend false s t (by simp [hs]), hs]; rfl
          | bad => rw [scanLine_extend false s t (by simp [hs]), hs]; rfl
          | incomplete => simp [hs] at h

theorem readFields_extend : ∀ (fuel fuel' : Nat) (s t : Bytes), fuel ≤ fuel' →
    readFields fuel s ≠ .incomplete → readFields fuel' (s ++ t) = (readFields fuel s).extend t
  | 0, _, _, _, _, h => by simp [readFields] at h
  | _ + 1, 0, _, _, hle, _ => by omega
  | fuel + 1, fuel' + 1, s, t, hle, h => by
    unfold readFields at h ⊢
    cases hl : scanLine false s with
    | incomplete => simp [hl] at h
    | bad => rw [scanLine_extend false s t (by simp [hl]), hl]; rfl
    | ok line rest =>
      rw [scanLine_extend false s t (by simp [hl]), hl]
      simp only [hl] at h
      cases line with
      | nil => rfl
      | cons c l =>
        simp only [Line.extend] at h ⊢
        cases hp : parseField (c :: l) with
        | error e => simp only; rfl
        | ok f =>
          simp only [hp] at h ⊢
          cases hr : readFields fuel rest with
          | incomplete => simp [hr] at h
          | bad m => rw [readFields_extend fuel fuel' rest t (by omega) (by simp [hr]), hr]; rfl
          | ok fs r => rw [readFields_extend fuel fuel' rest t (by omega) (by simp [hr]), hr]; rfl

/-! ## Chunk sizes and extensions -/

theorem restOfSize_extend : ∀ (size : Nat) (s t : Bytes), restOfSize size s ≠ .incomplete →
    restOfSize size (s ++ t) = (restOfSize size s).extend t
  | _, [], _, h => by simp [restOfSize] at h
  | size, c :: s, t, h => by
    rw [List.cons_append]
    unfold restOfSize at h ⊢
    cases hv : hexValue c with
    | none => simp only [hv] at h ⊢; split <;> rfl
    | some v => simp only [hv] at h ⊢; exact restOfSize_extend _ s t h

theorem restOfSize_tooLarge_ne_nil : ∀ {size : Nat} {s r : Bytes},
    restOfSize size s = .tooLarge r → r ≠ []
  | _, [], _, h => by simp [restOfSize] at h
  | size, c :: s, r, h => by
    unfold restOfSize at h
    cases hv : hexValue c with
    | none =>
      simp only [hv] at h
      split at h
      · simp at h
      · simp only [Size.tooLarge.injEq] at h; subst h; simp
    | some v => simp only [hv] at h; exact restOfSize_tooLarge_ne_nil h

theorem readSize_extend : ∀ (maxLeft size digits : Nat) (s t : Bytes),
    readSize maxLeft size digits s ≠ .incomplete →
    readSize maxLeft size digits (s ++ t) = (readSize maxLeft size digits s).extend t
  | _, _, _, [], _, h => by simp [readSize] at h
  | maxLeft, size, digits, c :: s, t, h => by
    rw [List.cons_append]
    unfold readSize at h ⊢
    cases hv : hexValue c with
    | none => simp only; rfl
    | some v =>
      simp only [hv] at h ⊢
      split
      · rename_i hgt
        simp only [hgt, ite_true] at h
        exact restOfSize_extend _ s t h
      · rename_i hgt
        simp only [hgt, ite_false] at h
        exact readSize_extend maxLeft _ _ s t h

/-- The bytes after a size are never empty: the size ends at a byte that is not a hex digit. -/
theorem readSize_rest_ne_nil : ∀ {maxLeft size digits : Nat} {s : Bytes},
    (∀ sz d r, readSize maxLeft size digits s = .ok sz d r → r ≠ []) ∧
      (∀ r, readSize maxLeft size digits s = .tooLarge r → r ≠ [])
  | _, _, _, [] => by simp [readSize]
  | maxLeft, size, digits, c :: s => by
    unfold readSize
    cases hv : hexValue c with
    | none => simp
    | some v =>
      simp only
      split
      · exact ⟨fun _ _ _ h => absurd h restOfSize_not_ok, fun _ h => restOfSize_tooLarge_ne_nil h⟩
      · exact readSize_rest_ne_nil

theorem skipExt_extend : ∀ (s t : Bytes), skipExt s ≠ .incomplete →
    skipExt (s ++ t) = (skipExt s).extend t
  | [], _, h => by simp [skipExt] at h
  | c :: s, t, h => by
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
        exact skipExt_extend s t h

/-- `skipChunkExt []` is `ok []`, which a `;` after it would change, so the lemma needs a byte. -/
theorem skipChunkExt_extend {s : Bytes} (t : Bytes) (hs : s ≠ []) (h : skipChunkExt s ≠ .incomplete) :
    skipChunkExt (s ++ t) = (skipChunkExt s).extend t := by
  match s, hs with
  | c :: s, _ =>
    rw [List.cons_append]
    by_cases hc : c = ';'
    · subst hc
      simp only [skipChunkExt] at h ⊢
      exact skipExt_extend s t h
    · have e1 : skipChunkExt (c :: (s ++ t)) = .ok (c :: (s ++ t)) := by
        unfold skipChunkExt; split
        · rename_i heq; simp at heq; exact absurd heq.1 hc
        · rfl
      have e2 : skipChunkExt (c :: s) = .ok (c :: s) := by
        unfold skipChunkExt; split
        · rename_i heq; simp at heq; exact absurd heq.1 hc
        · rfl
      rw [e1, e2]; rfl

theorem crlf_extend (r t : Bytes) (h : crlf r ≠ .incomplete) : crlf (r ++ t) = (crlf r).extend t := by
  match r, h with
  | a :: b :: r, _ => simp only [List.cons_append, crlf]; split <;> rfl
  | [_], h => simp [crlf] at h
  | [], h => simp [crlf] at h

theorem chunkTooLarge_extend {r : Bytes} (t : Bytes) (hr : r ≠ []) (h : chunkTooLarge r ≠ .incomplete) :
    chunkTooLarge (r ++ t) = chunkTooLarge r := by
  unfold chunkTooLarge at h ⊢
  cases he : skipChunkExt r with
  | incomplete => simp [he] at h
  | bad => rw [skipChunkExt_extend t hr (by simp [he]), he]; rfl
  | ok r2 =>
    rw [skipChunkExt_extend t hr (by simp [he]), he]
    simp only [he] at h
    simp only [Ext.extend]
    cases hc : crlf r2 with
    | incomplete => simp [hc] at h
    | bad => rw [crlf_extend r2 t (by simp [hc]), hc]; rfl
    | ok r3 => rw [crlf_extend r2 t (by simp [hc]), hc]; rfl

/-! ## Chunked bodies -/

theorem readChunks_extend (maxSize : Nat) :
    ∀ (fuel fuel' total : Nat) (acc s t : Bytes), fuel ≤ fuel' →
      readChunks maxSize fuel total acc s ≠ .incomplete →
      readChunks maxSize fuel' total acc (s ++ t) = (readChunks maxSize fuel total acc s).extend t
  | 0, _, _, _, _, _, _, h => by simp [readChunks] at h
  | _ + 1, 0, _, _, _, _, hle, _ => by omega
  | fuel + 1, fuel' + 1, total, acc, s, t, hle, h => by
    unfold readChunks at h ⊢
    have ⟨hok, htl⟩ := readSize_rest_ne_nil (maxLeft := maxSize - total) (size := 0) (digits := 0) (s := s)
    cases hs : readSize (maxSize - total) 0 0 s with
    | incomplete => simp [hs] at h
    | overflow => rw [readSize_extend _ _ _ s t (by simp [hs]), hs]; rfl
    | tooLarge r =>
      rw [readSize_extend _ _ _ s t (by simp [hs]), hs]
      simp only [hs] at h
      simp only [Size.extend]
      rw [chunkTooLarge_extend t (htl r hs) h]
      cases hc : chunkTooLarge r with
      | ok b r' => exact absurd hc chunkTooLarge_not_ok
      | _ => rfl
    | ok size digits r =>
      rw [readSize_extend _ _ _ s t (by simp [hs]), hs]
      simp only [hs] at h
      simp only [Size.extend]
      split
      · rfl
      · rename_i hd
        simp only [hd] at h
        have hr := hok size digits r hs
        cases he : skipChunkExt r with
        | incomplete => simp [he] at h
        | bad => rw [skipChunkExt_extend t hr (by simp [he]), he]; rfl
        | ok r2 =>
          rw [skipChunkExt_extend t hr (by simp [he]), he]
          simp only [he] at h
          simp only [Ext.extend]
          cases hc : crlf r2 with
          | incomplete => simp [hc] at h
          | bad => rw [crlf_extend r2 t (by simp [hc]), hc]; rfl
          | ok r3 =>
            rw [crlf_extend r2 t (by simp [hc]), hc]
            simp only [hc] at h
            simp only [Ext.extend]
            by_cases hz : (size == 0) = true
            · simp only [hz, ite_true] at h ⊢
              cases hf : readFields (r3.length + 1) r3 with
              | incomplete => simp [hf] at h
              | bad m =>
                rw [readFields_extend (r3.length + 1) ((r3 ++ t).length + 1) r3 t (by simp)
                  (by simp [hf]), hf]; rfl
              | ok fs rest =>
                rw [readFields_extend (r3.length + 1) ((r3 ++ t).length + 1) r3 t (by simp)
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
                  exact readChunks_extend maxSize fuel fuel' _ _ _ t (by omega) h
                · simp only [hc2, Bool.false_eq_true, ite_false]; rfl

/-! ## Requests -/

theorem readHead_extend (s t : Bytes) (h : ∀ o, readHead s = .fail o → o.atEof = false) :
    readHead (s ++ t) = (readHead s).extend t := by
  unfold readHead at h ⊢
  rw [List.dropWhile_append]
  generalize hd : s.dropWhile isNewline = d at h ⊢
  cases d with
  | nil =>
    have := h .closed (by simp [scanLine])
    simp [Outcome.atEof] at this
  | cons c d =>
    simp only [List.isEmpty_cons, Bool.false_eq_true, ite_false]
    cases hl : scanLine false (c :: d) with
    | incomplete =>
      simp only [hl] at h
      have := h _ rfl
      split at this <;> simp [Outcome.atEof] at this
    | bad => rw [scanLine_extend false _ t (by simp [hl]), hl]; rfl
    | ok line after =>
      rw [scanLine_extend false _ t (by simp [hl]), hl]
      simp only [hl] at h
      simp only [Line.extend]
      cases hp : parseRequestLine line with
      | error e => obtain ⟨_, _⟩ := e; simp only; rfl
      | ok v =>
        obtain ⟨method, target, http10⟩ := v
        simp only [hp] at h ⊢
        cases hf : readFields (after.length + 1) after with
        | incomplete =>
          simp only [hf] at h
          have := h _ rfl
          simp [Outcome.atEof] at this
        | bad m =>
          rw [readFields_extend (after.length + 1) ((after ++ t).length + 1) after t (by simp) (by simp [hf]), hf]; rfl
        | ok hs rest =>
          rw [readFields_extend (after.length + 1) ((after ++ t).length + 1) after t (by simp) (by simp [hf]), hf]; rfl

theorem readBody_extend (maxSize : Nat) (hd : Head) (t : Bytes)
    (h : (readBody maxSize hd).1.atEof = false) :
    readBody maxSize { hd with after := hd.after ++ t } =
      ((readBody maxSize hd).1,
        if (readBody maxSize hd).1.isReq then (readBody maxSize hd).2 ++ t
        else (readBody maxSize hd).2) := by
  unfold readBody at h ⊢
  simp only at h ⊢
  by_cases hh : (values hd.headers "host").length > 1
  · simp [hh, Outcome.isReq]
  · simp only [hh, ite_false] at h ⊢
    cases hf : frame maxSize hd.http10 hd.headers with
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
            cases hc : readChunks maxSize (hd.after.length + 1) 0 [] hd.after with
            | incomplete => simp [hc, Outcome.atEof] at h
            | bad c msg =>
              rw [readChunks_extend maxSize (hd.after.length + 1) ((hd.after ++ t).length + 1) 0 [] hd.after t (by simp)
                (by simp [hc]), hc]
              simp [Body.extend, Outcome.isReq]
            | ok body rest =>
              rw [readChunks_extend maxSize (hd.after.length + 1) ((hd.after ++ t).length + 1) 0 [] hd.after t (by simp)
                (by simp [hc]), hc]
              simp [Body.extend, Outcome.isReq]

/-- An outcome not decided by the end of the input stays the same when more bytes follow, and a
request leaves them after the bytes it left for the next request. -/
theorem readOne_extend (maxSize : Nat) (s t : Bytes) (h : (readOne maxSize s).1.atEof = false) :
    readOne maxSize (s ++ t) =
      ((readOne maxSize s).1,
        if (readOne maxSize s).1.isReq then (readOne maxSize s).2 ++ t else (readOne maxSize s).2) := by
  unfold readOne at h ⊢
  cases hh : readHead s with
  | fail o =>
    simp only [hh] at h
    rw [readHead_extend s t (by intro o' ho'; rw [hh] at ho'; cases ho'; exact h), hh]
    simp [HeadResult.extend, readHead_fail hh]
  | ok hd =>
    simp only [hh] at h
    rw [readHead_extend s t (by intro o' ho'; rw [hh] at ho'; cases ho'), hh]
    simp only [HeadResult.extend]
    exact readBody_extend maxSize hd t h

theorem readAllAux_ne_nil {maxSize : Nat} :
    ∀ {fuel : Nat} {s : Bytes}, 0 < fuel → readAllAux maxSize fuel s ≠ []
  | fuel + 1, s, _ => by unfold readAllAux; split <;> simp

theorem readAllAux_extend (maxSize : Nat) :
    ∀ (fuel fuel' : Nat) (s t : Bytes), s.length < fuel → fuel ≤ fuel' →
      (∀ o, (readAllAux maxSize fuel s).getLast? = some o → o.atEof = false) →
      readAllAux maxSize fuel' (s ++ t) = readAllAux maxSize fuel s
  | 0, _, _, _, hlen, _, _ => by omega
  | _ + 1, 0, _, _, _, hle, _ => by omega
  | fuel + 1, fuel' + 1, s, t, hlen, hle, h => by
    unfold readAllAux at h ⊢
    cases hr : readOne maxSize s with
    | mk o rest =>
      simp only [hr] at h
      have ho : o.atEof = false := by
        cases o with
        | req => rfl
        | bad => rfl
        | incomplete msg => simp [Outcome.atEof] at h
        | closed => simp [Outcome.atEof] at h
      have hx := readOne_extend maxSize s t (by rw [hr]; exact ho)
      rw [hr] at hx
      rw [hx]
      cases o with
      | req m tg k b =>
        cases k with
        | true =>
          simp only [Outcome.isReq, ite_true]
          have hshort := readOne_req_shorter hr
          have hne := readAllAux_ne_nil (maxSize := maxSize) (fuel := fuel) (s := rest) (by omega)
          have h' : ∀ o, (readAllAux maxSize fuel rest).getLast? = some o → o.atEof = false := by
            intro o ho
            apply h o
            rw [List.getLast?_cons, ho]
            rfl
          rw [readAllAux_extend maxSize fuel fuel' rest t (by omega) (by omega) h']
        | false => rfl
      | bad => rfl
      | incomplete => rfl
      | closed => rfl

/-- Bytes after a stream never change its outcomes, unless its last outcome came from its end
(`closed`, or an incomplete request). -/
theorem readAll_extend {maxSize : Nat} {s : Bytes} (t : Bytes)
    (h : ∀ o, (readAll maxSize s).getLast? = some o → o.atEof = false) :
    readAll maxSize (s ++ t) = readAll maxSize s := by
  unfold readAll at h ⊢
  exact readAllAux_extend maxSize _ _ s t (by omega) (by simp) h

/-- The hypothesis holds for real streams: a request then a rejection stay the same whatever
follows. -/
example (t : Bytes) :
    readAll 100 ("GET / HTTP/1.1\r\n\r\nGET\t/ HTTP/1.1\r\n\r\n".toList ++ t) =
      [.req "GET" "/" true [], .bad 400 "Malformed request line"] := by
  rw [readAll_extend t (by decide)]
  decide

end UniSpec.HttpReader
