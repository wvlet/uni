/-!
# A model of how `DomRenderer` places Rx sections and keyed lists

`DomRenderer` renders an `Rx` into a *section*: two empty text nodes in the parent, with the
content between them. On each emission it clears the section and renders the new value at the
section's anchor. A keyed list (`each`) gives the list and each item a pair of markers, and on each
update removes gone items, renders new ones, renders changed ones again, and moves the rest into the
new order. Everything depends on one invariant: **everything a value renders lands at its anchor,
inside its section.**

The model keeps the parent as a flat list of nodes with ids, and runs the same steps as the Scala
code: insert before an anchor, the backward clear walk, and the keyed walk behind a cursor.

- `denote` is the spec: the text the current variable values should show.
- `render` and `update` are the implementation, for two strategies for an `RxElement`:
  `atAnchor` (the fix) and `appendThenMove` (before the fix: append to the parent, then move only
  the last node to the anchor).
- `GenDomRenderCases.lean` runs every generated case through `atAnchor`, checks that the text equals
  `denote` after every step, and writes the node ids so `DomRenderSpecTest` can check node identity
  in Chromium too.

The theorems cover the primitives the invariant rests on: inserting at an anchor, the clear walk
removing exactly a section's content when its markers are paired (and crashing when they are not,
as before the fix), and a keyed list keeping one item per key.
-/

namespace UniSpec.DomRender

/-! ## Views and their meaning -/

/-- The shapes `DomRenderer` places. A `val` shows the value of the innermost section, or the
value of the keyed item it is in. -/
inductive View where
  /-- `span(s)` -/
  | text (s : String)
  /-- `span(value.toString)` -/
  | val
  /-- `DomNode.group(...)` -/
  | group (vs : List View)
  /-- An `RxElement` whose `render` returns `Embedded(body)` -/
  | comp (body : View)
  /-- `scalar(x).map(v => body)`, an Rx section over an `RxVar[Int]` -/
  | sec (x : Nat) (body : View)
  /-- `list(x).each(_._1)(p => item)`, a keyed list over an `RxVar[Seq[(Int, Int)]]`; `val` is
  `p._2` -/
  | keyed (x : Nat) (item : View)
  deriving Repr, Inhabited

/-- Current variable values. Scalars start at 0 and lists start empty, as in the replay. -/
structure Env where
  scalar : Nat → Nat
  list : Nat → List (Nat × Nat)

def Env.empty : Env := ⟨fun _ => 0, fun _ => []⟩

def Env.setScalar (e : Env) (x v : Nat) : Env :=
  { e with scalar := fun y => if y = x then v else e.scalar y }

def Env.setList (e : Env) (x : Nat) (ps : List (Nat × Nat)) : Env :=
  { e with list := fun y => if y = x then ps else e.list y }

def dedupAux (seen : List Nat) : List (Nat × Nat) → List (Nat × Nat)
  | [] => []
  | (k, v) :: rest =>
    if k ∈ seen then dedupAux seen rest else (k, v) :: dedupAux (k :: seen) rest

/-- The first item of each key, in order: a repeated key is skipped. -/
def dedup (ps : List (Nat × Nat)) : List (Nat × Nat) := dedupAux [] ps

mutual
/-- The spec: the leaf texts a view should show, in order. -/
def denote (env : Env) (cur : Nat) : View → List String
  | .text s => [s]
  | .val => [toString cur]
  | .group vs => denoteAll env cur vs
  | .comp b => denote env cur b
  | .sec x b => denote env (env.scalar x) b
  | .keyed x it => (dedup (env.list x)).flatMap fun p => denote env p.2 it

def denoteAll (env : Env) (cur : Nat) : List View → List String
  | [] => []
  | v :: vs => denote env cur v ++ denoteAll env cur vs
end

/-! ## The parent's child list -/

inductive Kind where
  | leaf (s : String)
  /-- An empty text node: a section's or keyed item's marker -/
  | mark
  deriving Repr, DecidableEq

structure Node where
  id : Nat
  kind : Kind
  deriving Repr, DecidableEq

def ids (d : List Node) : List Nat := d.map (·.id)

def removeNode (i : Nat) (d : List Node) : List Node := d.filter (·.id != i)

