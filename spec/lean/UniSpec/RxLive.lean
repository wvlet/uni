/-!
# A script model of `RxRunner` over live `RxVar`s

The other Rx models take one finished trace per source. UI code instead subscribes to `RxVar`s
that change while the subscription is live. This model drives vars with a script of actions and
mirrors the state each operator keeps in `RxRunner`:

- `flatMap` and `transformRx` switch to a new nested stream on each input value, cancelling the
  previous one, and `NestedStreamTracker` completes them only after the input and the current
  nested stream have both ended;
- `split` keeps one row var per key;
- `zip` buffers values per source, and `join` keeps the latest value per source;
- `take(n)` stops forwarding after a downstream `Stop` or the nth value;
- `cancel` ends a subscription between two actions.

`RxVar` is synchronous: `:=`, `stop()`, and `setException` deliver to every subscriber before they
return, and a new subscriber gets the current value at once. So a script gives one event order on
every platform. A var delivers a value only when it differs from the current one. A var that has
stopped or failed gets no further actions, as `RxVar`'s contract leaves later updates undefined;
the generated scripts respect this.

The sink records every event it receives. It answers as `RxRunner.run` does (`Stop` to a terminal
event) or, for `take`, as `runOnce` does (`Stop` to every event). `RxVar` ignores `Stop`, so the
model, like `RxRunner`, can deliver events after a terminal one where an operator does not guard
against them. Continuous mode is left out. `GenRxLiveCases.lean` evaluates the model to generate
the cases that `RxLiveSpecTest` replays.
-/

namespace UniSpec.RxLive

