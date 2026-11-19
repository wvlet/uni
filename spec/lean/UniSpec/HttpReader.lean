/-!
# A model of HTTP/1.1 request framing on a keep-alive connection

`HttpConnectionReader` (`uni/.jvm-native/.../SocketHttpProtocol.scala`) reads requests one after
another from the bytes of one connection, for the built-in JVM server and the Scala Native server.
This file models it as a function from everything a client sends to the outcomes the reader
reports: a request, a 400 with its message, or a clean close.

Bytes are `Char`s, read as Latin-1. The model leaves out two things:
- The header-size limit. The reader checks it while bytes arrive, so where it trips depends on how
  the bytes are split into reads. The cases keep header blocks far below the limit.
- Request-target decoding. The cases use ASCII targets, which decode to themselves.

`GenHttpReaderCases.lean` evaluates `readAll` to generate the cases that `HttpReaderSpecTest`
replays, feeding each input to the Scala reader in reads of several sizes. The theorems state the
framing rules: a request consumes exactly its header block, the terminator, and `Content-Length`
body bytes, so the next request starts right after it; conflicting `Content-Length` headers and
chunked bodies are rejected (both would let a proxy and the server disagree on where a request
ends, which is request smuggling); a body never exceeds the size limit.
-/

namespace UniSpec.HttpReader

abbrev Bytes := List Char

/-- What the reader reports for one request. -/
inductive Outcome where
  | req (method : String) (target : String) (body : Bytes)
  | bad (message : String)
  | closed
  deriving Repr, DecidableEq, Inhabited

def Outcome.isReq : Outcome → Bool
  | .req .. => true
  | _ => false

/-- The end of the header block. -/
def terminator : Bytes := ['\r', '\n', '\r', '\n']

/-- Split at the first `\r\n\r\n`: the header block and the bytes after the terminator. -/
def splitHead : Bytes → Option (Bytes × Bytes)
  | [] => none
  | c :: rest =>
    if terminator.isPrefixOf (c :: rest) then some ([], (c :: rest).drop 4)
    else (splitHead rest).map fun p => (c :: p.1, p.2)

/-- Split the header block into lines at each `\r\n`. -/
def splitLines : Bytes → List Bytes
  | [] => [[]]
  | '\r' :: '\n' :: t => [] :: splitLines t
  | c :: t =>
    match splitLines t with
    | l :: ls => (c :: l) :: ls
    | [] => [[c]]

def isHttpWhitespace (c : Char) : Bool := c == '\r' || c == '\n' || c == ' ' || c == '\t'

def isBlank (c : Char) : Bool := c == ' ' || c == '\t'

/-- Drop leading and trailing spaces and tabs. -/
def trim (s : Bytes) : Bytes := ((s.dropWhile isBlank).reverse.dropWhile isBlank).reverse

/-- `METHOD SP target [SP ...]`: the method and the target, or none when malformed. -/
def parseRequestLine (line : Bytes) : Option (Bytes × Bytes) :=
  match line.dropWhile (· ≠ ' ') with
  | [] => none
  | _ :: after =>
    if after.all (· == ' ') then none
    else some (line.takeWhile (· ≠ ' '), after.takeWhile (· ≠ ' '))

/-- `name: value`, both trimmed. A line without a colon, or starting with one, is skipped. -/
def parseHeader (line : Bytes) : Option (Bytes × Bytes) :=
  match line.dropWhile (· ≠ ':') with
  | [] => none
  | _ :: value =>
    let name := line.takeWhile (· ≠ ':')
    if name.isEmpty then none else some (trim name, trim value)

def lower (s : Bytes) : Bytes := s.map Char.toLower

/-- Every value of a header, in order. Names compare case-insensitively. -/
def values (headers : List (Bytes × Bytes)) (name : String) : List Bytes :=
  (headers.filter fun h => lower h.1 == name.toList).map (·.2)

/-- Whether `p` occurs in `s`. -/
def hasInfix (p : Bytes) : Bytes → Bool
  | [] => p.isEmpty
  | c :: t => p.isPrefixOf (c :: t) || hasInfix p t

/-- Scala's `distinct`: the first of each run of equal values, in order. -/
def distinct : List Bytes → List Bytes
  | [] => []
  | a :: l => a :: (distinct l).filter (· ≠ a)

