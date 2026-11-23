import UniSpec.HttpReader
import UniSpec.HttpSmuggle

/-!
Writes `HttpReaderSpecCases.scala`: byte streams a client could send on one keep-alive connection,
each with the outcomes `readAll` in `UniSpec/HttpReader.lean` gives for them. `HttpReaderSpecTest`
feeds every stream to `HttpConnectionReader` in reads of several sizes and expects the same
outcomes from each.

Usage: `lake exe gen-http-reader-cases [output path]`
-/

open UniSpec.HttpReader
open UniSpec.HttpSmuggle

/-- The limits the cases run with: small, so the cases can sit just under and just over each one. -/
def lim : Limits := { maxLine := 64, maxHeader := 128, maxBody := 100 }

def rep (n : Nat) (c : Char) : String := String.ofList (List.replicate n c)

def get (path : String) : String := s!"GET {path} HTTP/1.1\r\nHost: localhost\r\n\r\n"

def post (path body : String) : String :=
  s!"POST {path} HTTP/1.1\r\nHost: localhost\r\nContent-Length: {body.length}\r\n\r\n{body}"

def chunkedPost (path : String) (chunks : String) : String :=
  s!"POST {path} HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n{chunks}"

def inputs : List String := [
  -- Empty and idle connections
  "",
  "\r\n",
  " \t\r\n",
  "  ",
  -- One request
  get "/",
  get "/a/b?x=1&y=%20",
  post "/echo" "hello",
  post "/echo" "",
  "PUT /x HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc",
  "OPTIONS * HTTP/1.1\r\n\r\n",
  -- Keep-alive and pipelining: the next request starts right after the body
  get "/1" ++ get "/2",
  post "/a" "xyz" ++ get "/b" ++ post "/c" "1234567890",
  get "/1" ++ "\r\n",
  "\r\n" ++ get "/after-blank-line",
  "\r\n\r\n\n" ++ get "/after-blank-lines",
  post "/body-looks-like-a-request" "GET / HTTP/1.1\r\n\r\n",
  -- Connection and HTTP/1.0 keep-alive
  "GET /close HTTP/1.1\r\nConnection: close\r\n\r\n" ++ get "/never-read",
  "GET /close-in-list HTTP/1.1\r\nConnection: keep-alive, Close\r\n\r\n" ++ get "/never-read",
  "GET /http10 HTTP/1.0\r\n\r\n" ++ get "/never-read",
  "GET /http10-keep-alive HTTP/1.0\r\nConnection: Keep-Alive\r\n\r\n" ++ get "/next",
  -- The request line
  "get /lower-case-method HTTP/1.1\r\n\r\n",
  "BREW /coffee HTTP/1.1\r\nContent-Length: 1\r\n\r\nx" ++ get "/never-read",
  "GET /no-version\r\n\r\n",
  "GET /trailing-space HTTP/1.1 \r\n\r\n",
  "GET /http2 HTTP/2.0\r\n\r\n",
  "GET /http09 HTTP/0.9\r\n\r\n",
  "GET /bad-version HTTP/1.x\r\n\r\n",
  "GET /lower-version http/1.1\r\n\r\n",
  "G(T / HTTP/1.1\r\n\r\n",
  "GET /tab\tin-target HTTP/1.1\r\n\r\n",
  "GET /nul\x00 HTTP/1.1\r\n\r\n",
  "GET /latin1-é HTTP/1.1\r\n\r\n",
  -- Separators: exactly one SP between the words
  "GET  /two-spaces-before-target HTTP/1.1\r\n\r\n",
  "GET /two-spaces-before-version  HTTP/1.1\r\n\r\n",
  "GET\t/tab-separator HTTP/1.1\r\n\r\n",
  "GET /tab-before-version\tHTTP/1.1\r\n\r\n",
  "GET\r /cr-separator HTTP/1.1\r\nHost: localhost\r\n\r\n" ++ get "/never-read",
  "GET\x0b/vt-separator HTTP/1.1\r\n\r\n",
  "GET\x0c/ff-separator HTTP/1.1\r\n\r\n",
  " GET /leading-space HTTP/1.1\r\n\r\n",
  get "/1" ++ "GET\t/2 HTTP/1.1\r\n\r\n",
  -- Line endings: a bare CR or LF is rejected
  "GET /bare-lf HTTP/1.1\nHost: localhost\r\n\r\n",
  "GET /bare-lf-header HTTP/1.1\r\nHost: localhost\n\r\n",
  "GET /bare-cr HTTP/1.1\r\nX-A: a\rb\r\n\r\n",
  "GET /cr-cr HTTP/1.1\r\r\n\r\n",
  "GET /lf-only HTTP/1.1\n\n",
  -- Header lines
  "GET /h HTTP/1.1\r\nX-A:  spaced \t\r\nX-B:\r\n\r\n",
  "GET /no-colon HTTP/1.1\r\nno-colon\r\n\r\n",
  "GET /no-name HTTP/1.1\r\n: no-name\r\n\r\n",
  "GET /space-before-colon HTTP/1.1\r\nContent-Length : 0\r\n\r\n",
  "GET /space-in-name HTTP/1.1\r\nX A: b\r\n\r\n",
  "GET /obs-fold HTTP/1.1\r\nX-A: a\r\n b\r\n\r\n",
  "GET /obs-fold-tab HTTP/1.1\r\nX-A: a\r\n\tb\r\n\r\n",
  "GET /nul-in-value HTTP/1.1\r\nX-A: a\x00b\r\n\r\n",
  "GET /del-in-value HTTP/1.1\r\nX-A: a\x7fb\r\n\r\n",
  "GET /tab-in-value HTTP/1.1\r\nX-A: a\tb\r\n\r\n",
  "GET /latin1-value HTTP/1.1\r\nX-A: café\r\n\r\n",
  "GET /two-hosts HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n",
  "POST /lower-case-name HTTP/1.1\r\ncontent-length: 2\r\n\r\nok",
  "POST /tabs HTTP/1.1\r\nContent-Length:\t2\t\r\n\r\nok",
  -- Content-Length
  "POST /same-twice HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\nok",
  "POST /same-in-list HTTP/1.1\r\nContent-Length: 2, 2\r\n\r\nok",
  "POST /conflicting HTTP/1.1\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\nokk",
  "POST /plus HTTP/1.1\r\nContent-Length: +2\r\n\r\nok",
  "POST /minus-zero HTTP/1.1\r\nContent-Length: -0\r\n\r\n",
  "POST /not-a-number HTTP/1.1\r\nContent-Length: 1x\r\n\r\nx",
  "POST /hex HTTP/1.1\r\nContent-Length: 0x2\r\n\r\nok",
  "POST /empty HTTP/1.1\r\nContent-Length: \r\n\r\n",
  "POST /leading-zeros HTTP/1.1\r\nContent-Length: 0002\r\n\r\nok",
  "POST /at-limit HTTP/1.1\r\nContent-Length: 100\r\n\r\n" ++ String.ofList (List.replicate 100 'a'),
  "POST /over-limit HTTP/1.1\r\nContent-Length: 101\r\n\r\n",
  "POST /overflow HTTP/1.1\r\nContent-Length: 99999999999999999999\r\n\r\n",
  "POST /long-max HTTP/1.1\r\nContent-Length: 9223372036854775807\r\n\r\n",
  "POST /long-max-plus-one HTTP/1.1\r\nContent-Length: 9223372036854775808\r\n\r\n",
  -- Broken framing headers win over an unknown method: 400, not 501
  "PLAY /overflow HTTP/1.1\r\nContent-Length: 99999999999999999999\r\n\r\n",
  "BREW /same-in-list HTTP/1.1\r\nContent-Length: 2, 2\r\n\r\nok",
  "BREW /two-hosts HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n",
  -- Chunked bodies
  chunkedPost "/chunked" "5\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/chunked-many" "1\r\na\r\nA\r\n0123456789\r\n2\r\nbc\r\n0\r\n\r\n" ++ get "/next",
  chunkedPost "/chunked-empty" "0\r\n\r\n",
  chunkedPost "/chunked-ext" "5;name=value\r\nhello\r\n0;last\r\n\r\n",
  chunkedPost "/chunked-trailers" "3\r\nabc\r\n0\r\nX-Checksum: 1\r\nX-B: 2\r\n\r\n" ++ get "/next",
  chunkedPost "/chunked-upper-hex" "a\r\n0123456789\r\n0\r\n\r\n",
  chunkedPost "/chunked-leading-zeros" "0005\r\nhello\r\n0\r\n\r\n",
  "POST /chunked-case HTTP/1.1\r\nTransfer-Encoding: Chunked\r\n\r\n0\r\n\r\n",
  chunkedPost "/chunk-at-limit" ("64\r\n" ++ String.ofList (List.replicate 100 'a') ++ "\r\n0\r\n\r\n"),
  chunkedPost "/chunk-over-limit" "65\r\n",
  chunkedPost "/chunks-over-limit" ("32\r\n" ++ String.ofList (List.replicate 50 'a') ++ "\r\n33\r\n"),
  chunkedPost "/chunk-overflow" "ffffffffffffffffffff\r\n",
  chunkedPost "/chunk-int-max" "7fffffff\r\n",
  chunkedPost "/chunk-too-large-bare-lf" "7fffff\nff",
  chunkedPost "/chunk-too-large-ext" "7fffff;x\r\n",
  chunkedPost "/chunk-int-max-plus-one" "80000000\r\n",
  chunkedPost "/no-size" "\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/size-space" "5 \r\nhello\r\n0\r\n\r\n",
  chunkedPost "/size-plus" "+5\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/size-bare-lf" "5\nhello\r\n0\r\n\r\n",
  chunkedPost "/ext-bare-lf" "5;a\nhello\r\n0\r\n\r\n",
  chunkedPost "/ext-nul" "5;a\x00\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/data-too-long" "3\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/data-bare-lf" "5\r\nhello\n0\r\n\r\n",
  chunkedPost "/trailer-obs-fold" "0\r\nX-A: a\r\n b\r\n\r\n",
  chunkedPost "/trailer-bad-name" "0\r\nX A: a\r\n\r\n",
  chunkedPost "/truncated-chunk" "5\r\nhel",
  chunkedPost "/truncated-size" "5",
  chunkedPost "/truncated-trailers" "0\r\nX-A: a\r\n",
  -- Transfer-Encoding framing
  "POST /chunked-and-length HTTP/1.1\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n\r\n" ++ get "/never-read",
  "POST /gzip-chunked HTTP/1.1\r\nTransfer-Encoding: gzip, chunked\r\n\r\n0\r\n\r\n",
  "POST /chunked-gzip HTTP/1.1\r\nTransfer-Encoding: chunked, gzip\r\n\r\n",
  "POST /chunked-twice HTTP/1.1\r\nTransfer-Encoding: chunked\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
  "POST /identity HTTP/1.1\r\nTransfer-Encoding: identity\r\nContent-Length: 1\r\n\r\nx",
  "POST /empty-te HTTP/1.1\r\nTransfer-Encoding: \r\n\r\n",
  "POST /http10-chunked HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
  -- Expect
  "POST /expect HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\nok",
  "POST /expect-case HTTP/1.1\r\nExpect: 100-Continue\r\nContent-Length: 2\r\n\r\nok",
  "POST /expect-other HTTP/1.1\r\nExpect: something\r\nContent-Length: 2\r\n\r\nok",
  "POST /expect-http10 HTTP/1.0\r\nExpect: something\r\nContent-Length: 2\r\n\r\nok",
  -- Truncated and malformed requests
  "GET / HTTP/1.1\r\nHost: localhost\r\n",
  "GET / HTTP/1.1",
  "GET / HTTP/1.1\r",
  "POST /short HTTP/1.1\r\nContent-Length: 5\r\n\r\nabc",
  get "/ok" ++ "GET /cut",
  "GET\r\n\r\n",
  "GET \r\n\r\n",
  "GET    \r\n\r\n",
  " GET / HTTP/1.1\r\n\r\n",
  get "/1" ++ "BAD\r\n\r\n" ++ get "/never-read",
  -- Size limits (`lim`): each one exactly at its limit and one byte over
  -- The request line: `GET /` and ` HTTP/1.1` take 14 of its 64 bytes
  s!"GET /{rep 50 'a'} HTTP/1.1\r\n\r\n",
  s!"GET /{rep 51 'a'} HTTP/1.1\r\n\r\n",
  -- Past the limit, the line's length wins over a bare LF or a missing version after it
  s!"GET /{rep 51 'a'} HTTP/1.1\nHost: x\r\n\r\n",
  s!"GET /{rep 70 'a'}",
  -- Whitespace past the limit is not a clean close
  rep 70 ' ',
  -- The header block: 128 bytes from its first one; its CR LF and empty line may lie past them
  s!"GET / HTTP/1.1\r\nX-A: {rep 123 'b'}\r\n\r\n",
  s!"GET / HTTP/1.1\r\nX-A: {rep 124 'b'}\r\n\r\n",
  -- The limit counts every header line, CR LF included
  s!"GET / HTTP/1.1\r\nHost: localhost\r\nX-A: {rep 106 'b'}\r\n\r\n",
  s!"GET / HTTP/1.1\r\nHost: localhost\r\nX-A: {rep 107 'b'}\r\n\r\n",
  -- A line is checked at its end, so the limit wins over a bad name in the same line
  s!"GET / HTTP/1.1\r\nX A: {rep 124 'b'}\r\n\r\n",
  s!"GET / HTTP/1.1\r\nX A: b\r\nX-B: {rep 124 'b'}\r\n\r\n",
  -- The headers of the next request get their own limit
  s!"GET /1 HTTP/1.1\r\nX-A: {rep 123 'b'}\r\n\r\nGET /2 HTTP/1.1\r\nX-A: {rep 123 'b'}\r\n\r\n",
  -- Trailers have the header limit
  chunkedPost "/trailers-at-limit" s!"0\r\nX-T: {rep 123 'c'}\r\n\r\n",
  chunkedPost "/trailers-over-limit" s!"0\r\nX-T: {rep 124 'c'}\r\n\r\n",
  -- A chunk-size line has the request-line limit; leading zeros count
  chunkedPost "/size-line-at-limit" s!"{rep 63 '0'}5\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/size-line-over-limit" s!"{rep 64 '0'}5\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/ext-at-limit" s!"5;{rep 62 'e'}\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/ext-over-limit" s!"5;{rep 63 'e'}\r\nhello\r\n0\r\n\r\n",
  -- The `;` itself is not checked, an invalid extension byte wins over the limit, and the length
  -- wins over a size above the body limit
  chunkedPost "/semicolon-past-limit" s!"{rep 63 '0'}5;\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/ext-nul-past-limit" s!"5;{rep 62 'e'}\x00\r\nhello\r\n0\r\n\r\n",
  chunkedPost "/size-too-large-and-long" s!"{rep 63 '0'}65\r\n"
]

