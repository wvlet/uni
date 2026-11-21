import UniSpec.RxNested
import UniSpec.RxR3

/-!
# R3-style events through `flatMap`, `take`, and `recoverWith`

Extends `RxR3.lean` from per-event operators to operators that keep state. Sources are traces, as
in `RxNested.lean`: a trace that does not end with a terminal event is a live source (a var that
has not stopped), so "the nested stream is still open" is expressible. Nested streams are
synchronous: each runs to its end, or goes quiet, before the next input event.

Results:

- `flatMap` and `take` in today's default mode are this model with `resumeAsFailure` before the
  sink (`flatMap_default_eq`, `take_default_eq`), as `default_mode_eq` showed for per-event
  operators. So `run`, `await`, and RPC entry points can keep today's behavior.
- `recoverWith` does not have that property, because today's default mode is not consistent:
  a throwing `map` before `recover` lets the input continue, but before `recoverWith` it ends the
  input (`old_default_inconsistent`). With R3 events both operators continue on `resume` and end
  on `failed` (`r3_consistent`). The event decides, not the run mode.
- `take` passes `resume` without counting it, where today's continuous mode ends `take` on the
  first error even though continuous mode treats errors as transient
  (`UniSpec.Rx.continuous_take_ends_on_error`).
-/

namespace UniSpec.RxR3

/-! ## Traces -/

/-- The events before the first terminal one: values and `resume`s. -/
def values : List REvent → List REvent
  | [] => []
  | e :: s => if isTerminal e then [] else e :: values s

/-- The first terminal event, if any: `none` for a live (quiet) source. -/
def ending : List REvent → Option REvent
  | [] => none
  | e :: s => if isTerminal e then some e else ending s

/-! ## flatMap -/

/-- `rx.flatMap(f)` with synchronous nested streams. `open` says whether the current nested stream
is still running. A `resume` from either side is passed on and nothing ends; a `failed` from
either side ends the stream; the stream completes when the input and the current nested stream
have both completed. -/
def flatMapGo (f : Int → List REvent) : Bool → List REvent → List REvent
  | _, [] => []
  | _, .next x :: s =>
    values (f x) ++
      match ending (f x) with
      | some .failed => [.failed]
      | some .done => flatMapGo f false s
      | _ => flatMapGo f true s
  | o, .resume :: s => .resume :: flatMapGo f o s
  | o, .done :: _ => if o then [] else [.done]
  | _, .failed :: _ => [.failed]

def flatMap (f : Int → List REvent) (s : List REvent) : List REvent := flatMapGo f false s

/-- A nested stream's values, then what follows its end, as today's default mode sees them. -/
theorem observe_nested (t A B : List REvent) :
    Rx.observe ((values t ++
        match ending t with
        | some .failed => [.failed]
        | some .done => A
        | _ => B).map erase) =
      Rx.values (t.map erase) ++
        match Rx.ending (t.map erase) with
        | some .fail => [.fail]
        | some .done => Rx.observe (A.map erase)
        | _ => Rx.observe (B.map erase) := by
  induction t with
  | nil => simp [values, ending, Rx.values, Rx.ending]
  | cons e t ih =>
    cases e with
    | next v =>
      simp only [List.map_append] at ih
      simp [values, ending, isTerminal, erase, Rx.observe, Rx.values, Rx.ending, ih]
    | resume => simp [values, ending, isTerminal, erase, Rx.observe, Rx.values, Rx.ending]
    | done => simp [values, ending, isTerminal, erase, Rx.values, Rx.ending]
    | failed => simp [values, ending, isTerminal, erase, Rx.observe, Rx.values, Rx.ending]

/-- Today's default-mode `flatMap` is this `flatMap` with `resumeAsFailure` before the sink. -/
theorem flatMapGo_default_eq (f : Int → List REvent) (o : Bool) (s : List REvent) :
    (observe (resumeAsFailure (flatMapGo f o s))).map erase =
      Rx.flatMapGo (fun x => (f x).map erase) o (s.map erase) := by
  rw [observe_resumeAsFailure_erase]
  induction s generalizing o with
  | nil => rfl
  | cons e s ih =>
    cases e with
    | next x =>
      simp only [flatMapGo, List.map_cons, erase, Rx.flatMapGo]
      rw [observe_nested, ih false, ih true]
      rcases Rx.ending_cases ((f x).map erase) with h | h | h <;> simp [h]
    | resume => simp [flatMapGo, erase, Rx.observe, Rx.flatMapGo]
    | done => cases o <;> simp [flatMapGo, erase, Rx.observe, Rx.flatMapGo]
    | failed => simp [flatMapGo, erase, Rx.observe, Rx.flatMapGo]

theorem flatMap_default_eq (f : Int → List REvent) (s : List REvent) :
    (observe (resumeAsFailure (flatMap f s))).map erase =
      Rx.flatMap (fun x => (f x).map erase) (s.map erase) := flatMapGo_default_eq f false s

theorem values_nexts_append (vs : List Int) (r : List REvent) :
    values (nexts vs ++ r) = nexts vs ++ values r := by
  induction vs with
  | nil => rfl
  | cons v vs ih => simp_all [nexts, values, isTerminal]

theorem ending_nexts_append (vs : List Int) (r : List REvent) :
    ending (nexts vs ++ r) = ending r := by
  induction vs with
  | nil => rfl
  | cons v vs ih => simp_all [nexts, ending, isTerminal]