/-- Scala's `String.toIntOption`: an optional sign, then decimal digits, within `Int`'s range. -/
def parseInt (s : Bytes) : Option Int :=
  let (neg, digits) : Bool × Bytes :=
    match s with
    | '-' :: d => (true, d)
    | '+' :: d => (false, d)
    | d => (false, d)
  if digits.isEmpty || !digits.all Char.isDigit then none
  else
    let v : Nat := digits.foldl (fun acc c => acc * 10 + (c.toNat - '0'.toNat)) 0
    let i : Int := if neg then -(v : Int) else v
    if -2147483648 ≤ i ∧ i ≤ 2147483647 then some i else none

/-- Whether the first `Transfer-Encoding` value names `chunked`. -/
def isChunked (headers : List (Bytes × Bytes)) : Bool :=
  match values headers "transfer-encoding" with
  | v :: _ => hasInfix "chunked".toList (lower v)
  | [] => false

/-- The single `Content-Length` value as a body length, or the 400 message. -/
def contentLength (maxSize : Nat) : List Bytes → Except String Nat
  | [] => .ok 0
  | [v] =>
    match parseInt v with
    | some n => if 0 ≤ n ∧ n ≤ maxSize then .ok n.toNat else .error "Invalid Content-Length"
    | none => .error "Invalid Content-Length"
  | _ :: _ :: _ => .error "Conflicting Content-Length headers"

/-- The body length the headers declare, or the 400 message when they declare none safely. -/
def frame (maxSize : Nat) (headers : List (Bytes × Bytes)) : Except String Nat :=
  if isChunked headers then .error "Chunked request bodies are not supported"
  else contentLength maxSize (distinct (values headers "content-length"))

def methods : List String :=
  ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT"]

/-- `HttpMethod.of`: method names compare case-insensitively. -/
def methodOf (name : Bytes) : Option String :=
  methods.find? fun m => m.toList.map Char.toLower == lower name

/-- Read one request from the front of `s`: its outcome and the bytes left for the next one. -/
def readOne (maxSize : Nat) (s : Bytes) : Outcome × Bytes :=
  match splitHead s with
  | none => (if s.all isHttpWhitespace then .closed else .bad "Incomplete request", [])
  | some (block, after) =>
    match (splitLines block).dropWhile List.isEmpty with
    | [] => (.bad "Empty request line", after)
    | reqLine :: headerLines =>
      match parseRequestLine reqLine with
      | none => (.bad "Malformed request line", after)
      | some (method, target) =>
        match frame maxSize (headerLines.filterMap parseHeader) with
        | .error msg => (.bad msg, after)
        | .ok n =>
          if after.length < n then (.bad "Incomplete request body", [])
          else
            match methodOf method with
            | some m => (.req m (String.ofList target) (after.take n), after.drop n)
            | none =>
              (.bad s!"Unsupported HTTP method: {String.ofList method}", after.drop n)

/-- Read requests until one isn't: a 400 or the close ends the connection. -/
def readAllAux (maxSize : Nat) : Nat → Bytes → List Outcome
  | 0, _ => []
  | fuel + 1, s =>
    match readOne maxSize s with
    | (.req m t b, rest) => .req m t b :: readAllAux maxSize fuel rest
    | (o, _) => [o]

/-- Every request takes at least the 4 terminator bytes, so `s.length + 1` reads are enough. -/
def readAll (maxSize : Nat) (s : Bytes) : List Outcome := readAllAux maxSize (s.length + 1) s

/-! ## Theorems -/

/-- The header block and the rest are exactly the bytes around the first terminator. -/
theorem splitHead_spec : ∀ {s h t : Bytes}, splitHead s = some (h, t) → s = h ++ terminator ++ t
  | [], _, _, hs => by simp [splitHead] at hs
  | c :: rest, h, t, hs => by
    unfold splitHead at hs
    split at hs
    · rename_i hp
      simp only [Option.some.injEq, Prod.mk.injEq] at hs
      obtain ⟨rfl, rfl⟩ := hs
      have := (List.prefix_iff_eq_append).1 ((List.isPrefixOf_iff_prefix).1 hp)
      simpa [terminator] using this.symm
    · obtain ⟨⟨h', t'⟩, hp, he⟩ := Option.map_eq_some_iff.1 hs
      simp only [Prod.mk.injEq] at he
      obtain ⟨rfl, rfl⟩ := he
      simp [splitHead_spec hp]