/-! ## Smuggling checks (`UniSpec/HttpSmuggle.lean`)

Every input and every variant below is checked against the lenient family: at each request the
reader accepts and keeps the connection open after, every member must reject it or agree on the
head length and framing. `no_smuggling` (`UniSpec/HttpSmuggleProof.lean`) proves this for every
input, so the check guards the generator and the model's executable code, and picks the cases. Variants change the head of an input's first request by one edit. Those the
reader rejects or closes after, but on which the edit makes two members frame differently, are the
inputs a lenient front proxy could smuggle with, so a selection of them become cases too.
-/

/-- The members that disagree with the reader at a kept-alive request of `s`, as messages. -/
def disagreements (s : Bytes) : List String :=
  go (s.length + 1) s
where
  go : Nat → Bytes → List String
    | 0, _ => []
    | fuel + 1, s =>
      match readerFraming lim s with
      | none => []
      | some r =>
        let here := (allOpts.filter (fun o => !agrees r o s)).map fun o =>
          s!"{repr o}: the reader frames {repr r}, the member {repr (interp o s)}"
        match readOne lim s with
        | (.req _ _ true _, rest) => here ++ go fuel rest
        | _ => here

/-- Byte strings inserted at each position of a head. -/
def insertions : List Bytes := ["\r", "\n", " ", "\t", ",", "\r\n", "\r\n ", "\r\n\t"].map String.toList

