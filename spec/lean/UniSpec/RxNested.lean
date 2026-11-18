import UniSpec.Rx

/-!
# A trace model of Rx `concat`, `merge`, and `flatMap`

Extends `UniSpec/Rx.lean`: a stream is the list of events a downstream that always continues
observes. Sources are synchronous, as the pre-filled `Rx.queue`s in `RxNestedSpecTest` are, so
`RxRunner` is deterministic:

- `merge` subscribes its sources in order, and each delivers everything it has before the next;
- `flatMap` runs each nested stream to its end, or until it goes quiet, before the next input event;
- `concat` subscribes the second stream only when the first completes.

The theorems state the completion rules of `adr/2026-10-14-rx-completion-semantics.md` (`flatMap`)
and `adr/2026-10-27-rx-merge-parflatmap.md` (`merge`). `merge_ending` shows the ending of `merge`
does not depend on subscription order, so the rule holds for asynchronous interleavings too.
-/

namespace UniSpec.Rx

/-- The values of a trace before its first terminal event. -/
def values : List Event → List Event
  | [] => []
  | .next v :: s => .next v :: values s
  | _ :: _ => []

/-- The terminal event as a trace: empty when the stream goes quiet. -/
def endingTrace : Option Event → List Event
  | some e => [e]
  | none => []

theorem ending_cases (s : List Event) :
    ending s = none ∨ ending s = some .done ∨ ending s = some .fail := by
  induction s with
  | nil => simp [ending]
  | cons e s ih => cases e <;> simp [ending, ih]

/-- What a downstream observes is the values, then the terminal event if any. -/
theorem observe_eq (s : List Event) : observe s = values s ++ endingTrace (ending s) := by
  induction s with
  | nil => rfl
  | cons e s ih => cases e <;> simp [observe, values, ending, endingTrace, ih]

theorem ending_values_append (s r : List Event) : ending (values s ++ r) = ending r := by
  induction s with
  | nil => rfl
  | cons e s ih => cases e <;> simp [values, ending, ih]

theorem wellFormed_values_append (s r : List Event) :
    WellFormed (values s ++ r) ↔ WellFormed r := by
  induction s with
  | nil => rfl
  | cons e s ih => cases e <;> simp [values, WellFormed, ih]

theorem values_values_append (s r : List Event) : values (values s ++ r) = values s ++ values r := by
  induction s with
  | nil => rfl
  | cons e s ih => cases e <;> simp [values, ih]

theorem wellFormed_endingTrace (e : Option Event) : WellFormed (endingTrace e) := by
  rcases e with _ | e
  · trivial
  · cases e <;> simp [endingTrace, WellFormed]

/-! ## concat -/

/-- `a.concat(b)`: `b` is subscribed only when `a` completes. -/
def concat (a b : List Event) : List Event :=
  values a ++
    match ending a with
    | some .done => observe b
    | some .fail => [.fail]
    | _ => []

theorem concat_wellFormed (a b : List Event) : WellFormed (concat a b) := by
  unfold concat
  rw [wellFormed_values_append]
  rcases ending_cases a with h | h | h <;> rw [h]
  · trivial
  · exact observe_wellFormed b
  · simp [WellFormed]

/-- `Rx.empty.concat(b)` is `b`. -/
theorem concat_empty_left (b : List Event) : concat [.done] b = observe b := rfl

/-- `a.concat(Rx.empty)` is `a`. -/
theorem concat_empty_right (a : List Event) : concat a [.done] = observe a := by
  rw [observe_eq, concat]
  rcases ending_cases a with h | h | h <;> simp [h, endingTrace, observe]

/-- A failed or quiet first stream never subscribes the second. -/
theorem concat_not_done (a b : List Event) (h : ending a ≠ some .done) :
    concat a b = observe a := by
  rw [observe_eq, concat]
  rcases ending_cases a with h' | h' | h' <;> simp_all [endingTrace]

theorem values_concat (a b : List Event) :
    values (concat a b) = values a ++ (if ending a = some .done then values b else []) := by
  unfold concat
  rw [values_values_append]
  rcases ending_cases a with h | h | h <;> simp [h, values, observe_eq, values_values_append]
  rcases ending_cases b with h' | h' | h' <;> simp [h', endingTrace, values]

