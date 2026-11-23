import UniSpec.HttpReader

/-!
# Request smuggling, against a family of lenient parsers

Request smuggling needs two parsers on one byte stream, say a front proxy and this server, to
disagree on where a request ends. `interp o` is a family of lenient head parsers, one per choice in
`Opts`. Each choice is a behaviour real proxies and servers have shipped:

* `lfLines`: lines end at CR LF only, or also at a bare LF (dropping a CR before it);
* `dupLast`: of several `Content-Length` fields, the last wins (else the first);
* `teWins`: with both `Content-Length` and `Transfer-Encoding`, the coding wins (else the length);
* `stripColonWs`: whitespace before the colon is dropped from the name (so `Transfer-Encoding :`
  is the coding), or kept (so it is another field);
* `unfold`: an obs-fold line continues the previous field (so a folded `Content-Length` is no
  number and a folded coding is not `chunked`), or is ignored;
* `trimValue`: trailing whitespace is trimmed from values, or kept.

Every member skips CR and LF before the request line (RFC 9112 §2.2), skips the request line,
compares names ASCII case-insensitively, skips the whitespace after the colon, ends the head at the
first empty line, ignores a line without a colon, and takes the body as chunked when the last
`Transfer-Encoding` field is exactly `chunked` (any case). It rejects (`none`) a head it cannot
frame. Members use their own helpers below, not the reader's (`scanLine`, `isTokenChar`, `lower`,
`trim`), so a bug in a shared helper cannot make both sides agree by accident.

The rule `GenHttpReaderCases` checks, and a later theorem is to prove: whenever the reader accepts a
request and keeps the connection open after it, every member either rejects it or finds the same
head length and the same framing. A member that rejects forwards nothing, and after a close the
reader never reads another boundary, so neither case can smuggle a request. The rule is relative to
this family; for example, a parser that compares names case-sensitively is outside it. Chunked
bodies are not covered here.
-/

namespace UniSpec.HttpSmuggle

open UniSpec.HttpReader (Bytes Framing)

structure Opts where
  lfLines : Bool
  dupLast : Bool
  teWins : Bool
  stripColonWs : Bool
  unfold : Bool
  trimValue : Bool
  deriving DecidableEq, Repr, Inhabited

/-- All 64 members of the family. -/
def allOpts : List Opts := Id.run do
  let bs := [false, true]
  let mut out := []
  for a in bs do
    for b in bs do
      for c in bs do
        for d in bs do
          for e in bs do
            for f in bs do
              out := out ++ [⟨a, b, c, d, e, f⟩]
  return out

/-- The names of the options, and how to flip each one. -/
def flips : List (String × (Opts → Opts)) := [
  ("lfLines", fun o => { o with lfLines := !o.lfLines }),
  ("dupLast", fun o => { o with dupLast := !o.dupLast }),
  ("teWins", fun o => { o with teWins := !o.teWins }),
  ("stripColonWs", fun o => { o with stripColonWs := !o.stripColonWs }),
  ("unfold", fun o => { o with unfold := !o.unfold }),
  ("trimValue", fun o => { o with trimValue := !o.trimValue })
]

def isWs (c : Char) : Bool := c == ' ' || c == '\t'

def lowerAscii (c : Char) : Char :=
  if 'A' ≤ c && c ≤ 'Z' then Char.ofNat (c.toNat + 32) else c

/-- `n` is the name `s`, ignoring ASCII case. -/
def named (n : Bytes) (s : String) : Bool := n.map lowerAscii == s.toList

def dropTrailingWs (s : Bytes) : Bytes := (s.reverse.dropWhile isWs).reverse

/-- One line: its content and the bytes after its end. `none` when no line end arrived. -/
def nextLine (lf : Bool) : Bytes → Option (Bytes × Bytes)
  | [] => none
  | '\r' :: '\n' :: t => some ([], t)
  | c :: t =>
    if lf && c == '\n' then some ([], t)
    else (nextLine lf t).map fun (l, r) => (c :: l, r)

/-- The lines up to the empty line, and the bytes after it. -/
def headLines (lf : Bool) : Nat → Bytes → Option (List Bytes × Bytes)
  | 0, _ => none
  | fuel + 1, s =>
    match nextLine lf s with
    | none => none
    | some ([], r) => some ([], r)
    | some (l, r) => (headLines lf fuel r).map fun (ls, r') => (l :: ls, r')

/-- Decimal digits, without a limit. `none` for anything else, or nothing. -/
def decimal (v : Bytes) : Option Nat :=
  if v.isEmpty || !v.all (fun c => '0' ≤ c && c ≤ '9') then none
  else some (v.foldl (fun acc c => acc * 10 + (c.toNat - 48)) 0)

/-- The fields a member keeps, newest first. -/
def addLine (o : Opts) (acc : List (Bytes × Bytes)) (line : Bytes) : List (Bytes × Bytes) :=
  let value (v : Bytes) : Bytes :=
    let v := v.dropWhile isWs
    if o.trimValue then dropTrailingWs v else v
  match line with
  | [] => acc
  | c :: rest =>
    if isWs c then
      if o.unfold then
        match acc with
        | (n, v) :: acc' => (n, v ++ [' '] ++ value rest) :: acc'
        | [] => acc
      else acc
    else
      match line.dropWhile (· ≠ ':') with
      | [] => acc
      | _ :: v =>
        let n := line.takeWhile (· ≠ ':')
        let n := if o.stripColonWs then dropTrailingWs n else n
        (n, value v) :: acc

/-- Member `o`: the length of the head at the front of `s0` (with the CR and LF before it) and the
body's framing, or `none` if it rejects. -/
def interp (o : Opts) (s0 : Bytes) : Option (Nat × Framing) := do
  let s := s0.dropWhile (fun c => c == '\r' || c == '\n')
  let (lines, rest) ← headLines o.lfLines (s.length + 1) s
  let fields := ((lines.drop 1).foldl (addLine o) []).reverse
  let te := (fields.filter (named ·.1 "transfer-encoding")).map (·.2)
  let cl := (fields.filter (named ·.1 "content-length")).map (·.2)
  let chunked := te.getLast?.map (named · "chunked") == some true
  let length : Option Nat :=
    match (if o.dupLast then cl.getLast? else cl.head?) with
    | none => some 0
    | some v => decimal v
  let fr ← match te.isEmpty, cl.isEmpty with
    | true, _ => length.map .length
    | false, true => if chunked then some .chunked else none
    | false, false =>
      if o.teWins then (if chunked then some .chunked else none) else length.map .length
  pure (s0.length - rest.length, fr)

/-- Where the reader puts the end of the head at the front of `s` and how it frames the body, when
it accepts the request and keeps the connection open after it. -/
def readerFraming (maxSize : Nat) (s : Bytes) : Option (Nat × Framing) :=
  match HttpReader.readHead s with
  | .fail _ => none
  | .ok hd =>
    match HttpReader.readOne maxSize s, HttpReader.frame maxSize hd.http10 hd.headers with
    | (.req _ _ true _, _), .ok fr => some (s.length - hd.after.length, fr)
    | _, _ => none

/-- Member `o` agrees with the reader on `s`: it rejects, or finds the same head and framing. -/
def agrees (r : Nat × Framing) (o : Opts) (s : Bytes) : Bool :=
  match interp o s with
  | none => true
  | some m => m == r

end UniSpec.HttpSmuggle