/-- A nested `failed` (a failed `Rx.future`) ends `flatMap`: no special rule is needed, because a
terminal failure is terminal by definition. -/
theorem flatMap_nested_failed (vs : List Int) (s : List REvent) :
    flatMap (fun _ => nexts vs ++ [.failed]) (.next 0 :: s) = nexts vs ++ [.failed] := by
  simp [flatMap, flatMapGo, values_nexts_append, ending_nexts_append, values, ending, isTerminal]

/-- A nested `resume` is reported and the nested stream keeps going. -/
theorem flatMap_nested_resume :
    flatMap (fun _ => [.next 1, .resume, .next 2, .done]) [.next 0, .done] =
      [.next 1, .resume, .next 2, .done] := rfl

/-- A live nested stream keeps `flatMap` open after the input completes; a new input value
switches away from it, and the input's completion is then reported at once. -/
theorem flatMap_waits_then_switches :
    flatMap (fun x => if x = 0 then [.next 0] else [.next 1, .done]) [.next 0, .done] =
        [.next 0] ∧
      flatMap (fun x => if x = 0 then [.next 0] else [.next 1, .done]) [.next 0, .next 1, .done] =
        [.next 0, .next 1, .done] := ⟨rfl, rfl⟩

/-! ## take -/

/-- `rx.take(n)`: counts values only; a `resume` passes without ending or counting. -/
def take : Nat → List REvent → List REvent
  | 0, _ => [.done]
  | _ + 1, [] => []
  | n + 1, .next v :: s => .next v :: take n s
  | n + 1, .resume :: s => .resume :: take (n + 1) s
  | _ + 1, e :: _ => [e]

theorem take_default_eq (n : Nat) (s : List REvent) :
    (observe (resumeAsFailure (take n s))).map erase = Rx.take n (s.map erase) := by
  rw [observe_resumeAsFailure_erase]
  induction n generalizing s with
  | zero => simp [take, erase, Rx.observe, Rx.take]
  | succ n ih =>
    cases s with
    | nil => rfl
    | cons e s =>
      cases e with
      | next v => simp [take, erase, Rx.observe, Rx.take, ih]
      | resume => simp [take, erase, Rx.observe, Rx.take]
      | done => simp [take, erase, Rx.observe, Rx.take]
      | failed => simp [take, erase, Rx.observe, Rx.take]

/-- An operator error does not end `take`, and is not counted. -/
theorem take_passes_resume :
    take 1 [.resume, .next 1, .next 2] = [.resume, .next 1, .done] := rfl

/-! ## recoverWith -/

/-- `rx.recoverWith { case _ => g }`. A `resume` runs the recovery stream, then the input
continues (its next value replaces the recovery); a `failed` ended the input, so the stream
becomes the recovery stream. A failing recovery ends the stream. -/
def recoverWith (g : List REvent) : List REvent → List REvent
  | [] => []
  | .next v :: s => .next v :: recoverWith g s
  | .resume :: s =>
    values g ++
      match ending g with
      | some .failed => [.failed]
      | _ => recoverWith g s
  | .done :: _ => [.done]
  | .failed :: _ => observe g

/-- Today's default-mode `recoverWith` (`2026-10-14-rx-completion-semantics.md`, amended
2026-11-20): any recovered error ends the input, and the stream ends with the recovery stream. -/
def oldRecoverWith (g : List Rx.Event) : List Rx.Event → List Rx.Event
  | [] => []
  | .next v :: s => .next v :: oldRecoverWith g s
  | .done :: _ => [.done]
  | .fail :: _ => Rx.observe g

/-- A recovered terminal failure: the stream becomes the recovery stream, as today. -/
theorem recoverWith_failed (g : List REvent) (vs : List Int) (rest : List REvent) :
    recoverWith g (nexts vs ++ .failed :: rest) = nexts vs ++ observe g := by
  induction vs with
  | nil => rfl
  | cons v vs ih => simp_all [nexts, recoverWith]

/-- A recovered operator error: the recovery runs and the input continues to its own end. -/
theorem recoverWith_resume :
    recoverWith [.next 0, .done] [.next 1, .resume, .next 3, .done] =
      [.next 1, .next 0, .next 3, .done] := rfl

/-- Today's default mode treats an operator error (a throwing `map` on 2) differently in `recover`
and `recoverWith`: `recover` lets the input continue to 3, `recoverWith` ends it. -/
theorem old_default_inconsistent :
    Rx.run .default [Rx.boomE 2, Rx.recoverE 0] (Rx.nexts [1, 2, 3] ++ [.done]) =
        [.next 1, .next 0, .next 3, .done] ∧
      Rx.observe (oldRecoverWith [.next 0, .done]
          (Rx.boomE 2 (Rx.nexts [1, 2, 3] ++ [.done]))) =
        [.next 1, .next 0, .done] := ⟨rfl, rfl⟩

/-- With R3 events both operators let the input continue after an operator error. -/
theorem r3_consistent :
    observe (recover 0 ((Op.boom 2).r3 (nexts [1, 2, 3] ++ [.done]))) =
        [.next 1, .next 0, .next 3, .done] ∧
      observe (recoverWith [.next 0, .done] ((Op.boom 2).r3 (nexts [1, 2, 3] ++ [.done]))) =
        [.next 1, .next 0, .next 3, .done] := ⟨rfl, rfl⟩

/-- To keep today's `recoverWith` behavior, end the input at the error first. -/
theorem recoverWith_after_resumeAsFailure :
    observe (recoverWith [.next 0, .done]
        (resumeAsFailure ((Op.boom 2).r3 (nexts [1, 2, 3] ++ [.done])))) =
      [.next 1, .next 0, .done] := rfl

end UniSpec.RxR3
