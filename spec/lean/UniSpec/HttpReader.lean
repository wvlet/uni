/-!
# A model of HTTP/1.1 request framing on a keep-alive connection

`HttpConnectionReader` (`uni/.jvm-native/.../SocketHttpProtocol.scala`) reads requests one after
another from the bytes of one connection, for the built-in JVM server and the Scala Native server.
This file models it as a function from everything a client sends to the outcomes the reader
reports: a request (and whether the connection stays open after it), a rejection with its status
code and message, or a clean close.

Bytes are `Char`s, read as Latin-1. The model leaves out three things:
- The size limits on the request line, headers, trailers, and chunk-size lines. The cases keep them
  far below the limits; `maxSize`, the body limit, is modeled.
- Timeouts and `100 Continue`, which depend on when bytes arrive rather than on which bytes.
- Request-target decoding. The cases use ASCII targets, which decode to themselves.

The reader is strict where a lenient one would let a front proxy and this server disagree on where
a request ends (request smuggling), as Netty's decoder is: anything but one SP between the words of
the request line, a bare CR or LF, obs-fold, a header name
that is not a token, control characters in values, a non-numeric or repeated `Content-Length`, and
a `Transfer-Encoding` whose only coding is not `chunked` are rejected. A request with both
`Transfer-Encoding` and `Content-Length` is framed by the first and closes the connection.

`GenHttpReaderCases.lean` evaluates `readAll` to generate the cases that `HttpReaderSpecTest`
replays, feeding each input to the Scala reader in reads of several sizes.
-/

namespace UniSpec.HttpReader

abbrev Bytes := List Char

/-- What the reader reports for one request. -/
inductive Outcome where
  | req (method : String) (target : String) (keepAlive : Bool) (body : Bytes)
  | bad (code : Nat) (message : String)
  /-- The input ended inside a request: a 400 with `message`. More bytes could change it. -/
  | incomplete (message : String)
  | closed
  deriving Repr, DecidableEq, Inhabited

def Outcome.isReq : Outcome → Bool
  | .req .. => true
  | _ => false

/-- The outcome comes from the end of the input, not from the bytes before it: more bytes could
change it. -/
def Outcome.atEof : Outcome → Bool
  | .incomplete _ | .closed => true
  | _ => false

def isHttpWhitespace (c : Char) : Bool := c == '\r' || c == '\n' || c == ' ' || c == '\t'

def isBlank (c : Char) : Bool := c == ' ' || c == '\t'

/-- Drop leading and trailing spaces and tabs. -/
def trim (s : Bytes) : Bytes := ((s.dropWhile isBlank).reverse.dropWhile isBlank).reverse

def isControl (c : Char) : Bool := c.toNat < 0x20 || c.toNat == 0x7f

/-- Control characters other than HTAB are not allowed in a field value (RFC 9110 §5.5). -/
def isInvalidValueChar (c : Char) : Bool := (c.toNat < 0x20 && c != '\t') || c.toNat == 0x7f

/-- `tchar` (RFC 9110 §5.6.2). -/
def isTokenChar (c : Char) : Bool := c.isAlphanum || "!#$%&'*+-.^_`|~".toList.contains c

def lower (s : Bytes) : Bytes := s.map Char.toLower

/-! ## Lines -/

/-- One line: its bytes without the CRLF and the bytes after it. -/
inductive Line where
  | ok (line : Bytes) (rest : Bytes)
  | bad
  | incomplete
  deriving Repr, DecidableEq

/-- Scan to the next CRLF. `cr` is whether the previous byte was a CR. A bare CR or LF is `bad`. -/
def scanLine : Bool → Bytes → Line
  | _, [] => .incomplete
  | cr, c :: t =>
    if c == '\n' then (if cr then .ok [] t else .bad)
    else if cr then .bad
    else if c == '\r' then scanLine true t
    else
      match scanLine false t with
      | .ok l r => .ok (c :: l) r
      | o => o