/-- What the sink records. -/
inductive Out where
  /-- `OnNext(v)` -/
  | next (v : Int)
  /-- `OnNext((a, b))` from `zip` or `join` -/
  | pair (a b : Int)
  | done
  | fail
  /-- `split` emitted a list with these keys -/
  | keys (ks : List Nat)
  /-- The row of key `k` emitted `v` (to the test's subscriber of that row) -/
  | row (k : Nat) (v : Int)
  /-- The row of key `k` completed -/
  | rowDone (k : Nat)
  deriving Repr, DecidableEq

/-- An action on the vars of a pipeline. -/
inductive Action where
  /-- `var := v` -/
  | set (var : Nat) (v : Int)
  /-- `var.stop()` -/
  | stop (var : Nat)
  /-- `var.setException(e)` -/
  | fail (var : Nat)
  /-- Cancel the subscription -/
  | cancel
  deriving Repr, DecidableEq

/-- What a var delivers to its subscribers. -/
inductive VarEvent where
  | next (v : Int)
  | done
  | fail
  deriving Repr, DecidableEq

/-- The current value of each var, and whether it has stopped or failed. -/
structure Vars where
  val : Nat → Int
  ended : Nat → Bool

/-- Apply an action to the vars: the event the var delivers, if any, and the updated vars. -/
def Vars.apply (vs : Vars) : Action → Option (Nat × VarEvent) × Vars
  | .set i v =>
    if vs.ended i || vs.val i == v then (none, vs)
    else (some (i, .next v), { vs with val := fun j => if j = i then v else vs.val j })
  | .stop i =>
    if vs.ended i then (none, vs)
    else (some (i, .done), { vs with ended := fun j => j == i || vs.ended j })
  | .fail i =>
    if vs.ended i then (none, vs)
    else (some (i, .fail), { vs with ended := fun j => j == i || vs.ended j })
  | .cancel => (none, vs)

/-- Var `i` starts with `i * 10`, except var 0, which starts with 1. -/
def initVars : Vars := { val := fun i => if i = 0 then 1 else i * 10, ended := fun _ => false }

/-- Run a script from a state, collecting what the sink records. -/
def runFrom {σ : Type} (step : σ → Action → σ × List Out) : σ → List Action → List Out
  | _, [] => []
  | s, a :: as => (step s a).2 ++ runFrom step (step s a).1 as

/-- A step that keeps a property of the state and records nothing keeps recording nothing. -/
theorem runFrom_silent {σ : Type} (step : σ → Action → σ × List Out) (p : σ → Prop)
    (h : ∀ s a, p s → p (step s a).1 ∧ (step s a).2 = []) :
    ∀ s as, p s → runFrom step s as = [] := by
  intro s as hs
  induction as generalizing s with
  | nil => rfl
  | cons a as ih =>
    obtain ⟨h1, h2⟩ := h s a hs
    simp [runFrom, h2, ih _ h1]

/-! ## flatMap and transformRx: switching nested streams

Var 0 is the input. The function maps the input value 1 to var 1, 2 to var 2, and throws on any
other value. `transformRx` maps an input error to var 2. -/

/-- The nested var for an input value; `none` when the function throws. -/
def target (x : Int) : Option Nat :=
  if x = 1 then some 1 else if x = 2 then some 2 else none

inductive SwitchKind where
  | flatMap
  | transformRx
  deriving Repr, DecidableEq

structure Switch where
  vars : Vars
  /-- The subscription is not cancelled -/
  live : Bool := true
  /-- The var of the current nested stream. Every earlier one is cancelled. -/
  nested : Option Nat := none
  /-- `NestedStreamTracker.nestedActive`: the current nested stream has not ended -/
  active : Bool := false
  /-- `NestedStreamTracker.inputDone` -/
  inputDone : Bool := false

/-- Subscribe to the nested var `i`, which delivers its current value at once. -/
def Switch.subscribe (s : Switch) (i : Nat) : Switch × List Out :=
  ({ s with nested := some i, active := true }, [.next (s.vars.val i)])

/-- `NestedStreamTracker.completeInput`: complete now unless the nested stream is still active. -/
def Switch.completeInput (s : Switch) : Switch × List Out :=
  if s.inputDone then (s, [])
  else if s.active then ({ s with inputDone := true }, [])
  else ({ s with inputDone := true }, [.done])

/-- An event from the input. A new value first cancels the previous nested stream, even when the
function then throws. -/
def Switch.onInput (k : SwitchKind) (s : Switch) : VarEvent → Switch × List Out
  | .next x =>
    let s := { s with nested := none }
    match target x with
    | some i => s.subscribe i
    | none => ({ s with active := false }, [.fail])
  | .done => s.completeInput
  | .fail =>
    match k with
    | .flatMap => (s, [.fail])
    | .transformRx =>
      -- The error ends the input once the nested stream built from it ends
      let r := s.subscribe 2
      let c := r.1.completeInput
      (c.1, r.2 ++ c.2)

/-- An event from var `i`, which reaches the sink only from the current nested stream. -/
def Switch.onNested (s : Switch) (i : Nat) (e : VarEvent) : Switch × List Out :=
  if s.nested = some i then
    match e with
    | .next v => (s, [.next v])
    | .done => ({ s with active := false }, if s.inputDone then [.done] else [])
    -- The sink answers Stop to the error, so the operator does not complete after it
    | .fail => ({ s with active := false }, [.fail])
  else (s, [])

def Switch.step (k : SwitchKind) (s : Switch) (a : Action) : Switch × List Out :=
  match s.vars.apply a with
  | (none, vs) =>
    if a = .cancel then ({ s with vars := vs, live := false, nested := none }, [])
    else ({ s with vars := vs }, [])
  | (some (i, e), vs) =>
    if !s.live then ({ s with vars := vs }, [])
    else if i = 0 then ({ s with vars := vs }).onInput k e
    else ({ s with vars := vs }).onNested i e

/-- Subscribe: the input var delivers its current value, 1, at once. -/
def Switch.init (k : SwitchKind) : Switch × List Out :=
  Switch.onInput k { vars := initVars } (.next 1)

def runSwitch (k : SwitchKind) (as : List Action) : List Out :=
  (Switch.init k).2 ++ runFrom (Switch.step k) (Switch.init k).1 as

theorem Switch.step_cancelled (k : SwitchKind) (s : Switch) (a : Action) (h : s.live = false) :
    (s.step k a).1.live = false ∧ (s.step k a).2 = [] := by
  unfold Switch.step
  split <;> (try split) <;> simp_all

/-- After `cancel`, the sink records nothing more. -/
theorem switch_cancel (k : SwitchKind) (s : Switch) (as : List Action) :
    runFrom (Switch.step k) (s.step k .cancel).1 as = [] :=
  runFrom_silent _ (fun s => s.live = false) (fun s a h => Switch.step_cancelled k s a h) _ _
    (by simp [Switch.step, Vars.apply])

/-- A var that is not the current nested stream reaches the sink with nothing: a replaced nested
stream is cancelled. -/
theorem switch_replaced_silent (k : SwitchKind) (s : Switch) (i : Nat) (a : Action)
    (hi : i ≠ 0) (hn : s.nested ≠ some i) (ha : (s.vars.apply a).1.map (·.1) = some i) :
    (s.step k a).2 = [] := by
  unfold Switch.step
  split
  · simp_all
  · rename_i i' e vs heq
    simp only [heq, Option.map_some, Option.some.injEq] at ha
    subst ha
    simp [hi, Switch.onNested, hn]

/-- The operator completes only at a step after which the input has completed and the current
nested stream has ended, whichever ended first. -/
theorem switch_done (k : SwitchKind) (s : Switch) (a : Action) (h : Out.done ∈ (s.step k a).2) :
    (s.step k a).1.inputDone = true ∧ (s.step k a).1.active = false := by
  unfold Switch.step at h ⊢
  rcases hap : s.vars.apply a with ⟨_ | ⟨i, e⟩, vs⟩ <;> simp only [hap] at h ⊢
  · by_cases hc : a = .cancel <;> simp [hc] at h
  · by_cases hl : s.live = true
    · by_cases hi : i = 0
      · simp only [hl, hi, Bool.not_true, Bool.false_eq_true, ite_false, ite_true] at h ⊢
        cases e with
        | next x =>
          simp only [Switch.onInput] at h ⊢
          cases ht : target x <;> simp [ht, Switch.subscribe] at h
        | done =>
          simp only [Switch.onInput, Switch.completeInput] at h ⊢
          by_cases hd : s.inputDone = true
          · simp [hd] at h
          · by_cases ha : s.active = true <;> simp_all
        | fail =>
          cases k
          · simp [Switch.onInput] at h
          · simp only [Switch.onInput, Switch.subscribe, Switch.completeInput] at h
            split at h <;> simp at h
      · simp only [hl, hi, Bool.not_true, Bool.false_eq_true, ite_false] at h ⊢
        simp only [Switch.onNested] at h ⊢
        by_cases hn : s.nested = some i
        · cases e <;> simp_all
        · simp [hn] at h
    · simp [hl] at h

/-- Completion waits for the nested stream, in either order. -/
example : runSwitch .flatMap [.stop 0, .stop 1] = [.next 10, .done] := by decide
example : runSwitch .flatMap [.stop 1, .stop 0] = [.next 10, .done] := by decide
/-- Switching away from a nested stream drops it: its later values and completion are ignored. -/
example : runSwitch .flatMap [.set 0 2, .set 1 11, .stop 1, .stop 0] = [.next 10, .next 20] := by
  decide
/-- Switching back subscribes again, which delivers the var's current value. -/
example : runSwitch .flatMap [.set 1 11, .set 0 2, .set 0 1] = [.next 10, .next 11, .next 20,
    .next 11] := by decide
/-- A throwing function cancels the previous nested stream too. -/
example : runSwitch .flatMap [.set 0 0, .set 1 11, .stop 0] = [.next 10, .fail, .done] := by decide

/-! ## split

The input is a var of `(key, value)` items, split by key. The test subscribes to each new row as
soon as a list holding it is emitted, and records the row's events. -/

abbrev Items := List (Nat × Int)

/-- The first item of each key, in list order. -/
def firstItemsGo (seen : List Nat) : Items → Items
  | [] => []
  | (k, a) :: rest =>
    if seen.contains k then firstItemsGo seen rest else (k, a) :: firstItemsGo (k :: seen) rest

def firstItems (l : Items) : Items := firstItemsGo [] l

inductive ListAction where
  | set (items : Items)
  | stop
  | fail
  | cancel
  deriving Repr, DecidableEq

structure Split where
  /-- The input var's current value -/
  src : Items
  /-- The input var has stopped or failed -/
  ended : Bool := false
  live : Bool := true
  /-- Each live row's key and value, in the order `RxRunner`'s `rows` map iterates (insertion
  order, for up to four keys) -/
  rows : Items := []
  lastKeys : Option (List Nat) := none

/-- The keys of a list's first items. -/
def keysOf (l : Items) : List Nat := (firstItems l).map (·.1)

/-- Kept rows whose value changes, which emit the new value. -/
def Split.updates (s : Split) (l : Items) : List Out :=
  (firstItems l).filterMap fun (k, a) =>
    match s.rows.lookup k with
    | some b => if b = a then none else some (Out.row k a)
    | none => none

/-- New rows, whose first value the test's new subscriber receives. -/
def Split.news (s : Split) (l : Items) : List Out :=
  ((firstItems l).filter fun (k, _) => (s.rows.lookup k).isNone).map fun (k, a) => Out.row k a

/-- Removed rows, which stop. -/
def Split.removed (s : Split) (l : Items) : List Out :=
  (s.rows.filter fun (k, _) => !(keysOf l).contains k).map fun (k, _) => Out.rowDone k

/-- A new input list. Kept rows that change emit first; then the list, when its keys changed,
followed by each new row's first value (the test subscribes to it); then the removed rows stop. -/
def Split.onList (s : Split) (l : Items) : Split × List Out :=
  ({ s with rows := firstItems l, lastKeys := some (keysOf l) },
    s.updates l ++ (if s.lastKeys = some (keysOf l) then [] else Out.keys (keysOf l) :: s.news l) ++
      s.removed l)

/-- Stop every live row. -/
def Split.stopRows (s : Split) : Split × List Out :=
  ({ s with rows := [] }, s.rows.map fun (k, _) => Out.rowDone k)

def Split.step (s : Split) : ListAction → Split × List Out
  | .set l =>
    if s.ended || s.src == l then (s, [])
    else
      let s := { s with src := l }
      if s.live then s.onList l else (s, [])
  | .stop =>
    if s.ended then (s, [])
    else
      let s := { s with ended := true }
      if s.live then
        let r := s.stopRows
        (r.1, r.2 ++ [.done])
      else (s, [])
  | .fail =>
    if s.ended then (s, [])
    else
      let s := { s with ended := true }
      (s, if s.live then [.fail] else [])
  | .cancel =>
    if s.live then
      let r := s.stopRows
      ({ r.1 with live := false }, r.2)
    else (s, [])

def splitInit : Items := [(1, 10), (2, 20)]

def Split.init : Split × List Out := Split.onList { src := splitInit } splitInit

def runSplitFrom : Split → List ListAction → List Out
  | _, [] => []
  | s, a :: as => (s.step a).2 ++ runSplitFrom (s.step a).1 as

def runSplit (as : List ListAction) : List Out := Split.init.2 ++ runSplitFrom Split.init.1 as

/-- A list with the same keys as the last emitted one is not emitted again, and stops no row;
only the rows whose value changed emit. -/
theorem split_same_keys (s : Split) (l : Items) (hk : s.lastKeys = some (keysOf l))
    (hr : s.rows.map (·.1) = keysOf l) : (s.onList l).2 = s.updates l := by
  have : s.removed l = [] := by
    simp only [Split.removed, List.map_eq_nil_iff, List.filter_eq_nil_iff]
    intro p hp
    have : p.1 ∈ keysOf l := hr ▸ List.mem_map.mpr ⟨p, hp, rfl⟩
    simpa using this
  simp [Split.onList, hk, this]

/-- A list with new keys is emitted after the changed rows and before the removed rows stop. -/
theorem split_new_keys (s : Split) (l : Items) (hk : s.lastKeys ≠ some (keysOf l)) :
    (s.onList l).2 = s.updates l ++ Out.keys (keysOf l) :: (s.news l ++ s.removed l) := by
  simp [Split.onList, hk]

/-- Only rows emit before the list: a removed row never stops before it. -/
theorem split_updates_rows (s : Split) (l : Items) (o : Out) (h : o ∈ s.updates l) :
    ∃ k v, o = .row k v := by
  simp only [Split.updates, List.mem_filterMap] at h
  obtain ⟨⟨k, a⟩, _, h⟩ := h
  split at h
  · split at h
    · simp at h
    · exact ⟨k, a, (Option.some.inj h).symm⟩
  · simp at h

/-- Every row whose key leaves the list stops. -/
theorem split_removed_stop (s : Split) (l : Items) (k : Nat) (v : Int) (hs : (k, v) ∈ s.rows)
    (hn : k ∉ keysOf l) : Out.rowDone k ∈ s.removed l := by
  simp only [Split.removed, List.mem_map, List.mem_filter]
  exact ⟨(k, v), ⟨hs, by simpa using hn⟩, rfl⟩

/-- Cancel stops every live row, and the sink records nothing more. -/
theorem split_cancel (s : Split) (as : List ListAction) (h : s.live = true) :
    (s.step .cancel).2 = s.rows.map (fun (k, _) => Out.rowDone k) ∧
      runSplitFrom (s.step .cancel).1 as = [] := by
  refine ⟨by simp [Split.step, h, Split.stopRows], ?_⟩
  have hl : (s.step .cancel).1.live = false := by simp [Split.step, h, Split.stopRows]
  generalize (s.step .cancel).1 = t at hl
  induction as generalizing t with
  | nil => rfl
  | cons a as ih =>
    have : (t.step a).1.live = false ∧ (t.step a).2 = [] := by
      cases a <;> simp only [Split.step] <;> (repeat' split) <;> simp_all
    simp [runSplitFrom, this.2, ih _ this.1]

/-- The input's completion stops every live row, then completes. -/
theorem split_done (s : Split) (h : s.live = true) (he : s.ended = false) :
    (s.step .stop).2 = s.rows.map (fun (k, _) => Out.rowDone k) ++ [.done] := by
  simp [Split.step, h, he, Split.stopRows]

/-- A kept row with a new value emits it; the list is not emitted when its keys stay. -/
example : runSplit [.set [(1, 11), (2, 20)]] = [.keys [1, 2], .row 1 10, .row 2 20, .row 1 11] := by
  decide
/-- A repeated key keeps its first item, so this list changes nothing. -/
example : runSplit [.set [(1, 10), (2, 20), (1, 15)]] = [.keys [1, 2], .row 1 10, .row 2 20] := by
  decide
/-- A removed row stops after the list without it. -/
example : runSplit [.set [(1, 10)]] = [.keys [1, 2], .row 1 10, .row 2 20, .keys [1], .rowDone 2] := by
  decide

/-! ## zip and join over two vars

Var 0 starts with 1, and var 1 with 10. `zip` completes once a completed source has no buffered
value left (the shortest source ends it); `join` completes once both sources completed. The first
error fails the operator once. -/

inductive CombineKind where
  | zip
  | join
  deriving Repr, DecidableEq

/-- The last event a source delivered (`CombinedStream.lastEvent`). -/
inductive Last where
  | none
  | next
  | done
  | fail
  deriving Repr, DecidableEq

structure Combine where
  vars : Vars
  live : Bool := true
  /-- `zip`'s buffered values per source -/
  qa : List Int := []
  qb : List Int := []
  /-- `join`'s latest value per source -/
  la : Option Int := none
  lb : Option Int := none
  ea : Last := .none
  eb : Last := .none
  /-- A terminal event was sent -/
  completed : Bool := false

def Combine.isCompleted (k : CombineKind) (s : Combine) : Bool :=
  match k with
  | .zip => (s.ea == .done && s.qa.isEmpty) || (s.eb == .done && s.qb.isEmpty)
  | .join => s.ea == .done && s.eb == .done

def Combine.emit (k : CombineKind) (s : Combine) : Combine × List Out :=
  match k with
  | .zip =>
    match s.qa, s.qb with
    | a :: qa, b :: qb => ({ s with qa := qa, qb := qb }, [.pair a b])
    | _, _ => (s, [])
  | .join =>
    match s.la, s.lb with
    | some a, some b => (s, [.pair a b])
    | _, _ => (s, [])

/-- `CombinedStream.processEvents`: an error fails the operator once; otherwise emit when a value
arrived, and complete when the completion rule holds. -/
def Combine.process (k : CombineKind) (s : Combine) (doEmit : Bool) : Combine × List Out :=
  if s.ea == .fail || s.eb == .fail then
    if s.completed then (s, []) else ({ s with completed := true }, [.fail])
  else
    let r := if doEmit then s.emit k else (s, [])
    if r.1.isCompleted k && !r.1.completed then ({ r.1 with completed := true }, r.2 ++ [.done])
    else r

def lastOf : VarEvent → Last
  | .next _ => .next
  | .done => .done
  | .fail => .fail

/-- An event from source `i` (0 or 1). -/
def Combine.on (k : CombineKind) (s : Combine) (i : Nat) (e : VarEvent) : Combine × List Out :=
  let s :=
    if i = 0 then
      match e with
      | .next v => { s with ea := .next, qa := s.qa ++ [v], la := some v }
      | e => { s with ea := lastOf e }
    else
      match e with
      | .next v => { s with eb := .next, qb := s.qb ++ [v], lb := some v }
      | e => { s with eb := lastOf e }
  s.process k (match e with | .next _ => true | _ => false)

def Combine.step (k : CombineKind) (s : Combine) (a : Action) : Combine × List Out :=
  match s.vars.apply a with
  | (none, vs) =>
    if a = .cancel then ({ s with vars := vs, live := false }, [])
    else ({ s with vars := vs }, [])
  | (some (i, e), vs) =>
    if s.live then ({ s with vars := vs }).on k i e else ({ s with vars := vs }, [])

/-- Subscribe to var 0, then var 1, each delivering its current value at once, then check for
completion. -/
def Combine.init (k : CombineKind) : Combine × List Out :=
  let s0 : Combine := { vars := initVars }
  let r1 := s0.on k 0 (.next 1)
  let r2 := r1.1.on k 1 (.next 10)
  let r3 := r2.1.process k false
  (r3.1, r1.2 ++ r2.2 ++ r3.2)

def runCombine (k : CombineKind) (as : List Action) : List Out :=
  (Combine.init k).2 ++ runFrom (Combine.step k) (Combine.init k).1 as

theorem Combine.step_cancelled (k : CombineKind) (s : Combine) (a : Action) (h : s.live = false) :
    (s.step k a).1.live = false ∧ (s.step k a).2 = [] := by
  unfold Combine.step
  split <;> (try split) <;> simp_all

/-- After `cancel`, the sink records nothing more. -/
theorem combine_cancel (k : CombineKind) (s : Combine) (as : List Action) :
    runFrom (Combine.step k) (s.step k .cancel).1 as = [] :=
  runFrom_silent _ (fun s => s.live = false) (fun s a h => Combine.step_cancelled k s a h) _ _
    (by simp [Combine.step, Vars.apply])

/-- `zip` pairs its sources' values in order: until it fails, one buffer is always empty, so a new
value pairs with the oldest buffered value of the other source. -/
theorem zip_buffers (s : Combine) (i : Nat) (v : Int) (hok : s.ea ≠ .fail ∧ s.eb ≠ .fail)
    (h : s.qa = [] ∨ s.qb = []) :
    (s.on .zip i (.next v)).1.qa = [] ∨ (s.on .zip i (.next v)).1.qb = [] := by
  obtain ⟨ha, hb⟩ := hok
  by_cases hi : i = 0 <;>
    rcases hqa : s.qa with _ | ⟨x, qa⟩ <;> rcases hqb : s.qb with _ | ⟨y, qb⟩ <;>
    simp_all [Combine.on, Combine.process, Combine.emit] <;> split <;> simp_all

/-- In default mode, `zip` over two vars keeps pairing: it does not complete after the first. -/
example : runCombine .zip [.set 0 2, .set 1 20] = [.pair 1 10, .pair 2 20] := by decide
/-- The shorter source ends `zip` once its buffered values are paired. -/
example : runCombine .zip [.set 0 2, .stop 0, .set 1 20] = [.pair 1 10, .pair 2 20, .done] := by
  decide
/-- `join` emits the latest pair on every change and completes when both sources complete. -/
example : runCombine .join [.set 0 2, .stop 0, .set 1 20, .stop 1] =
    [.pair 1 10, .pair 2 10, .pair 2 20, .done] := by decide
/-- An error fails the operator once. -/
example : runCombine .join [.fail 0, .fail 1, .set 1 20] = [.pair 1 10, .fail] := by decide

/-! ## take(n) and a downstream Stop

Var 0 starts with 1. -/

/-- How the sink answers a value: `Continue` (`RxRunner.run`) or `Stop` (`runOnce`, before its
own filter). Both answer `Stop` to a terminal event. -/
inductive Sink where
  | run
  | once
  deriving Repr, DecidableEq

structure Take where
  vars : Vars
  live : Bool := true
  count : Nat := 0
  /-- `take` forwards nothing more -/
  done : Bool := false

def Take.on (n : Nat) (sink : Sink) (s : Take) : VarEvent → Take × List Out
  | .next v =>
    if s.done then (s, [])
    else if s.count + 1 < n then
      -- A downstream Stop before the nth value: forward nothing more, but stay subscribed
      ({ s with count := s.count + 1, done := sink == .once }, [.next v])
    else
      -- The nth value: complete unless the downstream stopped, and cancel the upstream
      ({ s with count := s.count + 1, done := true, live := false },
        .next v :: if sink == .run then [.done] else [])
  | .done => if s.done then (s, []) else ({ s with done := true, live := false }, [.done])
  | .fail => if s.done then (s, []) else ({ s with done := true, live := false }, [.fail])

def Take.step (n : Nat) (sink : Sink) (s : Take) (a : Action) : Take × List Out :=
  match s.vars.apply a with
  | (none, vs) =>
    if a = .cancel then ({ s with vars := vs, live := false }, [])
    else ({ s with vars := vs }, [])
  | (some (_, e), vs) =>
    if s.live then ({ s with vars := vs }).on n sink e else ({ s with vars := vs }, [])

/-- `take(0)` completes without subscribing. -/
def Take.init (n : Nat) (sink : Sink) : Take × List Out :=
  if n = 0 then ({ vars := initVars, live := false, done := true }, [.done])
  else Take.on n sink { vars := initVars } (.next 1)

def runTake (n : Nat) (sink : Sink) (as : List Action) : List Out :=
  (Take.init n sink).2 ++ runFrom (Take.step n sink) (Take.init n sink).1 as

theorem Take.step_done (n : Nat) (sink : Sink) (s : Take) (a : Action) (h : s.done = true) :
    (s.step n sink a).1.done = true ∧ (s.step n sink a).2 = [] := by
  unfold Take.step
  rcases hap : s.vars.apply a with ⟨_ | ⟨i, e⟩, vs⟩
  · by_cases hc : a = .cancel <;> simp [hc, h]
  · by_cases hl : s.live = true <;> cases e <;> simp [hl, h, Take.on]

theorem Take.step_cancelled (n : Nat) (sink : Sink) (s : Take) (a : Action)
    (h : s.live = false) : (s.step n sink a).1.live = false ∧ (s.step n sink a).2 = [] := by
  unfold Take.step
  split <;> (try split) <;> simp_all

/-- With a sink that answers `Stop` to every event, as `runOnce` does, `take(n)` delivers exactly
one event, however the var changes, completes, or fails afterwards. -/
theorem take_once (n : Nat) (as : List Action) : runTake n .once as = (Take.init n .once).2 ∧
    (Take.init n .once).2.length = 1 := by
  have hd : (Take.init n .once).1.done = true := by
    unfold Take.init
    by_cases h0 : n = 0
    · simp [h0]
    · by_cases h1 : 1 < n <;> simp [h0, h1, Take.on]
  refine ⟨?_, ?_⟩
  · simp only [runTake,
      runFrom_silent _ (fun s => s.done = true) (fun s a h => Take.step_done n .once s a h) _ _ hd,
      List.append_nil]
  · unfold Take.init
    by_cases h0 : n = 0
    · simp [h0]
    · by_cases h1 : 1 < n <;> simp [h0, h1, Take.on]

/-- After `cancel`, the sink records nothing more. -/
theorem take_cancel (n : Nat) (sink : Sink) (s : Take) (as : List Action) :
    runFrom (Take.step n sink) (s.step n sink .cancel).1 as = [] :=
  runFrom_silent _ (fun s => s.live = false) (fun s a h => Take.step_cancelled n sink s a h) _ _
    (by simp [Take.step, Vars.apply])

example : runTake 2 .run [.set 0 2, .set 0 3] = [.next 1, .next 2, .done] := by decide
example : runTake 2 .once [.set 0 2, .stop 0] = [.next 1] := by decide

end UniSpec.RxLive
