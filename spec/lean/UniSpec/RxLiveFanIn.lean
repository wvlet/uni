import UniSpec.RxLive

/-!
# `merge` and `parFlatMap` over live `RxVar`s

Both operators join streams through `FanIn` in `RxRunner`: a set of running slots, one per
subscribed stream. The operator completes when the last slot ends. A var's failure (a failed
`OnCompletion`) stops it, cancelling every slot, as does `cancel`. An error raised by the
function (an `OnError`) passes, and the operator keeps running.

`parFlatMap(n)` runs at most `n` nested streams. Further input values wait in a queue, and a nested
stream that ends hands its permit to the next queued value. Its event order depends on thread
scheduling only when nested streams emit from other threads. Nested `RxVar`s deliver on the
caller's thread, so a script gives one event order here, and the model can state the permit rule.

This reuses the script runner of `RxLive.lean`. `GenRxLiveCases.lean` evaluates both models.
-/

namespace UniSpec.RxLive

/-! ## merge of two vars

Var 0 starts with 1, and var 1 with 10. -/

structure Merge where
  vars : Vars
  /-- `FanIn.stopped`: completed, failed, or cancelled -/
  stopped : Bool := false
  /-- The sources whose slot is still running -/
  running : List Nat := [0, 1]

def Merge.on (s : Merge) (i : Nat) (e : VarEvent) : Merge × List Out :=
  if s.stopped || !s.running.contains i then (s, [])
  else
    match e with
    | .next v => (s, [.next v])
    | .done =>
      let r := s.running.erase i
      if r.isEmpty then ({ s with running := r, stopped := true }, [.done])
      else ({ s with running := r }, [])
    -- A var's failure ends the merge with it, cancelling the other source
    | .fail => ({ s with stopped := true, running := [] }, [.fail])

def Merge.step (s : Merge) (a : Action) : Merge × List Out :=
  match s.vars.apply a with
  | (none, vs) =>
    if a = .cancel then ({ s with vars := vs, stopped := true }, [])
    else ({ s with vars := vs }, [])
  | (some (i, e), vs) => ({ s with vars := vs }).on i e

/-- Subscribe to var 0, then var 1, each delivering its current value at once. -/
def Merge.init : Merge × List Out :=
  ({ vars := initVars }, [.next 1, .next 10])

def runMerge (as : List Action) : List Out :=
  Merge.init.2 ++ runFrom Merge.step Merge.init.1 as

theorem Merge.step_stopped (s : Merge) (a : Action) (h : s.stopped = true) :
    (s.step a).1.stopped = true ∧ (s.step a).2 = [] := by
  unfold Merge.step
  split
  · split <;> simp_all
  · simp [Merge.on, h]

/-- Once the merge completed, failed, or was cancelled, the sink records nothing more. -/
theorem merge_stopped (s : Merge) (as : List Action) (h : s.stopped = true) :
    runFrom Merge.step s as = [] :=
  runFrom_silent _ (fun s => s.stopped = true) (fun s a h => Merge.step_stopped s a h) _ _ h

/-- After `cancel`, the sink records nothing more. -/
theorem merge_cancel (s : Merge) (as : List Action) :
    runFrom Merge.step (s.step .cancel).1 as = [] :=
  merge_stopped _ _ (by simp [Merge.step, Vars.apply])

/-- A var's failure stops the merge. -/
theorem merge_fail_stops (s : Merge) (i : Nat) :
    (s.on i .fail).1.stopped = true ∨ (s.on i .fail).2 = [] := by
  unfold Merge.on
  split
  · simp
  · simp

/-- The merge completes only when its last running source completes. -/
theorem merge_done (s : Merge) (i : Nat) (e : VarEvent) (h : Out.done ∈ (s.on i e).2) :
    e = .done ∧ s.running.erase i = [] := by
  unfold Merge.on at h
  split at h
  · simp at h
  · cases e with
    | next v => simp at h
    | done =>
      simp only at h
      split at h
      · rename_i he
        exact ⟨rfl, List.isEmpty_iff.mp he⟩
      · simp at h
    | fail => simp at h

example : runMerge [.set 0 2, .stop 0, .set 1 20, .stop 1] =
    [.next 1, .next 10, .next 2, .next 20, .done] := by decide
example : runMerge [.fail 0, .set 1 20] = [.next 1, .next 10, .fail] := by decide