/-- `name: value` with the value trimmed, or the 400 message. -/
def parseField (line : Bytes) : Except String (Bytes × Bytes) :=
  match line with
  | [] => .error "Invalid header line"
  | c :: _ =>
    if isBlank c then .error "Obsolete line folding"
    else
      match line.dropWhile (· ≠ ':') with
      | [] => .error "Invalid header line"
      | _ :: v =>
        let name := line.takeWhile (· ≠ ':')
        if name.isEmpty || !name.all isTokenChar then .error "Invalid header name"
        else
          let value := trim v
          if value.any isInvalidValueChar then .error "Invalid header value"
          else .ok (name, value)

/-- Header (or trailer) lines up to the empty line. -/
inductive Fields where
  | ok (fields : List (Bytes × Bytes)) (rest : Bytes)
  | bad (message : String)
  | incomplete
  deriving Repr, DecidableEq

/-- Read fields until the empty line. Each line takes at least its CRLF, so `s.length + 1` fuel is
enough. -/
def readFields : Nat → Bytes → Fields
  | 0, _ => .incomplete
  | fuel + 1, s =>
    match scanLine false s with
    | .incomplete => .incomplete
    | .bad => .bad "Invalid line ending"
    | .ok [] rest => .ok [] rest
    | .ok line rest =>
      match parseField line with
      | .error e => .bad e
      | .ok f =>
        match readFields fuel rest with
        | .ok fs r => .ok (f :: fs) r
        | o => o

/-! ## The request line -/

/-- `METHOD SP target SP HTTP/1.x`, with exactly one SP between the words (RFC 9112 §3): the
method, the target, and whether it is HTTP/1.0. -/
def parseRequestLine (line : Bytes) : Except (Nat × String) (Bytes × Bytes × Bool) := do
  let method := line.takeWhile (· ≠ ' ')
  let r1 := line.dropWhile (· ≠ ' ')
  if method.isEmpty || r1.isEmpty then throw (400, "Malformed request line")
  let r2 := r1.drop 1
  let target := r2.takeWhile (· ≠ ' ')
  let r3 := r2.dropWhile (· ≠ ' ')
  if target.isEmpty || r3.isEmpty then throw (400, "Malformed request line")
  let version := r3.drop 1
  if version.isEmpty || version.any (· == ' ') then throw (400, "Malformed request line")
  let http10 ← match version with
    | ['H', 'T', 'T', 'P', '/', '1', '.', '1'] => pure false
    | ['H', 'T', 'T', 'P', '/', '1', '.', '0'] => pure true
    | ['H', 'T', 'T', 'P', '/', a, '.', b] =>
      if a.isDigit && b.isDigit then throw (505, "HTTP version not supported")
      else throw (400, "Invalid HTTP version")
    | _ => throw (400, "Invalid HTTP version")
  if !method.all isTokenChar then throw (400, "Invalid method")
  if target.any isControl then throw (400, "Invalid request target")
  pure (method, target, http10)

def methods : List String :=
  ["GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT"]

/-- Method names are case-sensitive (RFC 9110 §9.1). -/
def methodOf (name : Bytes) : Option String := methods.find? fun m => m.toList == name

/-! ## Framing -/

/-- Every value of a header, in order. Names compare case-insensitively. -/
def values (headers : List (Bytes × Bytes)) (name : String) : List Bytes :=
  (headers.filter fun h => lower h.1 == name.toList).map (·.2)

/-- Java's `split(",", -1)`. -/
def splitComma : Bytes → List Bytes
  | [] => [[]]
  | ',' :: t => [] :: splitComma t
  | c :: t =>
    match splitComma t with
    | l :: ls => (c :: l) :: ls
    | [] => [[c]]

/-- The comma-separated list elements of header values, trimmed and lower-cased. -/
def tokens (vs : List Bytes) : List Bytes := vs.flatMap fun v => (splitComma v).map (lower ∘ trim)

def digitsValue (s : Bytes) : Nat := s.foldl (fun acc c => acc * 10 + (c.toNat - '0'.toNat)) 0

