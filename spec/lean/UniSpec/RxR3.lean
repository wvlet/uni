import UniSpec.Rx
import UniSpec.RxRecover

/-!
# An R3-style event model, compared with today's two run modes

An experiment, not a model of current code. [R3](https://github.com/Cysharp/R3) splits Uni's
`OnError` in two:

- `resume`: an error that does not end the stream (R3's `OnErrorResume`), raised by an operator
  such as a throwing `map`;
- `failed`: the terminal failure (R3's `OnCompleted(Result.Failure)`), raised by a source that has
  ended, such as a failed `Rx.future` or `Rx.queue`.

`done` is the terminal success. A stream ends at its first terminal event, in one semantics with no
run mode: the sink cuts the trace there and nowhere else.

Today's `RxRunner` has one `OnError` (`Event.fail`) for both, and the run mode decides whether it
ends the stream: default mode cuts at every `OnError`, continuous mode at none, so an
`OnCompletion` does not end a continuous stream either. The theorems below show:

1. default mode is this model plus `resumeAsFailure` (R3's `OnErrorResumeAsFailure`) before the
   sink (`default_mode_eq`);
2. continuous mode over a live source (a var that has not stopped) is this model as is
   (`continuous_mode_eq`), so the two modes differ only in the events this model rules out;
3. `recover` needs no mode: a recovered `resume` lets the stream continue, and a recovered
   `failed` completes it (`recover_failed_completes`), where today's default mode goes quiet
   forever (`UniSpec.Rx.recover_source_error_goes_quiet`).

Operators are per-event (`map`, `filter`, a throwing `map`), as in `RxRecover.lean`. The
evaluation is in `plans/2026-11-21-rx-r3-event-model.md`.
-/

namespace UniSpec.RxR3

open UniSpec.Rx (Event Mode)

inductive REvent where
  | next (v : Int)
  /-- A non-terminal error (R3 `OnErrorResume`) -/
  | resume
  /-- Terminal success -/
  | done
  /-- Terminal failure (R3 `OnCompleted(Result.Failure)`) -/
  | failed
  deriving Repr, DecidableEq

def isTerminal : REvent → Bool
  | .done | .failed => true
  | _ => false

/-- What the sink sees: everything up to and including the first terminal event. -/
def observe : List REvent → List REvent
  | [] => []
  | e :: s => if isTerminal e then [e] else e :: observe s

/-- No event follows a terminal event. -/
def WellFormed : List REvent → Prop
  | [] => True
  | e :: s => if isTerminal e then s = [] else WellFormed s

theorem observe_wellFormed (s : List REvent) : WellFormed (observe s) := by
  induction s with
  | nil => trivial
  | cons e s ih => cases e <;> simp_all [observe, WellFormed, isTerminal]

/-- Today's single `OnError`: both kinds of error look the same. -/
def erase : REvent → Event
  | .next v => .next v
  | .resume => .fail
  | .done => .done
  | .failed => .fail

/-! ## Per-event operators in both models -/

inductive Op where
  | map (f : Int → Int)
  | filter (p : Int → Bool)
  /-- `map(v => if v == k then throw ... else v)` -/
  | boom (k : Int)

/-- The operator in this model. A throwing `map` raises `resume`: the source keeps running. -/
def Op.r3 : Op → List REvent → List REvent
  | .map f => List.map fun
    | .next v => .next (f v)
    | e => e
  | .filter p => List.filter fun
    | .next v => p v
    | _ => true
  | .boom k => List.map fun
    | .next v => if v = k then .resume else .next v
    | e => e

/-- The same operator today (`RxRecover.lean`). -/
def Op.old : Op → List Event → List Event
  | .map f => Rx.mapE f
  | .filter p => Rx.filterE p
  | .boom k => Rx.boomE k

def applyAll (ops : List (List α → List α)) (s : List α) : List α :=
  ops.foldl (fun s op => op s) s

/-- Run a pipeline in this model: one semantics, the sink cuts at the first terminal event. -/
def run (ops : List Op) (input : List REvent) : List REvent :=
  observe (applyAll (ops.map Op.r3) input)

/-- R3's `onErrorResumeAsFailure`: turn every `resume` into the terminal `failed`. -/
def resumeAsFailure : List REvent → List REvent :=
  List.map fun
    | .resume => .failed
    | e => e

/-! ## Lemmas -/

theorem Op.r3_cons (op : Op) (e : REvent) (s : List REvent) :
    op.r3 (e :: s) = op.r3 [e] ++ op.r3 s := by
  cases op with
  | map f => simp [Op.r3]
  | filter p =>
    cases e with
    | next v => by_cases h : p v <;> simp [Op.r3, h]
    | _ => simp [Op.r3]
  | boom k => simp [Op.r3]

theorem Op.old_cons (op : Op) (e : Event) (s : List Event) :
    op.old (e :: s) = op.old [e] ++ op.old s := by
  cases op with
  | map f => simp [Op.old, Rx.mapE]
  | filter p =>
    cases e with
    | next v => by_cases h : p v <;> simp [Op.old, Rx.filterE, h]
    | _ => simp [Op.old, Rx.filterE]
  | boom k => simp [Op.old, Rx.boomE]

theorem Op.erase_single (op : Op) (e : REvent) : (op.r3 [e]).map erase = op.old [erase e] := by
  cases op with
  | map f => cases e <;> simp [Op.r3, Op.old, erase, Rx.mapE]
  | filter p =>
    cases e with
    | next v => by_cases h : p v <;> simp [Op.r3, Op.old, erase, Rx.filterE, h]
    | _ => simp [Op.r3, Op.old, erase, Rx.filterE]
  | boom k =>
    cases e with
    | next v => by_cases h : v = k <;> simp [Op.r3, Op.old, erase, Rx.boomE, h]
    | _ => simp [Op.r3, Op.old, erase, Rx.boomE]

theorem Op.erase_comm (op : Op) (s : List REvent) :
    (op.r3 s).map erase = op.old (s.map erase) := by
  induction s with
  | nil => cases op <;> rfl
  | cons e s ih =>
    rw [Op.r3_cons, List.map_append, ih, Op.erase_single, List.map_cons,
      Op.old_cons op (erase e) (List.map erase s)]

theorem applyAll_erase (ops : List Op) (s : List REvent) :
    (applyAll (ops.map Op.r3) s).map erase = applyAll (ops.map Op.old) (s.map erase) := by
  induction ops generalizing s with
  | nil => rfl
  | cons op ops ih =>
    simp only [applyAll, List.map_cons, List.foldl_cons] at ih ⊢
    rw [ih, Op.erase_comm]

/-- Old default mode cuts at the first `fail` or `done`. With `resumeAsFailure` first, this
model's sink cuts at the same place. -/
theorem observe_resumeAsFailure_erase (s : List REvent) :
    (observe (resumeAsFailure s)).map erase = Rx.observe (s.map erase) := by
  induction s with
  | nil => rfl
  | cons e s ih =>
    cases e <;> simp_all [observe, resumeAsFailure, isTerminal, erase, Rx.observe]

/-- An operator turns a value into nothing, a value, or (a throwing `map`) an error. -/
theorem Op.old_next (op : Op) (v : Int) :
    op.old [.next v] = [] ∨ (∃ w, op.old [.next v] = [.next w]) ∨ op.old [.next v] = [.fail] := by
  cases op with
  | map f => exact Or.inr (Or.inl ⟨f v, by simp [Op.old, Rx.mapE]⟩)
  | filter p =>
    by_cases h : p v
    · exact Or.inr (Or.inl ⟨v, by simp [Op.old, Rx.filterE, h]⟩)
    · exact Or.inl (by simp [Op.old, Rx.filterE, h])
  | boom k =>
    by_cases h : v = k
    · exact Or.inr (Or.inr (by simp [Op.old, Rx.boomE, h]))
    · exact Or.inr (Or.inl ⟨v, by simp [Op.old, Rx.boomE, h]⟩)

/-- Operators pass terminal events through. -/
theorem Op.old_terminal (op : Op) (e : Event) (he : e = .done ∨ e = .fail) : op.old [e] = [e] := by
  rcases he with rfl | rfl <;> cases op <;> simp [Op.old, Rx.mapE, Rx.filterE, Rx.boomE]

/-- An operator applied to a trace sees the same thing before the first terminal event, whether
or not the trace was cut there first. -/
theorem Rx.observe_op_observe (op : Op) (s : List Event) :
    Rx.observe (op.old (Rx.observe s)) = Rx.observe (op.old s) := by
  induction s with
  | nil => rfl
  | cons e s ih =>
    cases e with
    | next v =>
      simp only [Rx.observe]
      rw [Op.old_cons, Op.old_cons op (.next v) s]
      rcases Op.old_next op v with h | ⟨w, h⟩ | h <;> rw [h]
      · simpa using ih
      · simp [Rx.observe, ih]
      · rfl
    | done =>
      simp only [Rx.observe]
      rw [Op.old_cons op .done s, Op.old_terminal op .done (.inl rfl)]
      rfl
    | fail =>
      simp only [Rx.observe]
      rw [Op.old_cons op .fail s, Op.old_terminal op .fail (.inr rfl)]
      rfl

theorem Rx.observe_observe (s : List Event) : Rx.observe (Rx.observe s) = Rx.observe s := by
  induction s with
  | nil => rfl
  | cons e s ih => cases e <;> simp_all [Rx.observe]

theorem Rx.observe_applyAll_observe (ops : List Op) (s : List Event) :
    Rx.observe (applyAll (ops.map Op.old) (Rx.observe s)) =
      Rx.observe (applyAll (ops.map Op.old) s) := by
  induction ops generalizing s with
  | nil => exact Rx.observe_observe s
  | cons op ops ih =>
    simp only [applyAll, List.map_cons, List.foldl_cons] at ih ⊢
    rw [← ih (op.old (Rx.observe s)), Rx.observe_op_observe, ih]

/-! ## 1. Default mode is this model with `resumeAsFailure` before the sink -/

/-- Today's default mode (`RxRunner.run`), erased, is this model with every `resume` turned into
`failed` at the end of the pipeline. -/
theorem default_mode_eq (ops : List Op) (input : List REvent) :
    (observe (resumeAsFailure (applyAll (ops.map Op.r3) input))).map erase =
      Rx.run .default (ops.map Op.old) (input.map erase) := by
  rw [observe_resumeAsFailure_erase, applyAll_erase]
  simp only [Rx.run]
  rw [show applyAll (ops.map Op.old) = fun s => (ops.map Op.old).foldl (fun s op => op s) s
    from rfl] at *
  exact (Rx.observe_applyAll_observe ops (input.map erase)).symm

/-! ## 2. Continuous mode over a live source is this model as is -/

/-- A live source: a var that has not stopped or failed emits only values. -/
def Live (s : List REvent) : Prop := ∀ e ∈ s, ∃ v, e = .next v

theorem Op.r3_no_terminal (op : Op) (s : List REvent) (h : ∀ e ∈ s, isTerminal e = false) :
    ∀ e ∈ op.r3 s, isTerminal e = false := by
  intro e he
  cases op with
  | map f =>
    simp only [Op.r3, List.mem_map] at he
    obtain ⟨x, hx, rfl⟩ := he
    have := h x hx
    cases x <;> simp_all [isTerminal]
  | filter p =>
    simp only [Op.r3, List.mem_filter] at he
    exact h e he.1
  | boom k =>
    simp only [Op.r3, List.mem_map] at he
    obtain ⟨x, hx, rfl⟩ := he
    have := h x hx
    cases x with
    | next v => by_cases hv : v = k <;> simp [hv, isTerminal]
    | _ => simp_all [isTerminal]

theorem applyAll_no_terminal (ops : List Op) (s : List REvent)
    (h : ∀ e ∈ s, isTerminal e = false) : ∀ e ∈ applyAll (ops.map Op.r3) s, isTerminal e = false := by
  induction ops generalizing s with
  | nil => exact h
  | cons op ops ih =>
    simp only [applyAll, List.map_cons, List.foldl_cons] at ih ⊢
    exact ih _ (Op.r3_no_terminal op s h)

theorem observe_no_terminal (s : List REvent) (h : ∀ e ∈ s, isTerminal e = false) :
    observe s = s := by
  induction s with
  | nil => rfl
  | cons e s ih =>
    have he := h e (by simp)
    simp [observe, he, ih (fun x hx => h x (by simp [hx]))]

/-- Over a live source, today's continuous mode (`runContinuously`) is this model: operator errors
are `resume`, delivered without ending the stream. The modes differ only after a source ends,
where continuous mode keeps delivering and this model has ended. -/
theorem continuous_mode_eq (ops : List Op) (input : List REvent) (h : Live input) :
    (run ops input).map erase = Rx.run .continuous (ops.map Op.old) (input.map erase) := by
  have hnt : ∀ e ∈ input, isTerminal e = false := by
    intro e he
    obtain ⟨v, rfl⟩ := h e he
    rfl
  simp only [run]
  rw [observe_no_terminal _ (applyAll_no_terminal ops input hnt), applyAll_erase]
  rfl

/-- Today's continuous mode delivers values after a completion; this model never does. -/
theorem continuous_after_done :
    Rx.run .continuous [] [.done, .next 1] = [.done, .next 1] ∧
      run [] [.done, .next 1] = [.done] := ⟨rfl, rfl⟩

/-! ## 3. `recover` without a run mode -/

/-- `recover { case _ => r }`: a `resume` becomes a value and the stream continues; a `failed`
becomes a value, and the stream completes, as R3's `Catch` does when its fallback completes. -/
def recover (r : Int) : List REvent → List REvent
  | [] => []
  | .resume :: s => .next r :: recover r s
  | .failed :: _ => [.next r, .done]
  | e :: s => e :: recover r s

def nexts (vs : List Int) : List REvent := vs.map .next

/-- A recovered source failure completes the stream. Today, default mode leaves it open forever
(`UniSpec.Rx.recover_source_error_goes_quiet`): the source has stopped, so no completion comes. -/
theorem recover_failed_completes (r : Int) (vs : List Int) (rest : List REvent) :
    observe (recover r (nexts vs ++ .failed :: rest)) = nexts vs ++ [.next r, .done] := by
  induction vs with
  | nil => simp [nexts, recover, observe, isTerminal]
  | cons v vs ih => simp_all [nexts, recover, observe, isTerminal]

/-- A recovered operator error lets the source run to its completion, as today in default mode
(`UniSpec.Rx.recover_operator_error_continues`). -/
theorem recover_resume_continues (k r : Int) (vs : List Int) :
    observe (recover r ((Op.boom k).r3 (nexts vs ++ [.done]))) =
      nexts (vs.map fun v => if v = k then r else v) ++ [.done] := by
  induction vs with
  | nil => simp [nexts, Op.r3, recover, observe, isTerminal]
  | cons v vs ih =>
    by_cases h : v = k <;> simp_all [nexts, Op.r3, recover, observe, isTerminal]

/-- Unlike today's default mode, a `resume` that is not recovered does not end the stream: the
sink reports it and keeps receiving values. Code that wants today's behavior adds
`resumeAsFailure`. -/
theorem resume_does_not_end :
    run [.boom 1] (nexts [1, 2] ++ [.done]) = [.resume, .next 2, .done] ∧
      observe (resumeAsFailure ((Op.boom 1).r3 (nexts [1, 2] ++ [.done]))) = [.failed] :=
  ⟨rfl, rfl⟩

end UniSpec.RxR3