/-- Bytes each byte of a head is replaced with. -/
def replacements : List Char := ['\r', '\n', ' ', '\t', ',']

/-- Every one-edit variant of the head of the first request in `s`: an insertion, a replaced byte,
or a header line repeated. -/
def variants (s : Bytes) : List Bytes :=
  match readHead lim s with
  | .fail _ => []
  | .ok hd =>
    let n := s.length - hd.after.length
    let edits := (List.range n).flatMap fun i =>
      (insertions.map fun e => s.take i ++ e ++ s.drop i) ++
        (replacements.filterMap fun c =>
          if s[i]? == some c then none else some (s.take i ++ [c] ++ s.drop (i + 1)))
    -- A header line starts after a CR LF, before the head's final empty line
    let lineStarts := (List.range n).filter fun i =>
      2 ≤ i && i + 2 < n && s[i - 2]? == some '\r' && s[i - 1]? == some '\n'
    let repeats := lineStarts.filterMap fun i =>
      let rest := s.drop i
      match (List.range rest.length).find? fun j => rest[j]? == some '\r' && rest[j + 1]? == some '\n' with
      | some j => some (s.take i ++ rest.take (j + 2) ++ rest)
      | none => none
    edits ++ repeats

/-- Distinct framings the members give for the head at the front of `s`, rejections left out. -/
def memberFramings (s : Bytes) : List (Nat × Framing) :=
  allOpts.foldl (fun acc o =>
    match interp o s with
    | some m => if acc.contains m then acc else acc ++ [m]
    | none => acc) []