/-- A request consumes its header block, the terminator, and exactly its body. -/
theorem readOne_req_consumes {maxSize : Nat} {s rest body : Bytes} {m t : String}
    (h : readOne maxSize s = (.req m t body, rest)) :
    ∃ block, s = block ++ terminator ++ body ++ rest := by
  unfold readOne at h
  split at h
  · split at h <;> simp at h
  · rename_i block after hs
    refine ⟨block, ?_⟩
    rw [splitHead_spec hs]
    repeat' split at h
    all_goals simp only [Prod.mk.injEq, Outcome.req.injEq, reduceCtorEq, false_and] at h
    obtain ⟨⟨-, -, rfl⟩, rfl⟩ := h
    simp

/-- Each request shortens the input by at least the terminator, so `readAll` needs no more reads. -/
theorem readOne_req_shorter {maxSize : Nat} {s rest body : Bytes} {m t : String}
    (h : readOne maxSize s = (.req m t body, rest)) : rest.length + 4 ≤ s.length := by
  obtain ⟨block, rfl⟩ := readOne_req_consumes h
  simp [terminator]
  omega

theorem mem_distinct {x : Bytes} : ∀ {l : List Bytes}, x ∈ distinct l ↔ x ∈ l
  | [] => by simp [distinct]
  | a :: l => by
    simp only [distinct, List.mem_cons, List.mem_filter, mem_distinct]
    by_cases hx : x = a <;> simp [hx]

/-- Two different `Content-Length` values are rejected, unless the body is chunked (also rejected). -/
theorem conflicting_content_length {maxSize : Nat} {headers : List (Bytes × Bytes)} {a b : Bytes}
    (ha : a ∈ values headers "content-length") (hb : b ∈ values headers "content-length")
    (hab : a ≠ b) : ∃ msg, frame maxSize headers = .error msg := by
  have ha' := mem_distinct.2 ha
  have hb' := mem_distinct.2 hb
  unfold frame
  cases hc : isChunked headers
  · simp only [Bool.false_eq_true, ite_false]
    generalize distinct (values headers "content-length") = l at ha' hb'
    match l, ha', hb' with
    | [_], ha', hb' =>
      simp only [List.mem_singleton] at ha' hb'
      exact absurd (ha'.trans hb'.symm) hab
    | _ :: _ :: _, _, _ => exact ⟨_, rfl⟩
  · exact ⟨_, rfl⟩

/-- A chunked `Transfer-Encoding` is always rejected. -/
theorem chunked_rejected {maxSize : Nat} {headers : List (Bytes × Bytes)}
    (hc : isChunked headers = true) :
    frame maxSize headers = .error "Chunked request bodies are not supported" := by
  simp [frame, hc]

/-- A body the reader accepts is never larger than the limit. -/
theorem frame_le {maxSize n : Nat} {headers : List (Bytes × Bytes)}
    (h : frame maxSize headers = .ok n) : n ≤ maxSize := by
  unfold frame at h
  cases hc : isChunked headers <;> simp only [hc, Bool.false_eq_true, ite_false, ite_true,
    reduceCtorEq] at h
  generalize distinct (values headers "content-length") = l at h
  match l, h with
  | [], h => simp [contentLength] at h; omega
  | [v], h =>
    simp only [contentLength] at h
    split at h
    · split at h
      · simp only [Except.ok.injEq] at h; omega
      · simp at h
    · simp at h
  | _ :: _ :: _, h => simp [contentLength] at h

/-- Only the last outcome can be other than a request: a 400 or a close ends the connection. -/
theorem readAllAux_only_last {maxSize : Nat} :
    ∀ {fuel : Nat} {s : Bytes} {pre : List Outcome} {o : Outcome} {post : List Outcome},
      readAllAux maxSize fuel s = pre ++ o :: post → post ≠ [] → o.isReq = true
  | 0, _, pre, o, post, h, _ => by simp [readAllAux] at h
  | fuel + 1, s, pre, o, post, h, hp => by
    unfold readAllAux at h
    split at h
    · cases pre with
      | nil =>
        simp only [List.nil_append, List.cons.injEq] at h
        rw [← h.1]
        rfl
      | cons p pre =>
        simp only [List.cons_append, List.cons.injEq] at h
        exact readAllAux_only_last h.2 hp
    · cases pre with
      | nil => simp at h; exact absurd h.2 hp
      | cons p pre => simp at h

end UniSpec.HttpReader
