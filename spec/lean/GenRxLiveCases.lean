import UniSpec.RxLive

/-!
Writes `RxLiveSpecCases.scala`: what the sink records for short scripts of var updates, evaluated
from the model in `UniSpec/RxLive.lean`. `RxLiveSpecTest` replays the cases through `RxRunner`.

Usage: `lake exe gen-rx-live-cases [output path]`
-/

open UniSpec.RxLive

def renderOut : Out → String
  | .next v => toString v
  | .pair a b => s!"({a},{b})"
  | .done => "C"
  | .fail => "E"
  | .keys ks => "[" ++ " ".intercalate (ks.map toString) ++ "]"
  | .row k v => s!"{k}={v}"
  | .rowDone k => s!"{k}:C"

def renderOuts : List Out → String
  | [] => "-"
  | os => " ".intercalate (os.map renderOut)

def renderAction : Action → String
  | .set i v => s!"set {i} {v}"
  | .stop i => s!"stop {i}"
  | .fail i => s!"fail {i}"
  | .cancel => "cancel"

def renderItems (l : Items) : String :=
  "[" ++ " ".intercalate (l.map fun (k, v) => s!"{k}={v}") ++ "]"

def renderListAction : ListAction → String
  | .set l => s!"set {renderItems l}"
  | .stop => "stop"
  | .fail => "fail"
  | .cancel => "cancel"

def renderScript {α : Type} (r : α → String) : List α → String
  | [] => "-"
  | as => ", ".intercalate (as.map r)

/-- Every script of at most `n` actions from `alphabet`. -/
def scripts {α : Type} (alphabet : List α) : Nat → List (List α)
  | 0 => [[]]
  | n + 1 => [] :: alphabet.flatMap fun a => (scripts alphabet n).map (a :: ·)

/-- The var an action stops or fails, if any. -/
def ends : Action → Option Nat
  | .stop i | .fail i => some i
  | _ => none

def target? : Action → Option Nat
  | .set i _ | .stop i | .fail i => some i
  | .cancel => none

/-- No action reaches a var after it stopped or failed, as `RxVar`'s contract requires. -/
def valid (as : List Action) : Bool :=
  let rec go (ended : List Nat) : List Action → Bool
    | [] => true
    | a :: rest =>
      match target? a with
      | some i => !ended.contains i && go ((ends a).toList ++ ended) rest
      | none => go ended rest
  go [] as

def validList : List ListAction → Bool
  | [] => true
  | .stop :: rest | .fail :: rest => rest.all (· == .cancel)
  | _ :: rest => validList rest

def dedup {α : Type} [BEq α] (l : List α) : List α :=
  l.foldl (fun acc x => if acc.contains x then acc else acc ++ [x]) []

def line (op script out : String) : String := s!"    \"{op} | {script} | {out}\""

def switchCases : List String :=
  let alphabet := [Action.set 0 0, .set 0 1, .set 0 2, .set 1 11, .set 2 21, .stop 0, .stop 1,
    .stop 2, .fail 0, .fail 1, .fail 2, .cancel]
  -- Longer scripts: switching back and forth, and completion in either order after a switch
  let longer := [
    [Action.set 0 2, .set 1 11, .set 0 1, .set 1 12],
    [.set 0 2, .stop 0, .set 2 21, .stop 2],
    [.set 0 2, .stop 2, .set 0 1, .stop 0, .stop 1],
    [.set 0 0, .set 0 2, .set 2 21, .stop 0, .stop 2],
    [.set 1 11, .set 0 2, .stop 1, .set 0 1, .stop 0]]
  let ss := ((scripts alphabet 2).filter valid) ++ longer
  [SwitchKind.flatMap, .transformRx].flatMap fun k =>
    let op := match k with | .flatMap => "flatMap" | .transformRx => "transformRx"
    ss.map fun as => line op (renderScript renderAction as) (renderOuts (runSwitch k as))

def splitCases : List String :=
  let lists : List Items := [
    [(1, 10), (2, 20)],              -- the current list: the var does not emit
    [(1, 11), (2, 20)],              -- a kept row changes
    [(2, 20), (1, 10)],              -- reordered keys
    [(1, 10)],                       -- a row is removed
    [(1, 10), (3, 30), (1, 99)],     -- a row is added; the repeated key keeps its first item
    [(1, 10), (2, 20), (1, 15)],     -- a different list with the same first items
    []]
  let alphabet := lists.map ListAction.set ++ [.stop, .fail, .cancel]
  let longer := [
    [ListAction.set [(1, 10)], .set [(1, 10), (2, 22)]],
    [.set [(1, 11), (2, 20)], .set [(1, 12), (2, 21)], .stop]]
  let ss := ((scripts alphabet 2).filter validList) ++ longer
  ss.map fun as => line "split" (renderScript renderListAction as) (renderOuts (runSplit as))

def combineCases : List String :=
  let alphabet := [Action.set 0 2, .set 0 3, .set 1 20, .set 1 30, .stop 0, .stop 1, .fail 0,
    .fail 1, .cancel]
  let small := [Action.set 0 2, .set 0 3, .set 1 20, .stop 0, .stop 1]
  let ss := dedup (((scripts alphabet 2) ++ (scripts small 3)).filter valid)
  [CombineKind.zip, .join].flatMap fun k =>
    let op := match k with | .zip => "zip" | .join => "join"
    ss.map fun as => line op (renderScript renderAction as) (renderOuts (runCombine k as))

def takeCases : List String :=
  let alphabet := [Action.set 0 2, .set 0 3, .stop 0, .fail 0, .cancel]
  let ss := (scripts alphabet 2).filter valid
  [0, 1, 2, 3].flatMap fun n =>
    [Sink.run, .once].flatMap fun sink =>
      let op := s!"take:{n}:" ++ match sink with | .run => "run" | .once => "once"
      ss.map fun as => line op (renderScript renderAction as) (renderOuts (runTake n sink as))

def caseLines : List String := switchCases ++ splitCases ++ combineCases ++ takeCases

-- Formatted as scalafmt would, so the Code format check passes on the generated file
def scalaSource : String :=
  "\n".intercalate ([
    "// Generated by `lake exe gen-rx-live-cases` in spec/lean from UniSpec/RxLive.lean. Do not edit.",
    "package wvlet.uni.rx",
    "",
    "object RxLiveSpecCases:",
    "  // Each case: operator | script of actions separated by , | what the sink records.",
    "  // Records: an Int or (a,b) for OnNext, C for OnCompletion, E for OnError, [keys] for a split",
    "  // list, k=v for a row value, k:C for a row completion; - for none.",
    "  val cases: Seq[String] = Seq("
  ] ++ [",\n".intercalate caseLines, "  )", "", "end RxLiveSpecCases", ""])

def defaultOutput : System.FilePath :=
  "../../uni/src/test/scala/wvlet/uni/rx/RxLiveSpecCases.scala"

def main (args : List String) : IO Unit := do
  let out := args.head?.map System.FilePath.mk |>.getD defaultOutput
  IO.FS.writeFile out scalaSource
  IO.println s!"Wrote {caseLines.length} cases to {out}"