/-- The options that matter on `s`: flipping one changes some member's answer. -/
def flipsThatMatter (s : Bytes) : List String :=
  (flips.filter fun (_, f) => allOpts.any fun o => interp (f o) s != interp o s).map (·.1)

/-- The first outcome the reader gives for `s`, without the request target. -/
def firstOutcomeKind (s : Bytes) : String :=
  match readAll lim s with
  | .req m _ k _ :: _ => s!"req {m} {k}"
  | .bad c msg :: _ => s!"bad {c} {msg}"
  | .incomplete msg :: _ => s!"bad 400 {msg}"
  | _ => "closed"

/-- How many variants become cases at most. -/
def maxSmugglingCases : Nat := 48

structure SmuggleReport where
  failures : List String
  variantCount : Nat
  splitting : Nat
  cases : List String
  flipsSeen : List String

/-- Check every input and variant, and pick the variants that become cases: per kind of first
outcome and set of options that matter, the first one met. -/
def smuggleReport : SmuggleReport := Id.run do
  let mut failures := []
  let mut count := 0
  let mut splitting := 0
  let mut seen : List String := []
  let mut picked : List String := []
  let mut flipsSeen : List String := []
  for input in inputs do
    let s := input.toList
    for msg in disagreements s do
      failures := failures ++ [s!"{repr input}: {msg}"]
    -- Only an edit that splits the family is of interest, not one of an input that already does
    let inputSplits := (memberFramings s).length > 1
    for v in variants s do
      count := count + 1
      for msg in disagreements v do
        failures := failures ++ [s!"{repr (String.ofList v)}: {msg}"]
      if !inputSplits && (readerFraming lim v).isNone && (memberFramings v).length > 1 then
        splitting := splitting + 1
        let fl := flipsThatMatter v
        for f in fl do
          if !flipsSeen.contains f then flipsSeen := flipsSeen ++ [f]
        let key := s!"{firstOutcomeKind v} {fl}"
        if !seen.contains key && picked.length < maxSmugglingCases then
          seen := seen ++ [key]
          picked := picked ++ [String.ofList v]
  return ⟨failures, count, splitting, picked, flipsSeen⟩