/-- Insert `n` right before the node `a`; `none` when `a` is not a child (`NotFoundError`). -/
def insertAt (n : Node) (a : Nat) : List Node → Option (List Node)
  | [] => none
  | m :: ms => if m.id = a then some (n :: m :: ms) else (insertAt n a ms).map (m :: ·)

/-- `parent.insertBefore(n, anchor)`, or `appendChild(n)` without an anchor. A node that is
already a child moves. -/
def insertBefore (n : Node) : Option Nat → List Node → Option (List Node)
  | none, d => some (removeNode n.id d ++ [n])
  | some a, d => insertAt n a (removeNode n.id d)

/-- `previousSibling`; `none` for the first child or a node that is not a child. -/
def prevOf (i : Nat) : List Node → Option Nat
  | a :: b :: rest => if b.id = i then some a.id else prevOf i (b :: rest)
  | _ => none

/-- `nextSibling`; `none` for the last child or a node that is not a child. -/
def nextOf (i : Nat) : List Node → Option Nat
  | a :: b :: rest => if a.id = i then some b.id else nextOf i (b :: rest)
  | _ => none

/-- `clearMountSection(start = back, end = front)`: remove `back`'s previous sibling until it is
`front`. There is no bound other than the parent's first child: reaching it calls
`removeChild(null)`, which throws (`none`). The fuel is the parent's length + 1. -/
theorem prevOf_mem {i p : Nat} {d : List Node} (h : prevOf i d = some p) : p ∈ ids d := by
  induction d with
  | nil => simp [prevOf] at h
  | cons a rest ih =>
    cases rest with
    | nil => simp [prevOf] at h
    | cons b rest =>
      simp only [prevOf] at h
      split at h
      · simp at h; simp [ids, h]
      · have := ih h; simp [ids] at this ⊢; exact Or.inr this

def clearSection (front back : Nat) : Nat → List Node → Option (List Node)
  | 0, _ => none
  | fuel + 1, d =>
    match prevOf back d with
    | none => none
    | some p => if p = front then some d else clearSection front back fuel (removeNode p d)

/-- Remove `front`'s next sibling until it is `back` (a keyed item's content). -/
def clearForward (front back : Nat) : Nat → List Node → Option (List Node)
  | 0, _ => none
  | fuel + 1, d =>
    match nextOf front d with
    | none => none
    | some n => if n = back then some d else clearForward front back fuel (removeNode n d)

/-- A keyed item's nodes, `front` to `back` inclusive, following `nextSibling`. -/
def spanIds (back : Nat) : Nat → Nat → List Node → Option (List Nat)
  | 0, _, _ => none
  | fuel + 1, cur, d =>
    if cur = back then some [cur]
    else match nextOf cur d with
      | none => none
      | some n => (spanIds back fuel n d).map (cur :: ·)

/-! ## The renderer -/

structure Item where
  key : Nat
  val : Nat
  front : Nat
  back : Nat
  scope : Nat
  deriving Repr

inductive SubKind where
  /-- An Rx section; `content` is the scope its current content's subscriptions belong to. In the
  Scala code `front` is `end` and `back` is `start`: content goes in before `start`. -/
  | sec (front back : Nat) (body : View) (content : Nat)
  /-- A keyed list after the marker `listFront`. -/
  | keyed (listFront : Nat) (item : View) (items : List Item)

/-- A live `RxVar` subscription, owned by the scope it was rendered in. -/
structure Sub where
  id : Nat
  owner : Nat
  var : Nat
  kind : SubKind

structure St where
  dom : List Node := []
  fresh : Nat := 1
  subs : List Sub := []
  env : Env := Env.empty

abbrev M := StateT St (Except String)

inductive Strategy where
  /-- The fix: an `RxElement` renders at its anchor. -/
  | atAnchor
  /-- Before the fix: an `RxElement` renders at the end of the parent, then only the parent's
  last node moves to the anchor. -/
  | appendThenMove
  deriving Repr, DecidableEq

def freshId : M Nat := modifyGet fun s => (s.fresh, { s with fresh := s.fresh + 1 })

def domOp (msg : String) (f : List Node → Option (List Node)) : M Unit := do
  let s ← get
  match f s.dom with
  | some d => set { s with dom := d }
  | none => throw msg

def newNode (k : Kind) (anchor : Option Nat) : M Nat := do
  let i ← freshId
  domOp "insertBefore" (insertBefore ⟨i, k⟩ anchor)
  pure i

