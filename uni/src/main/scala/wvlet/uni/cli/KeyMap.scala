/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.cli

/**
  * An editing action that a key sequence runs in [[LineReader]]
  */
enum LineAction:
  /**
    * Accept the input if [[LineReaderConfig.isInputComplete]] says it is complete; otherwise start
    * a new line in the same input
    */
  case AcceptLine

  /**
    * Insert a line break without checking for complete input
    */
  case InsertNewline

  case BackwardChar,
    ForwardChar,
    BackwardWord,
    ForwardWord

  case BeginningOfLine,
    EndOfLine,
    BeginningOfBuffer,
    EndOfBuffer

  /**
    * Move up one line in multi-line input, or to the previous history entry on the first line
    */
  case UpLineOrHistory

  /**
    * Move down one line in multi-line input, or to the next history entry on the last line
    */
  case DownLineOrHistory

  case PreviousHistory,
    NextHistory,
    ReverseSearchHistory

  case BackwardDeleteChar,
    DeleteChar

  /**
    * Delete the character under the cursor, or signal the end of input when the buffer is empty
    */
  case DeleteCharOrEndOfInput

  case KillLine,
    BackwardKillLine,
    KillWord,
    BackwardKillWord,
    Yank

  case Complete,
    ClearScreen

  /**
    * Abandon the input and return [[LineResult.Interrupted]]
    */
  case Interrupt

  /**
    * Run custom code against the editor, e.g., insert a template or accept with a suffix
    */
  case Custom(name: String, run: LineEditor => Unit)

end LineAction

/**
  * Bindings from key sequences to [[LineAction]]s. A sequence can be several keys long (a chord
  * such as Ctrl-J Ctrl-R); while the keys typed so far are a prefix of a longer binding, the editor
  * waits for more.
  *
  * Keys with no binding insert themselves if they are characters and are ignored otherwise.
  */
case class KeyMap(bindings: Map[Seq[Key], LineAction]):
  private lazy val prefixes: Set[Seq[Key]] = bindings
    .keySet
    .flatMap(seq => (1 until seq.length).map(seq.take))

  def bind(keys: Seq[Key], action: LineAction): KeyMap = KeyMap(bindings + (keys -> action))
  def bind(key: Key, action: LineAction): KeyMap       = bind(Seq(key), action)

  /**
    * Bind a sequence written in `^X` notation, e.g., `bind("^J^R", action)`
    */
  def bind(sequence: String, action: LineAction): KeyMap = bind(Key.parseSequence(sequence), action)

  def unbind(keys: Seq[Key]): KeyMap   = KeyMap(bindings - keys)
  def unbind(key: Key): KeyMap         = unbind(Seq(key))
  def unbind(sequence: String): KeyMap = unbind(Key.parseSequence(sequence))

  def get(keys: Seq[Key]): Option[LineAction] = bindings.get(keys)

  /**
    * True if `keys` is the beginning of a longer binding
    */
  def isPrefix(keys: Seq[Key]): Boolean = prefixes.contains(keys)

end KeyMap

object KeyMap:
  import LineAction.*

  val empty: KeyMap = KeyMap(Map.empty)

  /**
    * Emacs-style bindings, as in readline and JLine
    */
  val emacs: KeyMap = KeyMap(
    Map[Key, LineAction](
      Key.Enter              -> AcceptLine,
      Key.Ctrl('j')          -> AcceptLine,
      Key.Alt(Key.Enter)     -> InsertNewline,
      Key.Left               -> BackwardChar,
      Key.Ctrl('b')          -> BackwardChar,
      Key.Right              -> ForwardChar,
      Key.Ctrl('f')          -> ForwardChar,
      Key.Alt(Key.char('b')) -> BackwardWord,
      Key.Alt(Key.Left)      -> BackwardWord,
      Key.Alt(Key.char('f')) -> ForwardWord,
      Key.Alt(Key.Right)     -> ForwardWord,
      Key.Home               -> BeginningOfLine,
      Key.Ctrl('a')          -> BeginningOfLine,
      Key.End                -> EndOfLine,
      Key.Ctrl('e')          -> EndOfLine,
      Key.Alt(Key.char('<')) -> BeginningOfBuffer,
      Key.Alt(Key.char('>')) -> EndOfBuffer,
      Key.Up                 -> UpLineOrHistory,
      Key.Down               -> DownLineOrHistory,
      Key.Ctrl('p')          -> PreviousHistory,
      Key.Ctrl('n')          -> NextHistory,
      Key.Ctrl('r')          -> ReverseSearchHistory,
      Key.Backspace          -> BackwardDeleteChar,
      Key.Delete             -> DeleteChar,
      Key.Ctrl('d')          -> DeleteCharOrEndOfInput,
      Key.Ctrl('k')          -> KillLine,
      Key.Ctrl('u')          -> BackwardKillLine,
      Key.Alt(Key.char('d')) -> KillWord,
      Key.Ctrl('w')          -> BackwardKillWord,
      Key.Alt(Key.Backspace) -> BackwardKillWord,
      Key.Ctrl('y')          -> Yank,
      Key.Tab                -> Complete,
      Key.Ctrl('l')          -> ClearScreen,
      Key.Ctrl('c')          -> Interrupt
    ).map((k, v) => Seq(k) -> v)
  )

end KeyMap