def outcomeLine : Outcome → String
  | .req m t k b => s!"req {m} {t} {if k then "keep-alive" else "close"} {String.ofList b}"
  | .bad code msg => s!"bad {code} {msg}"
  -- The reader reports an incomplete request as a 400
  | .incomplete msg => s!"bad 400 {msg}"
  | .closed => "closed"

/-- A Scala string literal of `s`. -/
def scalaString (s : String) : String :=
  let esc : Char → String
    | '\r' => "\\r"
    | '\n' => "\\n"
    | '\t' => "\\t"
    | '"' => "\\\""
    | '\\' => "\\\\"
    | c =>
      if c.toNat < 0x20 || c.toNat == 0x7f then
        let h := String.ofList (Nat.toDigits 16 c.toNat)
        "\\u" ++ String.ofList (List.replicate (4 - h.length) '0') ++ h
      else c.toString
  "\"" ++ String.join (s.toList.map esc) ++ "\""

def caseLine (input : String) : String :=
  let outcomes := (readAll lim input.toList).map outcomeLine
  s!"    {scalaString (input ++ "␞" ++ "␟".intercalate outcomes)}"

-- Formatted as scalafmt would, so the Code format check passes on the generated file
def scalaSource (smuggling : List String) : String :=
  "\n".intercalate ([
    "// Generated by `lake exe gen-http-reader-cases` in spec/lean from UniSpec/HttpReader.lean. Do not edit.",
    "package wvlet.uni.http",
    "",
    "object HttpReaderSpecCases:",
    "  // The HttpReaderLimits the outcomes assume",
    s!"  val maxInitialLineLength: Int = {lim.maxLine}",
    -- Aligned as scalafmt aligns consecutive vals
    s!"  val maxHeaderSize: Int        = {lim.maxHeader}",
    s!"  val maxRequestSize: Int       = {lim.maxBody}",
    "",
    "  // Each case: the bytes a client sends, ␞, then the reader's outcomes in order, separated by ␟.",
    "  // An outcome is `req <method> <target> <keep-alive|close> <body>`, `bad <status> <message>`,",
    "  // or `closed`.",
    "  val cases: Seq[String] = Seq("
  ] ++ [",\n".intercalate (inputs.map caseLine), "  )", "",
    "  // Inputs a lenient front proxy could frame differently (UniSpec/HttpSmuggle.lean): one-edit",
    "  // variants of the cases above that the reader rejects or closes the connection after.",
    "  val smugglingCases: Seq[String] = Seq("
  ] ++ [",\n".intercalate (smuggling.map caseLine), "  )", "", "end HttpReaderSpecCases", ""])

def defaultOutput : System.FilePath :=
  "../../uni/.jvm-native/src/test/scala/wvlet/uni/http/HttpReaderSpecCases.scala"

def main (args : List String) : IO Unit := do
  let out := args.head?.map System.FilePath.mk |>.getD defaultOutput
  let r := smuggleReport
  if !r.failures.isEmpty then
    IO.eprintln s!"{r.failures.length} disagreements with the lenient family (UniSpec/HttpSmuggle.lean):"
    for f in r.failures.take 20 do IO.eprintln s!"  {f}"
    IO.Process.exit 1
  let missing := flips.map (·.1) |>.filter (!r.flipsSeen.contains ·)
  if !missing.isEmpty then
    IO.eprintln s!"No variant splits the family on these options, so they are never exercised: {missing}"
    IO.Process.exit 1
  IO.FS.writeFile out (scalaSource r.cases)
  IO.println s!"Checked {inputs.length} inputs and {r.variantCount} variants against {allOpts.length} lenient parsers; {r.splitting} variants split the family"
  IO.println s!"Wrote {inputs.length} cases and {r.cases.length} smuggling cases to {out}"