def moveNode (i : Nat) (anchor : Option Nat) : M Unit := do
  match (← get).dom.find? (·.id = i) with
  | some n => domOp "insertBefore" (insertBefore n anchor)
  | none => throw "insertBefore"

def lastId : M (Option Nat) := return (← get).dom.getLast?.map (·.id)

def scopesOf : SubKind → List Nat
  | .sec _ _ _ c => [c]
  | .keyed _ _ items => items.map (·.scope)

/-- Cancel every subscription in a scope and, through them, in the scopes they own. A scope is
always created after the subscription that owns it, so one pass in creation order finds them. -/
def cancelScope (s : Nat) : M Unit := modify fun st =>
  let (_, kept) := st.subs.foldl (init := ([s], ([] : List Sub))) fun (dead, kept) sub =>
    if sub.owner ∈ dead then (dead ++ scopesOf sub.kind, kept) else (dead, kept ++ [sub])
  { st with subs := kept }

def getSub (i : Nat) : M (Option Sub) := return (← get).subs.find? (·.id = i)

def putSub (sub : Sub) : M Unit := modify fun st =>
  { st with subs := st.subs.map fun s => if s.id = sub.id then sub else s }

def addSub (sub : Sub) : M Unit := modify fun st => { st with subs := st.subs ++ [sub] }

/-- A section's `OnNext(v)`: clear it, cancel its content, and render `v` before `back`. -/
def secEmit (body : Option Nat → Nat → Nat → M Unit) (id v : Nat) : M Unit := do
  let some sub ← getSub id | pure ()
  let .sec front back b content := sub.kind | pure ()
  domOp "removeChild(null)" fun d => clearSection front back (d.length + 1) d
  cancelScope content
  let c ← freshId
  putSub { sub with kind := .sec front back b c }
  body (some back) v c

/-- A keyed list's `OnNext(values)`, step by step as `DomRenderer.renderKeyedList.update`. -/
def keyedEmit (renderItem : Option Nat → Nat → Nat → M Unit) (id : Nat)
    (values : List (Nat × Nat)) : M Unit := do
  let some sub ← getSub id | pure ()
  let .keyed listFront it items := sub.kind | pure ()
  let latest := dedup values
  -- Remove the items whose key is gone
  for item in items do
    if !(latest.any (·.1 == item.key)) then
      cancelScope item.scope
      let d := (← get).dom
      let some ns := spanIds item.back (d.length + 1) item.front d | throw "nodesOf"
      for n in ns do
        domOp "removeChild" fun d => some (removeNode n d)
  -- Walk the new order, moving or creating each item right after the previous one
  let mut cursor := listFront
  let mut next : List Item := []
  for (k, v) in latest do
    let ref := nextOf cursor (← get).dom
    match items.find? (·.key == k) with
    | some ex =>
      let mut ex := ex
      if ref != some ex.front then
        let d := (← get).dom
        let some ns := spanIds ex.back (d.length + 1) ex.front d | throw "nodesOf"
        for n in ns do
          moveNode n ref
      if ex.val != v then
        cancelScope ex.scope
        domOp "removeChild(null)" fun d => clearForward ex.front ex.back (d.length + 1) d
        let s ← freshId
        ex := { ex with val := v, scope := s }
        renderItem (some ex.back) v s
      next := next ++ [ex]
      cursor := ex.back
    | none =>
      let f ← newNode .mark ref
      let b ← newNode .mark ref
      let s ← freshId
      renderItem (some b) v s
      next := next ++ [⟨k, v, f, b, s⟩]
      cursor := b
  putSub { sub with kind := .keyed listFront it next }