/-- The largest Content-Length and chunk size Netty parses (`Long.MAX_VALUE`, `Integer.MAX_VALUE`). -/
def longMax : Nat := 2 ^ 63 - 1
def intMax : Nat := 2 ^ 31 - 1

inductive Framing where
  | length (n : Nat)
  | chunked
  deriving Repr, DecidableEq

/-- How the body is framed, or the rejection (RFC 9112 §6). -/
def frame (maxSize : Nat) (http10 : Bool) (headers : List (Bytes × Bytes)) :
    Except (Nat × String) Framing :=
  let te := values headers "transfer-encoding"
  if !te.isEmpty then
    if http10 then .error (400, "Transfer-Encoding is not allowed in HTTP/1.0")
    else
      let codings := tokens te
      if codings.getLast? != some "chunked".toList then .error (400, "Invalid Transfer-Encoding")
      -- `chunked` must not be applied more than once (RFC 9112 §7.1)
      else if codings.dropLast.contains "chunked".toList then
        .error (400, "Invalid Transfer-Encoding")
      else if codings.length > 1 then .error (501, "Unsupported Transfer-Encoding")
      else .ok .chunked
  else
    match values headers "content-length" with
    | [] => .ok (.length 0)
    | [v] =>
      if v.contains ',' then .error (400, "Multiple Content-Length values")
      else if v.isEmpty || !v.all Char.isDigit then .error (400, "Invalid Content-Length")
      -- A value that doesn't fit in a Long is not a length (Netty's `Long.parseLong`): 400, not 413
      else if digitsValue v > longMax then .error (400, "Invalid Content-Length")
      else if digitsValue v > maxSize then .error (413, "Request body too large")
      else .ok (.length (digitsValue v))
    | _ :: _ :: _ => .error (400, "Multiple Content-Length values")

/-- `Expect: 100-continue` is the only expectation; HTTP/1.0 doesn't define `Expect`. -/
def checkExpect (http10 : Bool) (headers : List (Bytes × Bytes)) : Except (Nat × String) Unit :=
  match values headers "expect" with
  | [] => .ok ()
  | [v] =>
    if http10 || lower v == "100-continue".toList then .ok ()
    else .error (417, "Unsupported expectation")
  | _ => if http10 then .ok () else .error (417, "Unsupported expectation")

/-- Netty's `HttpUtil.isKeepAlive`, and a close after a request framed both ways. -/
def keepAlive (http10 chunked : Bool) (headers : List (Bytes × Bytes)) : Bool :=
  let ts := tokens (values headers "connection")
  !ts.contains "close".toList && (!http10 || ts.contains "keep-alive".toList) &&
    !(chunked && !(values headers "content-length").isEmpty)

/-! ## Chunked bodies -/

def hexValue (c : Char) : Option Nat :=
  if c.isDigit then some (c.toNat - '0'.toNat)
  else if 'a' ≤ c && c ≤ 'f' then some (c.toNat - 'a'.toNat + 10)
  else if 'A' ≤ c && c ≤ 'F' then some (c.toNat - 'A'.toNat + 10)
  else none

inductive Size where
  | ok (size digits : Nat) (rest : Bytes)
  | tooLarge (rest : Bytes)
  | overflow
  | incomplete
  deriving Repr, DecidableEq

