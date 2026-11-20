import UniSpec.DomRender

/-!
Writes `DomRenderSpecCases.scala`: views of every shape `UniSpec/DomRender.lean` models, nested up
to depth 3, once with a variable per section and keyed list and once with shared ones, each with a script of variable updates and the leaves (node id and text) the renderer
shows after every step. The generator fails when the model's renderer disagrees with the spec on
any case. `DomRenderSpecTest` replays the cases in Chromium and checks the text and which leaves
are the same DOM node as before.

Usage: `lake exe gen-dom-render-cases [output path]`
-/

open UniSpec.DomRender

/-- A view shape; `number` gives each section and keyed list its own variable. -/
inductive Shape where
  | text
  | val
  | group (a b : Shape)
  | comp (s : Shape)
  | sec (s : Shape)
  | keyed (s : Shape)
  deriving Inhabited

def Shape.depth : Shape → Nat
  | .text | .val => 0
  | .group a b => max a.depth b.depth + 1
  | .comp s | .sec s | .keyed s => s.depth + 1

def Shape.hasComp : Shape → Bool
  | .text | .val => false
  | .group a b => a.hasComp || b.hasComp
  | .comp _ => true
  | .sec s | .keyed s => s.hasComp

/-- Number the variables in order of appearance: (view, scalar count, list count). -/
def number : Shape → Nat → Nat → View × Nat × Nat
  | .text, s, l => (.text "a", s, l)
  | .val, s, l => (.val, s, l)
  | .group a b, s, l =>
    let (va, s, l) := number a s l
    let (vb, s, l) := number b s l
    (.group [va, vb], s, l)
  | .comp x, s, l => let (v, s, l) := number x s l; (.comp v, s, l)
  | .sec x, s, l => let (v, s', l) := number x (s + 1) l; (.sec s v, s', l)
  | .keyed x, s, l => let (v, s, l') := number x s (l + 1); (.keyed l v, s, l')

/-- Number every section as scalar 0 and every keyed list as list 0, so a section can sit inside
another on the same variable: one update then re-renders the outer one and reaches the inner one
after it was cancelled. -/
def shareVars : Shape → View
  | .text => .text "a"
  | .val => .val
  | .group a b => .group [shareVars a, shareVars b]
  | .comp x => .comp (shareVars x)
  | .sec x => .sec 0 (shareVars x)
  | .keyed x => .keyed 0 (shareVars x)

/-- Sections and keyed lists in a shape. -/
def Shape.vars : Shape → Nat
  | .text | .val => 0
  | .group a b => a.vars + b.vars
  | .comp s => s.vars
  | .sec s | .keyed s => s.vars + 1

def Shape.secs : Shape → Nat
  | .text | .val => 0
  | .group a b => a.secs + b.secs
  | .comp s | .keyed s => s.secs
  | .sec s => s.secs + 1

partial def encode : View → String
  | .text s => s
  | .val => "v"
  | .group vs => "g(" ++ ",".intercalate (vs.map encode) ++ ")"
  | .comp b => "c(" ++ encode b ++ ")"
  | .sec x b => s!"s{x}(" ++ encode b ++ ")"
  | .keyed x it => s!"k{x}(" ++ encode it ++ ")"

def wrap (f : Shape → Shape) (xs : List Shape) : List Shape := xs.map f

def d0 : List Shape := [.text, .val]

def grow (xs : List Shape) : List Shape :=
  wrap .comp xs ++ wrap .sec xs ++ wrap .keyed xs ++
    xs.flatMap fun a => xs.map fun b => .group a b

def d1 : List Shape := d0 ++ grow d0

/-- Every shape up to depth 2, and the depth-3 shapes that put a component (the `RxElement` case)
inside a section or keyed list inside another. -/
def shapes : List Shape :=
  let d2 := (grow d1).filter (·.depth == 2)
  d1 ++ d2 ++ (d2.filter (·.hasComp)).flatMap fun s => [.sec s, .keyed s]

