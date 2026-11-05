package example.server

import wvlet.uni.cli.term.PtyOptions
import wvlet.uni.log.LogSupport

import scala.scalajs.js

/**
  * Starts zsh and bash with shell integration: OSC 133 marks for each prompt, command line, command
  * output, and exit code, so the Terminal tab can mark failed commands and send them to the chat.
  * The user's own startup files still load first.
  *
  *   - zsh reads its startup files from `$ZDOTDIR`, so it starts with `ZDOTDIR` pointing at a
  *     directory whose `.zshenv` and `.zshrc` source the user's files, then add the hooks.
  *   - bash starts with `--rcfile`, a file that sources `~/.bashrc`, then adds the hooks. It sets
  *     the DEBUG trap, replacing one the user's `.bashrc` set.
  *
  * Other shells start unchanged, as does every shell when `UNI_HARNESS_NO_SHELL_INTEGRATION` is
  * set.
  */
object ShellIntegration extends LogSupport:

  /** The arguments and options to start `shell` with, with integration when it is zsh or bash. */
  def wrap(shell: String, args: Seq[String], options: PtyOptions): (Seq[String], PtyOptions) =
    if env("UNI_HARNESS_NO_SHELL_INTEGRATION").isDefined then
      (args, options)
    else
      shellName(shell) match
        case "zsh" =>
          dir.fold((args, options)) { d =>
            val user = env("ZDOTDIR").map("UNI_USER_ZDOTDIR" -> _)
            (args, options.withEnv(options.env ++ user + ("ZDOTDIR" -> d)))
          }
        case "bash" =>
          dir.fold((args, options))(d => (Seq("--rcfile", s"${d}/bashrc") ++ args, options))
        case _ =>
          (args, options)

  private[server] def shellName(shell: String): String = shell
    .split("[/\\\\]")
    .last
    .toLowerCase
    .stripSuffix(".exe")

  // A fresh private directory (mkdtemp creates it with mode 0700), so no other user can plant a
  // startup file in it. Created once per app run.
  private lazy val dir: Option[String] =
    try
      val process = js.Dynamic.global.process
      val fs      = process.getBuiltinModule("node:fs")
      val os      = process.getBuiltinModule("node:os")
      val path    = process.getBuiltinModule("node:path")
      val d = fs.mkdtempSync(path.join(os.tmpdir(), "uni-harness-shell-")).asInstanceOf[String]
      fs.writeFileSync(path.join(d, ".zshenv"), zshenv)
      fs.writeFileSync(path.join(d, ".zshrc"), zshrc)
      fs.writeFileSync(path.join(d, "bashrc"), bashrc)
      Some(d)
    catch
      case e: Throwable =>
        warn(s"Shell integration is off: could not write its startup files: ${e.getMessage}")
        None

  private def env(name: String): Option[String] =
    val v = js.Dynamic.global.process.env.selectDynamic(name)
    if js.isUndefined(v) || v == null then
      None
    else
      Some(v.toString)

  private[server] val zshenv: String =
    """# uni-harness shell integration: load the user's .zshenv from their own ZDOTDIR
      |__uni_zdotdir=$ZDOTDIR
      |ZDOTDIR=${UNI_USER_ZDOTDIR:-$HOME}
      |[[ -f $ZDOTDIR/.zshenv ]] && builtin source $ZDOTDIR/.zshenv
      |# .zshenv may change ZDOTDIR; .zshrc goes back to it
      |UNI_USER_ZDOTDIR=$ZDOTDIR
      |ZDOTDIR=$__uni_zdotdir
      |unset __uni_zdotdir
      |""".stripMargin

  private[server] val zshrc: String =
    """# uni-harness shell integration: load the user's .zshrc, then emit OSC 133 marks
      |ZDOTDIR=${UNI_USER_ZDOTDIR:-$HOME}
      |unset UNI_USER_ZDOTDIR
      |[[ -f $ZDOTDIR/.zshrc ]] && builtin source $ZDOTDIR/.zshrc
      |
      |__uni_ran=
      |# Runs first, while $? is still the command's exit code
      |__uni_status() {
      |  local code=$?
      |  [[ -n $__uni_ran ]] && builtin printf '\e]133;D;%s\a' $code
      |  __uni_ran=
      |}
      |# Runs last, after prompt themes set PS1
      |__uni_prompt() {
      |  if [[ $PS1 != *$'\e]133;A'* ]]; then
      |    PS1=$'%{\e]133;A\a%}'$PS1$'%{\e]133;B\a%}'
      |  fi
      |}
      |__uni_preexec() {
      |  __uni_ran=1
      |  local c=${1//\%/%25}
      |  c=${c//;/%3B}; c=${c//$'\n'/%0A}; c=${c//$'\r'/%0D}; c=${c//$'\e'/%1B}; c=${c//$'\a'/%07}
      |  builtin printf '\e]133;C;cmdline_url=%s\a' "$c"
      |}
      |precmd_functions=(__uni_status $precmd_functions __uni_prompt)
      |preexec_functions+=(__uni_preexec)
      |""".stripMargin

  private[server] val bashrc: String =
    """# uni-harness shell integration: load the user's .bashrc, then emit OSC 133 marks
      |[[ -f ~/.bashrc ]] && builtin source ~/.bashrc
      |
      |__uni_ran=
      |__uni_ready=
      |__uni_precmd() {
      |  local code=$?
      |  [[ -n $__uni_ran ]] && builtin printf '\e]133;D;%s\a' "$code"
      |  __uni_ran=
      |  if [[ $PS1 != *'133;A'* ]]; then
      |    PS1='\[\e]133;A\a\]'"$PS1"'\[\e]133;B\a\]'
      |  fi
      |}
      |# The DEBUG trap runs before every command; only the first one after a prompt is the user's.
      |# An empty command line runs none, so the next one is PROMPT_COMMAND's.
      |__uni_preexec() {
      |  [[ -z $__uni_ready ]] && return
      |  __uni_ready=
      |  [[ $BASH_COMMAND == __uni_precmd* ]] && return
      |  __uni_ran=1
      |  builtin printf '\e]133;C\a'
      |}
      |trap '__uni_preexec' DEBUG
      |PROMPT_COMMAND="__uni_precmd${PROMPT_COMMAND:+; $PROMPT_COMMAND}; __uni_ready=1"
      |""".stripMargin

end ShellIntegration