/-- The rest of a chunk size past `maxLeft`: a size that doesn't fit in an Int is not a size
(Netty's chunk-size parser), so it is told apart from one that is only too large. -/
def restOfSize : Nat → Bytes → Size
  | _, [] => .incomplete
  | size, c :: t =>
    match hexValue c with
    | none => if size > intMax then .overflow else .tooLarge (c :: t)
    | some v => restOfSize (size * 16 + v) t

/-- Hex digits of a chunk size. The size may not exceed `maxLeft`, the body bytes still allowed. -/
def readSize (maxLeft : Nat) : Nat → Nat → Bytes → Size
  | _, _, [] => .incomplete
  | size, digits, c :: t =>
    match hexValue c with
    | none => .ok size digits (c :: t)
    | some v =>
      if size * 16 + v > maxLeft then restOfSize (size * 16 + v) t else readSize maxLeft (size * 16 + v) (digits + 1) t

inductive Ext where
  | ok (rest : Bytes)
  | bad
  | incomplete
  deriving Repr, DecidableEq

/-- A chunk extension, skipped up to the CR or LF that ends it. -/
def skipExt : Bytes → Ext
  | [] => .incomplete
  | c :: t =>
    if c == '\r' || c == '\n' then .ok (c :: t)
    else if isInvalidValueChar c then .bad
    else skipExt t

/-- A CR LF at the front of `r`, and the bytes after it. -/
def crlf : Bytes → Ext
  | a :: b :: r => if a = '\r' ∧ b = '\n' then .ok r else .bad
  | _ => .incomplete

/-- After the size digits: an optional extension, up to the line's end. -/
def skipChunkExt : Bytes → Ext
  | ';' :: t => skipExt t
  | r => .ok r

inductive Body where
  | ok (body : Bytes) (rest : Bytes)
  | bad (code : Nat) (message : String)
  | incomplete
  deriving Repr, DecidableEq

/-- A chunk size over the limit: 413, but only once its line is well formed, so a malformed line
still gets 400 whatever its size. -/
def chunkTooLarge (r : Bytes) : Body :=
  match skipChunkExt r with
  | .incomplete => .incomplete
  | .bad => .bad 400 "Invalid chunk extension"
  | .ok r2 =>
    match crlf r2 with
    | .ok _ => .bad 413 "Request body too large"
    | .bad => .bad 400 "Invalid chunk size"
    | .incomplete => .incomplete

/-- Decode chunks after `total` body bytes `acc`: the body and the bytes after the trailers. Every
chunk takes at least 3 bytes, so `s.length + 1` fuel is enough. -/
def readChunks (maxSize : Nat) : Nat → Nat → Bytes → Bytes → Body
  | 0, _, _, _ => .incomplete
  | fuel + 1, total, acc, s =>
    match readSize (maxSize - total) 0 0 s with
    | .incomplete => .incomplete
    | .tooLarge r => chunkTooLarge r
    | .overflow => .bad 400 "Invalid chunk size"
    | .ok size digits r =>
      if digits == 0 then .bad 400 "Invalid chunk size"
      else
        match skipChunkExt r with
        | .incomplete => .incomplete
        | .bad => .bad 400 "Invalid chunk extension"
        | .ok r2 =>
          match crlf r2 with
          | .ok r3 =>
            if size == 0 then
              match readFields (r3.length + 1) r3 with
              | .ok _ rest => .ok acc rest
              | .bad m => .bad 400 m
              | .incomplete => .incomplete
            else if r3.length < size + 2 then .incomplete
            else if (r3.drop size).take 2 == ['\r', '\n'] then
              readChunks maxSize fuel (total + size) (acc ++ r3.take size) (r3.drop (size + 2))
            else .bad 400 "Invalid chunk data"
          | .bad => .bad 400 "Invalid chunk size"
          | .incomplete => .incomplete

/-! ## Requests -/

def isNewline (c : Char) : Bool := c == '\r' || c == '\n'

/-- The request line and headers of a request. -/
structure Head where
  method : Bytes
  target : Bytes
  http10 : Bool
  headers : List (Bytes × Bytes)
  after : Bytes

inductive HeadResult where
  | ok (head : Head)
  | fail (outcome : Outcome)

/-- Read the request line and headers. CR and LF before the request line are skipped (RFC 9112
§2.2). -/
def readHead (s0 : Bytes) : HeadResult :=
  let s := s0.dropWhile isNewline
  match scanLine false s with
  | .incomplete => .fail (if s.all isHttpWhitespace then .closed else .incomplete "Incomplete request")
  | .bad => .fail (.bad 400 "Invalid line ending")
  | .ok line afterLine =>
    match parseRequestLine line with
    | .error (c, m) => .fail (.bad c m)
    | .ok (method, target, http10) =>
      match readFields (afterLine.length + 1) afterLine with
      | .incomplete => .fail (.incomplete "Incomplete request")
      | .bad m => .fail (.bad 400 m)
      | .ok headers after => .ok ⟨method, target, http10, headers, after⟩

/-- Check the head and read the body: the outcome and the bytes left for the next request. -/
def readBody (maxSize : Nat) (hd : Head) : Outcome × Bytes :=
  if (values hd.headers "host").length > 1 then (.bad 400 "Multiple Host headers", [])
  else
    match frame maxSize hd.http10 hd.headers with
    | .error (c, msg) => (.bad c msg, [])
    | .ok fr =>
      -- After the framing headers, so a request with broken ones gets 400, as in uni-netty
      match methodOf hd.method with
      | none => (.bad 501 s!"Unsupported HTTP method: {String.ofList hd.method}", [])
      | some m =>
        match checkExpect hd.http10 hd.headers with
        | .error (c, msg) => (.bad c msg, [])
        | .ok () =>
          let keep := keepAlive hd.http10 (fr == .chunked) hd.headers
          match fr with
          | .length n =>
            if hd.after.length < n then (.incomplete "Incomplete request body", [])
            else (.req m (String.ofList hd.target) keep (hd.after.take n), hd.after.drop n)
          | .chunked =>
            match readChunks maxSize (hd.after.length + 1) 0 [] hd.after with
            | .ok body rest => (.req m (String.ofList hd.target) keep body, rest)
            | .bad c msg => (.bad c msg, [])
            | .incomplete => (.incomplete "Incomplete request body", [])

/-- Read one request from the front of `s`: its outcome and the bytes left for the next one. -/
def readOne (maxSize : Nat) (s : Bytes) : Outcome × Bytes :=
  match readHead s with
  | .fail o => (o, [])
  | .ok hd => readBody maxSize hd

/-- Read requests until one isn't, or one closes the connection. -/
def readAllAux (maxSize : Nat) : Nat → Bytes → List Outcome
  | 0, _ => []
  | fuel + 1, s =>
    match readOne maxSize s with
    | (.req m t true b, rest) => .req m t true b :: readAllAux maxSize fuel rest
    | (o, _) => [o]

/-- Every request takes at least one byte, so `s.length + 1` reads are enough. -/
def readAll (maxSize : Nat) (s : Bytes) : List Outcome := readAllAux maxSize (s.length + 1) s

/-! ## Theorems -/

/-- A line and the bytes after it are a split of the input, minus the line's CRLF. -/
theorem scanLine_split : ∀ {cr : Bool} {s line rest : Bytes},
    scanLine cr s = .ok line rest → ∃ pre, s = pre ++ rest ∧ line.length < pre.length + (if cr then 1 else 0)
  | _, [], _, _, h => by simp [scanLine] at h
  | cr, c :: t, line, rest, h => by
    unfold scanLine at h
    split at h
    · split at h
      · simp only [Line.ok.injEq] at h
        obtain ⟨rfl, rfl⟩ := h
        exact ⟨[c], by simp, by simp_all⟩
      · simp at h
    · split at h
      · simp at h
      · split at h
        · obtain ⟨pre, hs, hl⟩ := scanLine_split h
          exact ⟨c :: pre, by simp [hs], by simp at hl ⊢; omega⟩
        · split at h
          · rename_i l r hr
            simp only [Line.ok.injEq] at h
            obtain ⟨rfl, rfl⟩ := h
            obtain ⟨pre, hs, hl⟩ := scanLine_split hr
            exact ⟨c :: pre, by simp [hs], by simp at hl ⊢; split <;> omega⟩
          · rename_i o ho
            cases o <;> simp_all

/-- Fields end with an empty line, so they always consume at least its CRLF. -/
theorem readFields_split : ∀ {fuel : Nat} {s : Bytes} {fs : List (Bytes × Bytes)} {rest : Bytes},
    readFields fuel s = .ok fs rest → ∃ pre, s = pre ++ rest ∧ 0 < pre.length
  | 0, _, _, _, h => by simp [readFields] at h
  | fuel + 1, s, fs, rest, h => by
    unfold readFields at h
    split at h
    · simp at h
    · simp at h
    · rename_i r hl
      simp only [Fields.ok.injEq] at h
      obtain ⟨-, rfl⟩ := h
      obtain ⟨pre, hs, hlen⟩ := scanLine_split hl
      exact ⟨pre, hs, by simp at hlen; omega⟩
    · rename_i line r hne hl
      split at h
      · simp at h
      · split at h
        · rename_i fs' r' hr
          simp only [Fields.ok.injEq] at h
          obtain ⟨-, rfl⟩ := h
          obtain ⟨pre, hs, hlen⟩ := scanLine_split hl
          obtain ⟨pre', hs', hlen'⟩ := readFields_split hr
          exact ⟨pre ++ pre', by simp [hs, hs'], by simp; omega⟩
        · rename_i o ho
          cases o <;> simp_all

