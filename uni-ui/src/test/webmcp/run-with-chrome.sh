#!/usr/bin/env bash
# Runs UiAgentTest against an installed Chrome 150+ with WebMCP turned on, so the test
# "installWebMcp round-trips through the browser's own document.modelContext" runs instead of
# being skipped. CI's Playwright Chromium predates WebMCP; remove this script once it doesn't.
#
# Usage (from the repository root): uni-ui/src/test/webmcp/run-with-chrome.sh [path-to-chrome]
#
# It points Playwright's browser cache at a temporary stand-in for the headless shell that
# execs the given Chrome with --enable-features=WebMCP. Only Playwright's headless Chromium
# revision used by this build (chromium_headless_shell-1148, Playwright 1.49) is replaced.
set -euo pipefail

chrome="${1:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
[[ -x "${chrome}" ]] || { echo "Chrome not found: ${chrome}" >&2; exit 1; }
"${chrome}" --version

browsers="$(mktemp -d)"
trap 'rm -rf "${browsers}"' EXIT
for dir in chrome-mac chrome-linux; do
  mkdir -p "${browsers}/chromium_headless_shell-1148/${dir}"
  cat > "${browsers}/chromium_headless_shell-1148/${dir}/headless_shell" <<EOF
#!/usr/bin/env bash
# Merge Playwright's --enable-features (if any) with WebMCP; Chrome reads only the last one
features="WebMCP"; args=()
for a in "\$@"; do
  case "\${a}" in
    --enable-features=*) features="\${features},\${a#--enable-features=}" ;;
    --headless*) ;;
    *) args+=("\${a}") ;;
  esac
done
exec "${chrome}" --headless=new --enable-features="\${features}" "\${args[@]}"
EOF
  chmod +x "${browsers}/chromium_headless_shell-1148/${dir}/headless_shell"
done
touch "${browsers}/chromium_headless_shell-1148/"{DEPENDENCIES_VALIDATED,INSTALLATION_COMPLETE}

# The sbt server must start with these variables, so stop a running one first
./sbt shutdown >/dev/null 2>&1 || true
PLAYWRIGHT_BROWSERS_PATH="${browsers}" PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
  ./sbt "ui/testOnly wvlet.uni.ui.agent.UiAgentTest"
./sbt shutdown >/dev/null 2>&1 || true