theorem ending_concat (a b : List Event) :
    ending (concat a b) = if ending a = some .done then ending b else ending a := by
  unfold concat
  rw [ending_values_append]
  rcases ending_cases a with h | h | h <;> simp [h, ending]
  rw [observe_eq, ending_values_append]
  rcases ending_cases b with h' | h' | h' <;> simp [h', endingTrace, ending]

theorem observe_of_wellFormed (t : List Event) (h : WellFormed t) : observe t = t := by
  induction t with
  | nil => rfl
  | cons e s ih =>
    cases e with
    | next v => simp only [WellFormed] at h; simp [observe, ih h]
    | done => simp only [WellFormed] at h; subst h; rfl
    | fail => simp only [WellFormed] at h; subst h; rfl

theorem concat_assoc (a b c : List Event) : concat (concat a b) c = concat a (concat b c) := by
  rw [← observe_of_wellFormed _ (concat_wellFormed (concat a b) c),
    ← observe_of_wellFormed _ (concat_wellFormed a (concat b c)), observe_eq, observe_eq]
  simp only [values_concat, ending_concat]
  rcases ending_cases a with ha | ha | ha <;> rcases ending_cases b with hb | hb | hb <;>
    simp [ha, hb]

/-! ## merge -/

/-- `merge` of the sources in subscription order. `allDone` records whether every source so far
completed: a quiet source never ends, so the merge cannot complete. -/
def mergeGo : Bool → List (List Event) → List Event
  | allDone, [] => if allDone then [.done] else []
  | allDone, t :: ts =>
    values t ++
      match ending t with
      | some .fail => [.fail]
      | some .done => mergeGo allDone ts
      | _ => mergeGo false ts

/-- `Rx.merge(sources)` -/
def merge (ts : List (List Event)) : List Event := mergeGo true ts

/-- The ending of a merge: it fails if any source fails, completes if every source completes, and
otherwise stays open. `any` and `all` ignore order. -/
def mergeEnding (es : List (Option Event)) : Option Event :=
  if es.any (· == some .fail) then some .fail
  else if es.all (· == some .done) then some .done
  else none

theorem mergeGo_wellFormed (allDone : Bool) (ts : List (List Event)) :
    WellFormed (mergeGo allDone ts) := by
  induction ts generalizing allDone with
  | nil => cases allDone <;> simp [mergeGo, WellFormed]
  | cons t ts ih =>
    unfold mergeGo
    rw [wellFormed_values_append]
    rcases ending_cases t with h | h | h <;> rw [h]
    · exact ih false
    · exact ih allDone
    · simp [WellFormed]

theorem merge_wellFormed (ts : List (List Event)) : WellFormed (merge ts) :=
  mergeGo_wellFormed true ts

theorem mergeGo_ending (allDone : Bool) (ts : List (List Event)) :
    ending (mergeGo allDone ts) =
      if (ts.map ending).any (· == some .fail) then some .fail
      else if allDone && (ts.map ending).all (· == some .done) then some .done
      else none := by
  induction ts generalizing allDone with
  | nil => cases allDone <;> simp [mergeGo, ending]
  | cons t ts ih =>
    unfold mergeGo
    rw [ending_values_append]
    rcases ending_cases t with h | h | h <;> simp [h, ih, ending]

/-- `merge` completes exactly when every source completes, and fails exactly when some source
fails, whatever the order the sources are subscribed in. -/
theorem merge_ending (ts : List (List Event)) : ending (merge ts) = mergeEnding (ts.map ending) := by
  simp [merge, mergeGo_ending, mergeEnding]