theorem restOfSize_not_ok : ∀ {size : Nat} {s : Bytes} {sz d : Nat} {r : Bytes},
    restOfSize size s ≠ .ok sz d r
  | _, [], _, _, _ => by simp [restOfSize]
  | size, c :: t, sz, d, r => by
    unfold restOfSize
    split
    · split <;> simp
    · exact restOfSize_not_ok

theorem readSize_spec : ∀ {maxLeft size digits : Nat} {s : Bytes} {sz d : Nat} {r : Bytes},
    readSize maxLeft size digits s = .ok sz d r → size ≤ maxLeft → sz ≤ maxLeft ∧ r.length ≤ s.length
  | _, _, _, [], _, _, _, h, _ => by simp [readSize] at h
  | maxLeft, size, digits, c :: t, sz, d, r, h, hle => by
    unfold readSize at h
    split at h
    · simp only [Size.ok.injEq] at h
      obtain ⟨rfl, -, rfl⟩ := h
      exact ⟨hle, by simp⟩
    · split at h
      · exact absurd h restOfSize_not_ok
      · rename_i hgt
        have ⟨h1, h2⟩ := readSize_spec h (by omega)
        exact ⟨h1, by simp; omega⟩

theorem skipExt_spec : ∀ {s r : Bytes}, skipExt s = .ok r → r.length ≤ s.length
  | [], _, h => by simp [skipExt] at h
  | c :: t, r, h => by
    unfold skipExt at h
    split at h
    · simp only [Ext.ok.injEq] at h
      subst h
      simp
    · split at h
      · simp at h
      · have := skipExt_spec h
        simp; omega

