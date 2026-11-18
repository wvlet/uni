/-!
# A trace model of Rx `map`, `filter`, and `take`

A stream is modeled as the list of events a downstream observes. The downstream always continues,
so the model leaves out `RxResult.Stop`, continuous mode, cancellation, and concurrency.

Each operator is a function from the input trace to the output trace. The theorems state the rules
of `adr/2026-10-14-rx-completion-semantics.md` and `adr/2026-10-19-rx-take-completion.md`.
`GenRxCases.lean` evaluates these functions to generate the cases that `RxSpecTest` replays
through `RxRunner`.
-/

namespace UniSpec.Rx

/-- An Rx event: `OnNext`, `OnCompletion`, or `OnError`. -/
inductive Event where
  | next (v : Int)
  | done
  | fail
  deriving Repr, DecidableEq, Inhabited

/-- What a downstream sees from a source: nothing after the first terminal event. -/
def observe : List Event → List Event
  | [] => []
  | .next v :: s => .next v :: observe s
  | e :: _ => [e]

/-- `rx.map(f)` -/
def map (f : Int → Int) : List Event → List Event
  | [] => []
  | .next v :: s => .next (f v) :: map f s
  | e :: _ => [e]

/-- `rx.filter(p)`: skips rejected values and reports only the upstream's termination. -/
def filter (p : Int → Bool) : List Event → List Event
  | [] => []
  | .next v :: s => if p v then .next v :: filter p s else filter p s
  | e :: _ => [e]

/-- `rx.take(n)`: completes right after the nth value, without waiting for the upstream. -/
def take : Nat → List Event → List Event
  | 0, _ => [.done]
  | _ + 1, [] => []
  | n + 1, .next v :: s => .next v :: take n s
  | _ + 1, e :: _ => [e]

/-- No event follows a terminal event. -/
def WellFormed : List Event → Prop
  | [] => True
  | .next _ :: s => WellFormed s
  | _ :: s => s = []

/-- The terminal event that ends the trace, if any. -/
def ending : List Event → Option Event
  | [] => none
  | .next _ :: s => ending s
  | e :: _ => some e

/-- The number of values in the trace. -/
def countNext : List Event → Nat
  | [] => 0
  | .next _ :: s => countNext s + 1
  | _ :: s => countNext s

/-- The values as `OnNext` events. -/
def nexts (vs : List Int) : List Event := vs.map .next

/-! ## Every operator emits a well-formed trace, whatever its input -/

theorem observe_wellFormed (s : List Event) : WellFormed (observe s) := by
  induction s with
  | nil => trivial
  | cons e s ih => cases e <;> simp [observe, WellFormed, ih]

theorem map_wellFormed (f : Int → Int) (s : List Event) : WellFormed (map f s) := by
  induction s with
  | nil => trivial
  | cons e s ih => cases e <;> simp [map, WellFormed, ih]

theorem filter_wellFormed (p : Int → Bool) (s : List Event) : WellFormed (filter p s) := by
  induction s with
  | nil => trivial
  | cons e s ih =>
    cases e with
    | next v => by_cases h : p v <;> simp [filter, WellFormed, h, ih]
    | _ => simp [filter, WellFormed]

theorem take_wellFormed (n : Nat) (s : List Event) : WellFormed (take n s) := by
  induction n generalizing s with
  | zero => simp [take, WellFormed]
  | succ n ih =>
    cases s with
    | nil => simp [take, WellFormed]
    | cons e s => cases e <;> simp [take, WellFormed, ih]

/-! ## `filter` and `map` end exactly when their input ends

A rejected value never completes `filter`: it reports the upstream's terminal event and nothing
else (see `2026-10-14-rx-completion-semantics.md`). -/

theorem filter_ending (p : Int → Bool) (s : List Event) : ending (filter p s) = ending s := by
  induction s with
  | nil => rfl
  | cons e s ih =>
    cases e with
    | next v => by_cases h : p v <;> simp [filter, ending, h, ih]
    | _ => rfl

theorem map_ending (f : Int → Int) (s : List Event) : ending (map f s) = ending s := by
  induction s with
  | nil => rfl
  | cons e s ih => cases e <;> simp [map, ending, ih]

/-! ## `take(n)` -/

/-- `take(n <= 0)` completes at once and never subscribes. -/
theorem take_zero (s : List Event) : take 0 s = [.done] := rfl

/-- `take(n)` emits at most n values. -/
theorem take_countNext_le (n : Nat) (s : List Event) : countNext (take n s) ≤ n := by
  induction n generalizing s with
  | zero => simp [take, countNext]
  | succ n ih =>
    cases s with
    | nil => simp [take, countNext]
    | cons e s =>
      cases e with
      | next v => simpa [take, countNext] using ih s
      | _ => simp [take, countNext]

/-- Given at least n values, `take(n)` completes right after the nth one, whatever follows: the
rest of the upstream, including silence, is never awaited. -/
theorem take_completes (n : Nat) (vs : List Int) (rest : List Event) (h : n ≤ vs.length) :
    take n (nexts vs ++ rest) = nexts (vs.take n) ++ [.done] := by
  induction n generalizing vs with
  | zero => simp [take, nexts]
  | succ n ih =>
    cases vs with
    | nil => simp at h
    | cons v vs =>
      simp only [List.length_cons] at h
      simp [nexts, take] at ih ⊢
      exact ih vs (by omega)

/-- An upstream that terminates before n values ends `take(n)` with that same event. -/
theorem take_forwards_early_end (n : Nat) (vs : List Int) (e : Event) (rest : List Event)
    (he : e = .done ∨ e = .fail) (h : vs.length < n) :
    take n (nexts vs ++ e :: rest) = nexts vs ++ [e] := by
  induction vs generalizing n with
  | nil =>
    cases n with
    | zero => simp at h
    | succ n => rcases he with rfl | rfl <;> simp [take, nexts]
  | cons v vs ih =>
    cases n with
    | zero => simp at h
    | succ n =>
      simp only [List.length_cons] at h
      simp [nexts, take] at ih ⊢
      exact ih n (by omega)

/-- An upstream that goes quiet before n values leaves `take(n)` open. -/
theorem take_waits_for_quiet_upstream (n : Nat) (vs : List Int) (h : vs.length < n) :
    take n (nexts vs) = nexts vs := by
  induction vs generalizing n with
  | nil => cases n with
    | zero => simp at h
    | succ n => rfl
  | cons v vs ih =>
    cases n with
    | zero => simp at h
    | succ n =>
      simp only [List.length_cons] at h
      simp [nexts, take] at ih ⊢
      exact ih n (by omega)

end UniSpec.Rx