/-! ## parFlatMap(n)

Var 0 is the input. The function maps 1, 2, and 3 to vars 1, 2, and 3, and throws on any other
value.

Following the code, a nested stream that ends hands its permit to the next queued value. The model
removes the ended slot first and starts a queued value only while fewer than `n` slots run; with at
most `n` slots, that is exactly when the code hands the permit on, and it makes the bound a
property of every step. -/

/-- The nested var for an input value; `none` when the function throws. -/
def parTarget (x : Int) : Option Nat :=
  if x = 1 ∨ x = 2 ∨ x = 3 then some x.toNat else none

structure ParFlatMap where
  vars : Vars
  /-- `parallelism` -/
  n : Nat
  /-- `FanIn.stopped` -/
  stopped : Bool := false
  /-- The input's slot is still running -/
  inputOpen : Bool := true
  /-- The running nested streams, as (slot id, var), in subscription order -/
  slots : List (Nat × Nat) := []
  nextId : Nat := 0
  /-- Input values waiting for a permit -/
  pending : List Int := []

/-- Stop the operator, cancelling every slot. -/
def ParFlatMap.stopAll (s : ParFlatMap) : ParFlatMap :=
  { s with stopped := true, inputOpen := false, slots := [] }

/-- Start queued values while a permit is free. A value whose function throws reports an `OnError`
at once, and its permit passes to the next value. -/
def ParFlatMap.handoff : Nat → ParFlatMap → ParFlatMap × List Out
  | 0, s => (s, [])
  | fuel + 1, s =>
    if s.stopped || s.slots.length ≥ s.n then (s, [])
    else
      match s.pending with
      | [] => (s, [])
      | x :: rest =>
        let s := { s with pending := rest }
        match parTarget x with
        | some i =>
          -- Subscribe: the var delivers its current value at once
          ({ s with slots := s.slots ++ [(s.nextId, i)], nextId := s.nextId + 1 },
            [.next (s.vars.val i)])
        | none =>
          let r := handoff fuel s
          (r.1, .fail :: r.2)

def ParFlatMap.release (s : ParFlatMap) : ParFlatMap × List Out :=
  s.handoff (s.pending.length + 1)

/-- Complete when the input and every nested stream ended. -/
def ParFlatMap.checkDone (s : ParFlatMap) : ParFlatMap × List Out :=
  if !s.stopped && !s.inputOpen && s.slots.isEmpty then ({ s with stopped := true }, [.done])
  else (s, [])

/-- A nested stream ended: hand its permit on, then complete if nothing is left. -/
def ParFlatMap.endSlot (s : ParFlatMap) (id : Nat) : ParFlatMap × List Out :=
  let r := ({ s with slots := s.slots.filter (·.1 ≠ id) }).release
  let c := r.1.checkDone
  (c.1, r.2 ++ c.2)

/-- An event from the nested stream in slot `id`. A nested var's failure stops the operator. -/
def ParFlatMap.onSlot (s : ParFlatMap) (id : Nat) : VarEvent → ParFlatMap × List Out
  | .next v => (s, [.next v])
  | .done => s.endSlot id
  | .fail => (s.stopAll, [.fail])

def ParFlatMap.onInput (s : ParFlatMap) : VarEvent → ParFlatMap × List Out
  | .next x => ({ s with pending := s.pending ++ [x] }).release
  | .done => ({ s with inputOpen := false }).checkDone
  -- The input var's failure stops the operator
  | .fail => (s.stopAll, [.fail])

/-- Var `i` delivers to its subscribers in subscription order. It iterates over a snapshot, so a
slot that a handoff subscribes while the event is delivered does not receive it. -/
def ParFlatMap.onNestedFrom (i : Nat) (e : VarEvent) :
    List Nat → ParFlatMap × List Out → ParFlatMap × List Out
  | [], acc => acc
  | id :: ids, (s, out) =>
    if s.stopped || !s.slots.any (·.1 == id) then onNestedFrom i e ids (s, out)
    else
      let r := s.onSlot id e
      onNestedFrom i e ids (r.1, out ++ r.2)

def ParFlatMap.onVar (s : ParFlatMap) (i : Nat) (e : VarEvent) : ParFlatMap × List Out :=
  if s.stopped then (s, [])
  else if i = 0 then (if s.inputOpen then s.onInput e else (s, []))
  else ParFlatMap.onNestedFrom i e ((s.slots.filter (·.2 = i)).map (·.1)) (s, [])