theorem skipChunkExt_spec {s r : Bytes} (h : skipChunkExt s = .ok r) : r.length ≤ s.length := by
  unfold skipChunkExt at h
  split at h
  · have := skipExt_spec h
    simp; omega
  · simp only [Ext.ok.injEq] at h
    subst h
    exact Nat.le_refl _

/-- A chunk size over the limit never yields a body. -/
theorem chunkTooLarge_not_ok {r body rest : Bytes} : chunkTooLarge r ≠ .ok body rest := by
  unfold chunkTooLarge
  repeat' split
  all_goals simp

/-- A CR LF takes two bytes. -/
theorem crlf_ok {r r3 : Bytes} (h : crlf r = .ok r3) : r.length = r3.length + 2 := by
  match r, h with
  | a :: b :: r, h =>
    simp only [crlf] at h
    split at h
    · simp only [Ext.ok.injEq] at h; subst h; simp
    · simp at h
  | [_], h => simp [crlf] at h
  | [], h => simp [crlf] at h

/-- A chunked body never exceeds the limit, and decoding consumes at least one byte. -/
theorem readChunks_spec {maxSize : Nat} :
    ∀ {fuel total : Nat} {acc s body rest : Bytes},
      readChunks maxSize fuel total acc s = .ok body rest → acc.length ≤ total → total ≤ maxSize →
      body.length ≤ maxSize ∧ rest.length < s.length
  | 0, _, _, _, _, _, h, _, _ => by simp [readChunks] at h
  | fuel + 1, total, acc, s, body, rest, h, hacc, htot => by
    unfold readChunks at h
    split at h
    · simp at h
    · exact absurd h chunkTooLarge_not_ok
    · simp at h
    · rename_i size digits r hsize
      have ⟨hsz, hr⟩ := readSize_spec hsize (Nat.zero_le _)
      split at h
      · simp at h
      · split at h
        · simp at h
        · simp at h
        · rename_i r2 he
          have hr2 := skipChunkExt_spec he
          split at h
          · rename_i r3 hc
            have hr3 := crlf_ok hc
            split at h
            · split at h
              · rename_i fs rest' hf
                simp only [Body.ok.injEq] at h
                obtain ⟨rfl, rfl⟩ := h
                obtain ⟨pre, hs, -⟩ := readFields_split hf
                refine ⟨by omega, ?_⟩
                have : r3.length = pre.length + rest'.length := by simp [hs]
                omega
              · simp at h
              · simp at h
            · split at h
              · simp at h
              · split at h
                · rename_i hlen _ hcrlf
                  have ⟨h1, h2⟩ := readChunks_spec h (by simp; omega) (by omega)
                  refine ⟨h1, ?_⟩
                  simp at h2
                  omega
                · simp at h
          · simp at h
          · simp at h

