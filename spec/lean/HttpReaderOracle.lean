import UniSpec.HttpReader

/-!
The request-framing model as a fuzz oracle. `HttpReaderFuzzTest` starts it once and sends every
input it generates, so the Scala reader is checked against `readAll` in `UniSpec/HttpReader.lean`
on inputs no generated case holds.

Usage: `lake exe http-reader-oracle <maxLine> <maxHeader> <maxBody>`

Each stdin line is one input, hex-encoded (one byte per character, so bytes 0 to 255). For each one
it prints one line: the outcomes of `readAll` as `Outcome.text`, each hex-encoded the same way and
separated by a space. It exits when stdin closes. A line that is not hex gets `error <message>`.
-/

open UniSpec.HttpReader

def hexDigit (c : Char) : Option Nat :=
  if '0' ≤ c && c ≤ '9' then some (c.toNat - '0'.toNat)
  else if 'a' ≤ c && c ≤ 'f' then some (c.toNat - 'a'.toNat + 10)
  else if 'A' ≤ c && c ≤ 'F' then some (c.toNat - 'A'.toNat + 10)
  else none

def unhex : List Char → Option (List Char)
  | [] => some []
  | h :: l :: rest => do
    let b := (← hexDigit h) * 16 + (← hexDigit l)
    return Char.ofNat b :: (← unhex rest)
  | [_] => none

def hexChars : Array Char := "0123456789abcdef".toList.toArray

/-- Hex of each character's code, which `Outcome.text` keeps below 256 for the reader's bytes;
the model's messages are ASCII. -/
def hex (s : String) : String :=
  String.ofList <| s.toList.flatMap fun c =>
    let n := c.toNat % 256
    [hexChars[n / 16]!, hexChars[n % 16]!]

partial def loop (lim : Limits) (stdin : IO.FS.Stream) (stdout : IO.FS.Stream) : IO Unit := do
  let line ← stdin.getLine
  if line.isEmpty then return
  let answer :=
    match unhex (line.toList.filter (· != '\n')) with
    | some input => " ".intercalate ((readAll lim input).map (hex ∘ Outcome.text))
    | none => "error input is not hex"
  stdout.putStrLn answer
  stdout.flush
  loop lim stdin stdout

def main (args : List String) : IO UInt32 := do
  match args.map String.toNat? with
  | [some maxLine, some maxHeader, some maxBody] =>
    loop { maxLine, maxHeader, maxBody } (← IO.getStdin) (← IO.getStdout)
    return 0
  | _ =>
    IO.eprintln "Usage: http-reader-oracle <maxLine> <maxHeader> <maxBody>"
    return 2
