import UniSpec.Rx

/-!
# A trace model of Rx `recover` and continuous mode

Extends `UniSpec/Rx.lean`. `map`, `filter`, a throwing `map`, and `recover` act on each event and
never cut the trace; `take` keeps its own end. The run mode decides where the trace is cut:

- **default** (`RxRunner.run`): `Rx.queue` stops after its own terminal event, and the sink answers
  `Stop` to the first terminal event it receives, which stops everything upstream;
- **continuous** (`RxRunner.runContinuously`): terminal events and `Stop` are transient, so neither
  cut applies.

`recover` answers `Continue`, so a recovered error ends the stream only when it came from the
source, which has already stopped: the stream then goes quiet. A recovered operator error lets the
source continue. `GenRxRecoverCases.lean` evaluates `run` to generate the cases `RxRecoverSpecTest`
replays.
-/

namespace UniSpec.Rx

/-- `rx.map(f)`, per event. -/
def mapE (f : Int → Int) : List Event → List Event :=
  List.map fun
    | .next v => .next (f v)
    | e => e

/-- `rx.filter(p)`, per event. -/
def filterE (p : Int → Bool) : List Event → List Event :=
  List.filter fun
    | .next v => p v
    | _ => true

/-- `rx.map(v => if v == k then throw ... else v)`: an operator error on `k`. -/
def boomE (k : Int) : List Event → List Event :=
  List.map fun
    | .next v => if v = k then .fail else .next v
    | e => e

/-- `rx.recover { case _ => r }` -/
def recoverE (r : Int) : List Event → List Event :=
  List.map fun
    | .fail => .next r
    | e => e

inductive Mode where
  | default
  | continuous
  deriving Repr, DecidableEq

/-- Run a pipeline of operators over a source trace in a mode. -/
def run (mode : Mode) (ops : List (List Event → List Event)) (input : List Event) : List Event :=
  match mode with
  | .default => observe (ops.foldl (fun s op => op s) (observe input))
  | .continuous => ops.foldl (fun s op => op s) input

/-! ## Default mode -/

theorem run_default_wellFormed (ops : List (List Event → List Event)) (input : List Event) :
    WellFormed (run .default ops input) := observe_wellFormed _

/-- Per-event `map` agrees with the single-stream `map` once the sink cuts the trace. -/
theorem observe_mapE (f : Int → Int) (s : List Event) : observe (mapE f s) = map f s := by
  induction s with
  | nil => rfl
  | cons e s ih => cases e <;> simp_all [mapE, observe, map]

/-- Per-event `filter` agrees with the single-stream `filter` once the sink cuts the trace. -/
theorem observe_filterE (p : Int → Bool) (s : List Event) :
    observe (filterE p s) = filter p s := by
  induction s with
  | nil => rfl
  | cons e s ih =>
    cases e with
    | next v => by_cases h : p v <;> simp_all [filterE, observe, filter]
    | done => simp [filterE, observe, filter]
    | fail => simp [filterE, observe, filter]

theorem observe_take (n : Nat) (s : List Event) : observe (take n s) = take n s := by
  induction n generalizing s with
  | zero => rfl
  | succ n ih =>
    cases s with
    | nil => rfl
    | cons e s => cases e <;> simp [take, observe, ih]

/-- A total `recover` never emits `OnError`. -/
theorem recoverE_no_fail (r : Int) (s : List Event) : .fail ∉ recoverE r s := by
  induction s with
  | nil => simp [recoverE]
  | cons e s ih => cases e <;> simp_all [recoverE]

/-- In default mode a recovered source error leaves the stream open: the source has already
stopped, so no completion ever follows the recovered value. -/
theorem recover_source_error_goes_quiet (r : Int) (vs : List Int) (rest : List Event) :
    run .default [recoverE r] (nexts vs ++ .fail :: rest) = nexts vs ++ [.next r] := by
  induction vs with
  | nil => simp [run, nexts, observe, recoverE]
  | cons v vs ih =>
    simp only [run, List.foldl_cons, List.foldl_nil, nexts, List.map_cons, List.cons_append] at ih ⊢
    simp only [observe, recoverE, List.map_cons] at ih ⊢
    simpa [recoverE] using ih

/-- In default mode a recovered operator error lets the source continue to its completion. -/
theorem recover_operator_error_continues (k r : Int) (vs : List Int) :
    run .default [boomE k, recoverE r] (nexts vs ++ [.done]) =
      nexts (vs.map fun v => if v = k then r else v) ++ [.done] := by
  induction vs with
  | nil => rfl
  | cons v vs ih =>
    simp only [run, List.foldl_cons, List.foldl_nil] at ih ⊢
    by_cases h : v = k <;>
      simp_all [nexts, observe, boomE, recoverE]

/-! ## Continuous mode -/

/-- Continuous mode delivers events after an error or a completion. -/
theorem continuous_delivers_after_terminal :
    run .continuous [] [.fail, .next 1, .done, .next 2] = [.fail, .next 1, .done, .next 2] := rfl

/-- A recovered error in continuous mode is just another value: the stream keeps going. -/
theorem continuous_recover (r : Int) (s : List Event) :
    run .continuous [recoverE r] s = recoverE r s := rfl

/-- `take` still ends at the first terminal event in continuous mode. -/
theorem continuous_take_ends_on_error (n : Nat) (vs : List Int) (rest : List Event)
    (h : vs.length < n) :
    run .continuous [take n] (nexts vs ++ .fail :: rest) = nexts vs ++ [.fail] :=
  take_forwards_early_end n vs .fail rest (.inr rfl) h

/-- `take` output is well-formed in either mode. -/
theorem run_take_wellFormed (mode : Mode) (n : Nat) (input : List Event) :
    WellFormed (run mode [take n] input) := by
  cases mode
  · exact observe_wellFormed _
  · exact take_wellFormed n input

end UniSpec.Rx
