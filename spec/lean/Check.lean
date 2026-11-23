import Lean
import UniSpec

/-!
# The proof gate

Building this module fails unless every declaration in a `UniSpec.*` module depends only on Lean's
standard axioms: `propext`, `Classical.choice`, and `Quot.sound`. A `sorry` (including one Lean
inserts after an elaboration error) shows up as `sorryAx`, `native_decide` as the axiom it
generates (`..._native.native_decide.ax_...`, which trusts compiled code), and an `axiom`
declaration as itself. `lake build` only warns about a `sorry`, so without this gate an unfinished
proof would pass CI.

It is a default target, so `lake build` runs it.
-/

open Lean Elab Command

namespace UniSpec.Check

def standardAxioms : List Name := [``propext, ``Classical.choice, ``Quot.sound]

/-- The modules the gate covers: `UniSpec` and everything under it. -/
def covered (m : Name) : Bool := (`UniSpec).isPrefixOf m

elab "#check_uni_spec_axioms" : command => do
  let env ← getEnv
  let mut checked := 0
  let mut failures : Array String := #[]
  for (name, info) in env.constants.map₁.toList do
    let some idx := env.getModuleIdxFor? name | continue
    let some m := env.header.moduleNames[idx.toNat]? | continue
    if !covered m then continue
    if info matches .axiomInfo _ then
      failures := failures.push s!"{name} (in {m}) is an axiom declaration"
      continue
    let axs ← liftCoreM (collectAxioms name)
    checked := checked + 1
    let extra := axs.filter (!standardAxioms.contains ·)
    if !extra.isEmpty then
      failures := failures.push s!"{name} (in {m}) depends on {extra.toList}"
  if checked == 0 then
    throwError "the proof gate found no declarations in UniSpec modules"
  if !failures.isEmpty then
    throwError m!"{failures.size} declarations depend on non-standard axioms:\n{"\n".intercalate failures.toList}"
  logInfo m!"proof gate: {checked} declarations use only {standardAxioms}"

#check_uni_spec_axioms

end UniSpec.Check