mutual
/-- `renderToInternal`'s `traverse`: render a view before `anchor` (or at the end), with `cur` as
the value `val` shows, registering subscriptions in `scope`. -/
def render (strat : Strategy) (anchor : Option Nat) (cur scope : Nat) : View → M Unit
  | .text s => do let _ ← newNode (.leaf s) anchor
  | .val => do let _ ← newNode (.leaf (toString cur)) anchor
  | .group vs => renderAll strat anchor cur scope vs
  | .comp b =>
    match strat with
    | .atAnchor => render strat anchor cur scope b
    | .appendThenMove => do
      let before ← lastId
      render strat none cur scope b
      let after ← lastId
      match after with
      | some e => if after != before then moveNode e anchor
      | none => pure ()
  | .sec x b => do
    -- createMountSection: `end` (front), then `start` (back), both at the anchor
    let front ← newNode .mark anchor
    let back ← newNode .mark anchor
    let id ← freshId
    let c ← freshId
    addSub ⟨id, scope, x, .sec front back b c⟩
    -- An RxVar emits its current value on subscribe
    secEmit (fun a v s => render strat a v s b) id ((← get).env.scalar x)
  | .keyed x it => do
    let lf ← newNode .mark anchor
    let _ ← newNode .mark anchor
    let id ← freshId
    addSub ⟨id, scope, x, .keyed lf it []⟩
    keyedEmit (fun a v s => render strat a v s it) id ((← get).env.list x)

def renderAll (strat : Strategy) (anchor : Option Nat) (cur scope : Nat) : List View → M Unit
  | [] => pure ()
  | v :: vs => do
    render strat anchor cur scope v
    renderAll strat anchor cur scope vs
end

/-- One update of an `RxVar`. -/
inductive Step where
  | scalar (x v : Nat)
  | list (x : Nat) (ps : List (Nat × Nat))
  deriving Repr

def Sub.isSec (s : Sub) : Bool := match s.kind with | .sec .. => true | _ => false

/-- `RxVar :=`: nothing when the value is equal; otherwise notify the subscribers of a snapshot
taken before the update, in subscription order. A section or keyed list cancelled by an earlier
subscriber in the same round ignores the update (its `cancelled` flag): its markers are gone, and
without the flag its clear walk would throw (`clearSection_unpaired`). -/
def update (strat : Strategy) : Step → M Unit
  | .scalar x v => do
    let st ← get
    if st.env.scalar x = v then return
    set { st with env := st.env.setScalar x v }
    for i in (st.subs.filter fun s => s.var = x && s.isSec).map (·.id) do
      if let some sub ← getSub i then
        if let .sec _ _ b _ := sub.kind then
          secEmit (fun a v s => render strat a v s b) i v
  | .list x ps => do
    let st ← get
    if st.env.list x = ps then return
    set { st with env := st.env.setList x ps }
    for i in (st.subs.filter fun s => s.var = x && !s.isSec).map (·.id) do
      if let some sub ← getSub i then
        if let .keyed _ it _ := sub.kind then
          keyedEmit (fun a v s => render strat a v s it) i ps

/-- The leaves in the parent, in order, with their node ids. -/
def leaves (d : List Node) : List (Nat × String) :=
  d.filterMap fun n => match n.kind with | .leaf s => some (n.id, s) | .mark => none

/-- The parent's leaves after the initial render and after each step, or the error that stopped
the run. Scope 0 is the root, which is never cancelled. -/
def runCase (strat : Strategy) (v : View) (steps : List Step) :
    Except String (List (List (Nat × String))) :=
  let go : M (List (List (Nat × String))) := do
    render strat none 0 0 v
    let mut out := [leaves (← get).dom]
    for s in steps do
      update strat s
      out := out ++ [leaves (← get).dom]
    pure out
  (go.run {}).map (·.1)

/-- The spec's text after the initial render and after each step. -/
def expected (v : View) (steps : List Step) : List (List String) :=
  let envs := steps.foldl (init := [Env.empty]) fun acc s =>
    let e := acc.getLast?.getD Env.empty
    acc ++ [match s with | .scalar x v => e.setScalar x v | .list x ps => e.setList x ps]
  envs.map fun e => denote e 0 v

/-- The renderer agrees with the spec on a case: it does not throw, and after every step the
leaf texts are the spec's. -/
def agrees (strat : Strategy) (v : View) (steps : List Step) : Bool :=
  match runCase strat v steps with
  | .ok states => states.map (·.map (·.2)) == expected v steps
  | .error _ => false

/-! ## Theorems -/

/-- Inserting before an anchor puts the node right before it and keeps every other node in
order. -/
theorem insertAt_before (n m : Node) (xs ys : List Node) (h : m.id ∉ ids xs) :
    insertAt n m.id (xs ++ m :: ys) = some (xs ++ n :: m :: ys) := by
  induction xs with
  | nil => simp [insertAt]
  | cons x xs ih =>
    simp only [ids, List.map_cons, List.mem_cons, not_or] at h
    have hx : x.id ≠ m.id := fun e => h.1 e.symm
    simp [insertAt, hx, ih h.2]