/-- The ending of a merge is the same for any order of its sources. -/
theorem merge_ending_perm {ts ts' : List (List Event)} (p : ts.Perm ts') :
    ending (merge ts) = ending (merge ts') := by
  rw [merge_ending, merge_ending, mergeEnding, mergeEnding, (p.map ending).any_eq,
    (p.map ending).all_eq]

/-- `merge` of no sources completes at once. -/
theorem merge_nil : merge [] = [.done] := rfl

/-- `merge` of one source is that source. -/
theorem merge_single (t : List Event) : merge [t] = observe t := by
  rw [observe_eq, merge, mergeGo]
  rcases ending_cases t with h | h | h <;> simp [h, mergeGo, endingTrace]

/-! ## flatMap -/

/-- `flatMap` in input order. `nestedOpen` records that the last nested stream went quiet, so an
input completion must wait for it, which never comes. A new input value replaces it. -/
def flatMapGo (f : Int → List Event) : Bool → List Event → List Event
  | _, [] => []
  | _, .next x :: s =>
    values (f x) ++
      match ending (f x) with
      | some .fail => [.fail]
      | some .done => flatMapGo f false s
      | _ => flatMapGo f true s
  | nestedOpen, .done :: _ => if nestedOpen then [] else [.done]
  | _, .fail :: _ => [.fail]

/-- `rx.flatMap(f)` -/
def flatMap (f : Int → List Event) (s : List Event) : List Event := flatMapGo f false s

theorem flatMapGo_wellFormed (f : Int → List Event) (o : Bool) (s : List Event) :
    WellFormed (flatMapGo f o s) := by
  induction s generalizing o with
  | nil => trivial
  | cons e s ih =>
    cases e with
    | next x =>
      unfold flatMapGo
      rw [wellFormed_values_append]
      rcases ending_cases (f x) with h | h | h <;> rw [h]
      · exact ih true
      · exact ih false
      · simp [WellFormed]
    | done => cases o <;> simp [flatMapGo, WellFormed]
    | fail => simp [flatMapGo, WellFormed]

theorem flatMap_wellFormed (f : Int → List Event) (s : List Event) :
    WellFormed (flatMap f s) := flatMapGo_wellFormed f false s

theorem flatMapGo_done (f : Int → List Event) (o : Bool) (s : List Event)
    (h : ending (flatMapGo f o s) = some .done) : ending s = some .done := by
  induction s generalizing o with
  | nil => simp [flatMapGo, ending] at h
  | cons e s ih =>
    cases e with
    | next x =>
      unfold flatMapGo at h
      rw [ending_values_append] at h
      simp only [ending]
      rcases ending_cases (f x) with hf | hf | hf <;> rw [hf] at h
      · exact ih true h
      · exact ih false h
      · simp [ending] at h
    | done => rfl
    | fail => simp [flatMapGo, ending] at h

/-- `flatMap` completes only after its input completes (and, by `flatMapGo`, only when the last
nested stream has completed too). -/
theorem flatMap_done (f : Int → List Event) (s : List Event)
    (h : ending (flatMap f s) = some .done) : ending s = some .done := flatMapGo_done f false s h

/-- A quiet nested stream holds back the completion of its input. -/
theorem flatMap_waits_for_nested (f : Int → List Event) (x : Int) (s : List Event)
    (hf : ending (f x) = none) : flatMap f (.next x :: .done :: s) = values (f x) := by
  simp [flatMap, flatMapGo, hf]

/-- When every nested stream completes, `flatMap` ends exactly as its input does. -/
theorem flatMap_ending (f : Int → List Event) (hf : ∀ x, ending (f x) = some .done)
    (s : List Event) : ending (flatMap f s) = ending s := by
  suffices ∀ s, ending (flatMapGo f false s) = ending s from this s
  intro s
  induction s with
  | nil => rfl
  | cons e s ih =>
    cases e with
    | next x => simp [flatMapGo, hf x, ending_values_append, ending, ih]
    | done => rfl
    | fail => rfl

/-- `rx.flatMap(x => Rx.single(x))` is `rx`. -/
theorem flatMap_single_right (s : List Event) :
    flatMap (fun x => [.next x, .done]) s = observe s := by
  suffices ∀ s, flatMapGo (fun x => [.next x, .done]) false s = observe s from this s
  intro s
  induction s with
  | nil => rfl
  | cons e s ih => cases e <;> simp [flatMapGo, values, ending, observe, ih]

/-- `Rx.single(x).flatMap(f)` is `f(x)`. -/
theorem flatMap_single_left (f : Int → List Event) (x : Int) :
    flatMap f [.next x, .done] = observe (f x) := by
  rw [observe_eq]
  simp only [flatMap, flatMapGo]
  rcases ending_cases (f x) with h | h | h <;> simp [h, endingTrace]

end UniSpec.Rx