/-- A body framed by `Content-Length` is never larger than the limit. -/
theorem frame_le {maxSize n : Nat} {http10 : Bool} {headers : List (Bytes × Bytes)}
    (h : frame maxSize http10 headers = .ok (.length n)) : n ≤ maxSize := by
  unfold frame at h
  cases hte : (values headers "transfer-encoding").isEmpty
  · simp only [hte, Bool.not_false, ite_true] at h
    repeat' split at h
    all_goals simp at h
  · simp only [hte, Bool.not_true, Bool.false_eq_true, ite_false] at h
    repeat' split at h
    all_goals first
      | (simp at h; done)
      | (simp only [Except.ok.injEq, Framing.length.injEq] at h; omega)

/-- More than one `Content-Length` value is rejected, even identical ones, unless the request is
chunked (as in Netty 4.2). -/
theorem multiple_content_length {maxSize : Nat} {http10 : Bool} {headers : List (Bytes × Bytes)}
    (hte : values headers "transfer-encoding" = [])
    (h2 : 2 ≤ (values headers "content-length").length) :
    frame maxSize http10 headers = .error (400, "Multiple Content-Length values") := by
  unfold frame
  simp only [hte, List.isEmpty_nil, Bool.not_true, Bool.false_eq_true, ite_false]
  match hv : values headers "content-length", h2 with
  | _ :: _ :: _, _ => rfl

/-- A request with `Transfer-Encoding` is never framed by `Content-Length`. -/
theorem transfer_encoding_wins {maxSize n : Nat} {http10 : Bool} {headers : List (Bytes × Bytes)}
    (hte : values headers "transfer-encoding" ≠ []) :
    frame maxSize http10 headers ≠ .ok (.length n) := by
  unfold frame
  have : (values headers "transfer-encoding").isEmpty = false := by
    cases h : values headers "transfer-encoding" <;> simp_all
  simp only [this, Bool.not_false, ite_true]
  repeat' split
  all_goals simp

/-- `Transfer-Encoding` in an HTTP/1.0 request is rejected. -/
theorem transfer_encoding_http10 {maxSize : Nat} {headers : List (Bytes × Bytes)}
    (hte : values headers "transfer-encoding" ≠ []) :
    frame maxSize true headers = .error (400, "Transfer-Encoding is not allowed in HTTP/1.0") := by
  unfold frame
  have : (values headers "transfer-encoding").isEmpty = false := by
    cases h : values headers "transfer-encoding" <;> simp_all
  simp [this]