/-- Values for each round of updates: a repeated key in round 2, an empty list in round 3. -/
def listRounds : List (List (Nat × Nat)) :=
  [[(1, 1), (2, 2)], [(2, 2), (1, 3), (3, 3)], [(3, 3), (3, 4), (1, 3)], []]

def scalarRounds : List Nat := [1, 2, 0, 3]

def stepsFor (scalars lists : Nat) : List Step :=
  (listRounds.zip scalarRounds).flatMap fun (ps, v) =>
    (List.range lists).map (fun x => Step.list x ps) ++
      (List.range scalars).map (fun x => Step.scalar x v)

def encodeStep : Step → String
  | .scalar x v => s!"s{x}={v}"
  | .list x ps => s!"k{x}=" ++ ",".intercalate (ps.map fun (k, v) => s!"{k}:{v}")

def encodeState (ls : List (Nat × String)) : String :=
  " ".intercalate (ls.map fun (i, s) => s!"{i}:{s}")

/-- The root, as `DomRenderSpecTest` builds it: the view, then a `tail` leaf that shows whether
anything was placed after the parent's later nodes. -/
def root (v : View) : View := .group [v, .text "tail"]

structure Case where
  view : View
  steps : List Step

def cases : List Case :=
  shapes.map (fun sh =>
    let (v, s, l) := number sh 0 0
    ⟨v, stepsFor s l⟩) ++
  -- The same shapes with shared variables, where a section or keyed list is nested in another
  (shapes.filter (·.vars ≥ 2)).map fun sh =>
    let s := if sh.secs > 0 then 1 else 0
    let l := if sh.vars > sh.secs then 1 else 0
    ⟨shareVars sh, stepsFor s l⟩

def caseLine (c : Case) : Except String String := do
  let states ← runCase .atAnchor (root c.view) c.steps
  let steps := match c.steps with | [] => "-" | ss => "; ".intercalate (ss.map encodeStep)
  pure s!"    \"{encode c.view} | {steps} | {" / ".intercalate (states.map encodeState)}\""

def scalaSource (lines : List String) : String :=
  "\n".intercalate ([
    "// Generated by `lake exe gen-dom-render-cases` in spec/lean from UniSpec/DomRender.lean. Do not edit.",
    "package wvlet.uni.dom",
    "",
    "object DomRenderSpecCases:",
    "  // Each case: view | updates | leaves after the initial render / after each update.",
    "  // View: a = span(\"a\"), v = span(value), g(..) = group, c(x) = RxElement rendering Embedded(x),",
    "  // sN(x) = scalar N .map(v => x), kN(x) = list N .each(_._1)(p => x) with value p._2.",
    "  // Updates: sN=V sets scalar N; kN=K:V,.. sets list N. A leaf: model node id:text.",
    "  val cases: Seq[String] = Seq("
  ] ++ [",\n".intercalate lines, "  )", "", "end DomRenderSpecCases", ""])

def defaultOutput : System.FilePath :=
  "../../uni-dom-test/src/test/scala/wvlet/uni/dom/DomRenderSpecCases.scala"

def main (args : List String) : IO UInt32 := do
  let out := args.head?.map System.FilePath.mk |>.getD defaultOutput
  let bad := cases.filter fun c => !agrees .atAnchor (root c.view) c.steps
  if !bad.isEmpty then
    for c in bad do
      IO.eprintln s!"The renderer disagrees with the spec: {encode c.view}"
    return 1
  let mut lines := []
  for c in cases do
    match caseLine c with
    | .ok l => lines := lines ++ [l]
    | .error e => IO.eprintln s!"{encode c.view}: {e}"; return 1
  let old := cases.filter fun c => !agrees .appendThenMove (root c.view) c.steps
  IO.FS.writeFile out (scalaSource lines)
  IO.println s!"Wrote {lines.length} cases to {out} ({old.length} fail with the renderer before the fix)"
  return 0