theorem prevOf_append (b : Nat) (p : Node) (bn : Node) (xs ys : List Node)
    (hb : bn.id = b) (hxs : b ∉ ids xs) (hp : p.id ≠ b) :
    prevOf b (xs ++ p :: bn :: ys) = some p.id := by
  induction xs with
  | nil => simp [prevOf, hb]
  | cons x xs ih =>
    simp only [ids, List.map_cons, List.mem_cons, not_or] at hxs
    cases xs with
    | nil => simp [prevOf, hp, hb]
    | cons y xs =>
      simp only [ids, List.map_cons, List.mem_cons, not_or] at hxs ih
      have hy : y.id ≠ b := fun e => hxs.2.1 e.symm
      simp only [List.cons_append, prevOf, hy, ite_false]
      exact ih ⟨hxs.2.1, hxs.2.2⟩

theorem removeNode_not_mem (i : Nat) (d : List Node) (h : i ∉ ids d) : removeNode i d = d := by
  induction d with
  | nil => rfl
  | cons x xs ih =>
    simp only [ids, List.map_cons, List.mem_cons, not_or] at h
    have hx : (x.id != i) = true := by simp; exact fun e => h.1 e.symm
    simp only [removeNode, List.filter_cons, hx, ite_true]
    exact congrArg (x :: ·) (ih h.2)

theorem removeNode_mid (p : Node) (xs ys : List Node) (hxs : p.id ∉ ids xs) (hys : p.id ∉ ids ys) :
    removeNode p.id (xs ++ p :: ys) = xs ++ ys := by
  have e1 := removeNode_not_mem p.id xs hxs
  have e2 := removeNode_not_mem p.id ys hys
  simp only [removeNode] at e1 e2 ⊢
  simp [List.filter_append, e1, e2]

theorem not_mem_left {l r : List Nat} {a : Nat} (h : (l ++ r).Nodup) (ha : a ∈ r) : a ∉ l :=
  fun hl => (List.nodup_append.mp h).2.2 a hl a ha rfl

theorem ids_append (xs ys : List Node) : ids (xs ++ ys) = ids xs ++ ids ys := List.map_append ..

theorem ids_cons (x : Node) (xs : List Node) : ids (x :: xs) = x.id :: ids xs := rfl