def ParFlatMap.step (s : ParFlatMap) (a : Action) : ParFlatMap × List Out :=
  match s.vars.apply a with
  | (none, vs) =>
    if a = .cancel then ({ s with vars := vs }.stopAll, [])
    else ({ s with vars := vs }, [])
  | (some (i, e), vs) => ({ s with vars := vs }).onVar i e

/-- Subscribe: the input var delivers its current value, 1, at once. -/
def ParFlatMap.init (n : Nat) : ParFlatMap × List Out :=
  ParFlatMap.onInput { vars := initVars, n := n } (.next 1)

def runParFlatMap (n : Nat) (as : List Action) : List Out :=
  (ParFlatMap.init n).2 ++ runFrom ParFlatMap.step (ParFlatMap.init n).1 as

/-! ### Theorems -/

theorem ParFlatMap.step_stopped (s : ParFlatMap) (a : Action) (h : s.stopped = true) :
    (s.step a).1.stopped = true ∧ (s.step a).2 = [] := by
  unfold ParFlatMap.step
  split
  · split <;> simp_all [ParFlatMap.stopAll]
  · simp [ParFlatMap.onVar, h]

/-- After `cancel`, the sink records nothing more. -/
theorem parFlatMap_cancel (s : ParFlatMap) (as : List Action) :
    runFrom ParFlatMap.step (s.step .cancel).1 as = [] :=
  runFrom_silent _ (fun s => s.stopped = true) (fun s a h => ParFlatMap.step_stopped s a h) _ _
    (by simp [ParFlatMap.step, Vars.apply, ParFlatMap.stopAll])

/-- The bound: at most `n` nested streams are subscribed. -/
def ParFlatMap.bounded (s : ParFlatMap) : Prop := s.slots.length ≤ s.n

theorem ParFlatMap.stopAll_bounded (s : ParFlatMap) : s.stopAll.bounded ∧ s.stopAll.n = s.n := by
  simp [ParFlatMap.stopAll, ParFlatMap.bounded]

theorem ParFlatMap.handoff_bounded (fuel : Nat) (s : ParFlatMap) (h : s.bounded) :
    (s.handoff fuel).1.bounded ∧ (s.handoff fuel).1.n = s.n := by
  induction fuel generalizing s with
  | zero => exact ⟨h, rfl⟩
  | succ fuel ih =>
    unfold ParFlatMap.handoff
    split
    · exact ⟨h, rfl⟩
    · rename_i hs
      have hlt : s.slots.length < s.n := by simp at hs; omega
      split
      · exact ⟨h, rfl⟩
      · split
        · simp [ParFlatMap.bounded]; omega
        · exact ih _ h

theorem ParFlatMap.checkDone_bounded (s : ParFlatMap) (h : s.bounded) :
    s.checkDone.1.bounded ∧ s.checkDone.1.n = s.n := by
  unfold ParFlatMap.checkDone
  split
  · exact ⟨h, rfl⟩
  · exact ⟨h, rfl⟩

theorem ParFlatMap.endSlot_bounded (s : ParFlatMap) (id : Nat) (h : s.bounded) :
    (s.endSlot id).1.bounded ∧ (s.endSlot id).1.n = s.n := by
  have h1 : ({ s with slots := s.slots.filter (·.1 ≠ id) } : ParFlatMap).bounded :=
    Nat.le_trans (List.length_filter_le _ _) h
  obtain ⟨h2, n2⟩ := ParFlatMap.handoff_bounded _ _ h1
  obtain ⟨h3, n3⟩ := ParFlatMap.checkDone_bounded _ h2
  exact ⟨h3, by simp [ParFlatMap.endSlot, ParFlatMap.release] at n2 n3 ⊢; rw [n3, n2]⟩

theorem ParFlatMap.onSlot_bounded (s : ParFlatMap) (id : Nat) (e : VarEvent) (h : s.bounded) :
    (s.onSlot id e).1.bounded ∧ (s.onSlot id e).1.n = s.n := by
  cases e with
  | next v => exact ⟨h, rfl⟩
  | done => exact ParFlatMap.endSlot_bounded s id h
  | fail => exact ParFlatMap.stopAll_bounded _