/-- After a request framed both ways, the connection closes. -/
theorem chunked_with_length_closes {http10 : Bool} {headers : List (Bytes × Bytes)}
    (hcl : values headers "content-length" ≠ []) : keepAlive http10 true headers = false := by
  have : (values headers "content-length").isEmpty = false := by
    cases h : values headers "content-length" <;> simp_all
  simp [keepAlive, this]

/-- The head is a prefix of the input, and takes at least one byte. -/
theorem readHead_split {s : Bytes} {hd : Head} (h : readHead s = .ok hd) :
    ∃ pre, s = pre ++ hd.after ∧ 0 < pre.length := by
  unfold readHead at h
  dsimp only at h
  split at h
  · simp at h
  · simp at h
  · rename_i line afterLine hl
    obtain ⟨pre0, hs0, hlen0⟩ := scanLine_split hl
    split at h
    · simp at h
    · split at h
      · simp at h
      · simp at h
      · rename_i headers after hf
        simp only [HeadResult.ok.injEq] at h
        subst h
        obtain ⟨pre1, hs1, hlen1⟩ := readFields_split hf
        refine ⟨s.takeWhile isNewline ++ pre0 ++ pre1, ?_, by simp; omega⟩
        have := List.takeWhile_append_dropWhile (p := isNewline) (l := s)
        conv => lhs; rw [← this]
        rw [hs0, hs1]
        simp

/-- A failed head is never a request. -/
theorem readHead_fail {s : Bytes} {o : Outcome} (h : readHead s = .fail o) : o.isReq = false := by
  unfold readHead at h
  dsimp only at h
  repeat' split at h
  all_goals simp only [HeadResult.fail.injEq, reduceCtorEq] at h
  all_goals (subst h; rfl)

/-- A request consumes at least one byte, so the next request starts later in the stream. -/
theorem readOne_req_shorter {maxSize : Nat} {s rest body : Bytes} {m t : String} {k : Bool}
    (h : readOne maxSize s = (.req m t k body, rest)) : rest.length < s.length := by
  unfold readOne at h
  split at h
  · rename_i o ho
    simp only [Prod.mk.injEq] at h
    have := readHead_fail ho
    rw [h.1] at this
    simp [Outcome.isReq] at this
  · rename_i hd hh
    obtain ⟨pre, hs, hpre⟩ := readHead_split hh
    have hlen : hd.after.length < s.length := by rw [hs]; simp; omega
    unfold readBody at h
    repeat' split at h
    all_goals simp only [Prod.mk.injEq, Outcome.req.injEq, reduceCtorEq, false_and] at h
    · obtain ⟨-, rfl⟩ := h
      simp
      omega
    · rename_i body' rest' hc
      obtain ⟨-, rfl⟩ := h
      have := (readChunks_spec hc (by simp) (Nat.zero_le _)).2
      omega

/-- A body framed by `Content-Length` is exactly the next `n` bytes after the head, and the next
request starts right after them. -/
theorem readBody_length {maxSize n : Nat} {hd : Head} {m : String}
    (hm : methodOf hd.method = some m) (hhost : (values hd.headers "host").length ≤ 1)
    (hf : frame maxSize hd.http10 hd.headers = .ok (.length n))
    (he : checkExpect hd.http10 hd.headers = .ok ()) (hn : n ≤ hd.after.length) :
    readBody maxSize hd =
      (.req m (String.ofList hd.target) (keepAlive hd.http10 false hd.headers) (hd.after.take n),
        hd.after.drop n) := by
  have hhost' : ¬ (values hd.headers "host").length > 1 := by omega
  have hn' : ¬ hd.after.length < n := by omega
  have hb : (Framing.length n == Framing.chunked) = false := by simp
  simp [readBody, hm, hhost', hf, he, hn', hb]

/-- Only the last outcome can be other than a kept-alive request: a rejection, a close, or a
request that closes the connection ends it. -/
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