/-- With its markers paired (front before back, ids unique), clearing a section removes exactly
the nodes between them and leaves every other node in place. `rs` is the content, last node
first, the order the walk removes it in. -/
theorem clearSection_paired (fr bk : Node) (xs rs ys : List Node) (fuel : Nat)
    (hfuel : rs.length < fuel)
    (hnd : (ids (xs ++ fr :: (rs.reverse ++ bk :: ys))).Nodup) :
    clearSection fr.id bk.id fuel (xs ++ fr :: (rs.reverse ++ bk :: ys)) =
      some (xs ++ fr :: bk :: ys) := by
  induction rs generalizing fuel with
  | nil =>
    obtain ⟨fuel, rfl⟩ : ∃ f, fuel = f + 1 := ⟨fuel - 1, by simp at hfuel; omega⟩
    simp only [List.reverse_nil, List.nil_append] at hnd ⊢
    simp only [ids_append, ids_cons] at hnd
    have hb : bk.id ∉ ids xs := not_mem_left hnd (by simp)
    have hfb : fr.id ≠ bk.id := by
      have := (List.nodup_append.mp hnd).2.1
      simp at this; exact this.1.1
    simp [clearSection, prevOf_append bk.id fr bk xs ys rfl hb hfb]
  | cons p rs ih =>
    obtain ⟨fuel, rfl⟩ : ∃ f, fuel = f + 1 := ⟨fuel - 1, by simp at hfuel; omega⟩
    have hl : xs ++ fr :: ((p :: rs).reverse ++ bk :: ys) =
        (xs ++ fr :: rs.reverse) ++ p :: bk :: ys := by simp
    rw [hl] at hnd ⊢
    have hnd' := hnd
    simp only [ids_append, ids_cons] at hnd'
    have hb : bk.id ∉ ids (xs ++ fr :: rs.reverse) := by
      rw [ids_append, ids_cons]; exact not_mem_left hnd' (by simp)
    have hpb : p.id ≠ bk.id := by
      have := (List.nodup_append.mp hnd').2.1
      simp at this; exact this.1.1
    have hpl : p.id ∉ ids (xs ++ fr :: rs.reverse) := by
      rw [ids_append, ids_cons]; exact not_mem_left hnd' (by simp)
    have hpr : p.id ∉ ids (bk :: ys) := by
      have := (List.nodup_append.mp hnd').2.1
      simp at this
      rw [ids_cons]; simp only [List.mem_cons, not_or]; exact ⟨this.1.1, this.1.2⟩
    have hpf : p.id ≠ fr.id := by
      intro e; apply hpl; rw [ids_append, ids_cons, e]; simp
    simp only [clearSection, prevOf_append bk.id p bk _ ys rfl hb hpb, hpf, ite_false]
    rw [removeNode_mid p _ _ hpl hpr]
    have := ih fuel (by simp at hfuel; omega) (by
      rw [show xs ++ fr :: (rs.reverse ++ bk :: ys) = (xs ++ fr :: rs.reverse) ++ bk :: ys by simp]
      rw [ids_append] at hnd ⊢
      exact List.Nodup.sublist ((List.Sublist.refl _).append (by simp [ids])) hnd)
    simpa using this

/-- When the front marker is not in the parent, the clear walk never stops at it: it removes
nodes until it reaches the first child and throws, as the nested-section crash did before the
fix. -/
theorem clearSection_unpaired (fr bk fuel : Nat) (d : List Node) (h : fr ∉ ids d) :
    clearSection fr bk fuel d = none := by
  induction fuel generalizing d with
  | zero => rfl
  | succ fuel ih =>
    simp only [clearSection]
    split
    · rfl
    · rename_i p hp
      have hpd : p ∈ ids d := prevOf_mem hp
      have hne : p ≠ fr := fun e => h (e ▸ hpd)
      simp only [hne, ite_false]
      apply ih
      intro hm; apply h
      simp [ids, removeNode] at hm ⊢
      obtain ⟨a, ha, he⟩ := hm
      exact ⟨a, ha.1, he⟩

theorem dedupAux_not_mem (seen : List Nat) (ps : List (Nat × Nat)) :
    ∀ k ∈ (dedupAux seen ps).map (·.1), k ∉ seen := by
  induction ps generalizing seen with
  | nil => simp [dedupAux]
  | cons p rest ih =>
    obtain ⟨k, v⟩ := p
    simp only [dedupAux]
    split
    · exact ih seen
    · rename_i hk
      intro k' hk'
      simp only [List.map_cons, List.mem_cons] at hk'
      rcases hk' with e | hm
      · exact e ▸ hk
      · exact fun hs => ih (k :: seen) k' hm (List.mem_cons_of_mem _ hs)

theorem dedupAux_nodup (seen : List Nat) (ps : List (Nat × Nat)) :
    ((dedupAux seen ps).map (·.1)).Nodup := by
  induction ps generalizing seen with
  | nil => simp [dedupAux]
  | cons p rest ih =>
    obtain ⟨k, v⟩ := p
    simp only [dedupAux]
    split
    · exact ih seen
    · simp only [List.map_cons, List.nodup_cons]
      exact ⟨fun hm => dedupAux_not_mem (k :: seen) rest k hm (by simp), ih (k :: seen)⟩

/-- A keyed list keeps one item per key, so no two items share a pair of markers. -/
theorem dedup_keys_nodup (ps : List (Nat × Nat)) : ((dedup ps).map (·.1)).Nodup :=
  dedupAux_nodup [] ps

/-- The fix is needed: before it, a component that renders two nodes inside a section left its
first node outside the section, after the parent's later nodes. -/
theorem appendThenMove_leaks :
    agrees .appendThenMove (.group [.sec 0 (.comp (.group [.text "a", .val])), .text "tail"])
      [.scalar 0 1] = false := by decide +kernel

theorem atAnchor_places :
    agrees .atAnchor (.group [.sec 0 (.comp (.group [.text "a", .val])), .text "tail"])
      [.scalar 0 1] = true := by decide +kernel

end UniSpec.DomRender