theorem ParFlatMap.onInput_bounded (s : ParFlatMap) (e : VarEvent) (h : s.bounded) :
    (s.onInput e).1.bounded ∧ (s.onInput e).1.n = s.n := by
  cases e with
  | next x =>
    exact ParFlatMap.handoff_bounded _ ({ s with pending := s.pending ++ [x] } : ParFlatMap) h
  | done => exact ParFlatMap.checkDone_bounded ({ s with inputOpen := false } : ParFlatMap) h
  | fail => exact ParFlatMap.stopAll_bounded _

theorem ParFlatMap.onNestedFrom_bounded (i : Nat) (e : VarEvent) (n : Nat) :
    ∀ (ids : List Nat) (acc : ParFlatMap × List Out), acc.1.bounded → acc.1.n = n →
      (ParFlatMap.onNestedFrom i e ids acc).1.bounded ∧
      (ParFlatMap.onNestedFrom i e ids acc).1.n = n := by
  intro ids
  induction ids with
  | nil => intro acc h hn; exact ⟨h, hn⟩
  | cons id ids ih =>
    intro ⟨s, out⟩ h hn
    unfold ParFlatMap.onNestedFrom
    split
    · exact ih _ h hn
    · obtain ⟨h1, n1⟩ := ParFlatMap.onSlot_bounded s id e h
      exact ih _ h1 (by simp only at hn ⊢; rw [n1, hn])

/-- The permit bound: every step keeps at most `n` nested streams subscribed. -/
theorem parFlatMap_bounded (s : ParFlatMap) (a : Action) (h : s.bounded) :
    (s.step a).1.bounded ∧ (s.step a).1.n = s.n := by
  unfold ParFlatMap.step
  rcases hap : s.vars.apply a with ⟨_ | ⟨i, e⟩, vs⟩
  · by_cases hc : a = .cancel
    · simp only [hc, ite_true]; exact ParFlatMap.stopAll_bounded _
    · simp only [hc, ite_false]; exact ⟨h, trivial⟩
  · have hv : ({ s with vars := vs } : ParFlatMap).bounded := h
    simp only [ParFlatMap.onVar]
    by_cases hs : s.stopped = true
    · simp only [hs, ite_true]; exact ⟨hv, trivial⟩
    · simp only [hs, Bool.false_eq_true, ite_false]
      by_cases hi : i = 0
      · by_cases ho : s.inputOpen = true
        · simp only [hi, ho, ite_true]; exact ParFlatMap.onInput_bounded _ e hv
        · simp only [hi, ho, ite_true, Bool.false_eq_true, ite_false]; exact ⟨hv, trivial⟩
      · simp only [hi, ite_false]
        exact ParFlatMap.onNestedFrom_bounded _ _ _ _ _ hv rfl

/-- Subscribing keeps the bound too, so by `parFlatMap_bounded` it holds after every script. -/
theorem ParFlatMap.init_bounded (n : Nat) :
    (ParFlatMap.init n).1.bounded ∧ (ParFlatMap.init n).1.n = n := by
  have : ({ vars := initVars, n := n } : ParFlatMap).bounded := by
    simp [ParFlatMap.bounded]
  exact ParFlatMap.handoff_bounded _ _ this

/-- With one permit, queued values start in input order, each when the running one ends. -/
example : runParFlatMap 1 [.set 0 2, .set 0 3, .stop 1, .stop 2, .stop 0, .stop 3] =
    [.next 10, .next 20, .next 30, .done] := by decide
/-- With two permits, the second value starts at once. -/
example : runParFlatMap 2 [.set 0 2, .set 1 11, .set 2 21] =
    [.next 10, .next 20, .next 11, .next 21] := by decide
/-- A nested var's failure stops the operator. -/
example : runParFlatMap 1 [.set 0 2, .fail 1, .set 2 21] = [.next 10, .fail] := by decide
/-- A throwing function reports an error and hands its permit on: the operator keeps running. -/
example : runParFlatMap 1 [.set 0 0, .set 0 2, .stop 1, .stop 2, .stop 0] =
    [.next 10, .fail, .next 20, .done] := by decide
/-- Completion waits for the input and every nested stream. -/
example : runParFlatMap 2 [.set 0 2, .stop 0, .stop 1, .stop 2] =
    [.next 10, .next 20, .done] := by decide

end UniSpec.RxLive
