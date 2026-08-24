#!/usr/bin/env bash
#
# M5-07. Everything M0…M5 established, run in one go against a live stack.
#
# Written because every milestone so far began by bringing the stand up by hand and re-typing the
# curl invocations of the previous one. The cost of that overtook the cost of this script somewhere
# around M5.
#
#   ./check.sh              # the whole suite
#   ./check.sh --keep       # leave the stack running afterwards, for poking at it
#
# Two rules this script follows, both paid for elsewhere:
#
#   * teardown lives in a `trap EXIT`, not at the end. A check that only runs when the script gets
#     that far is precisely the check that does not run on the day something fails;
#   * assertions compare content, never just the status code. A renderer that has lost every
#     stylesheet still answers 200, and so does a page rendered for somebody else's request.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

: "${JAVA_HOME:=$(/usr/libexec/java_home -v 21 2>/dev/null)}"
export JAVA_HOME
KEEP=false
[[ "${1:-}" == "--keep" ]] && KEEP=true

PORT_SITE=8080
PORT_RENDERER=7899
LOG_DIR=$(mktemp -d)
RENDERER_PID=""
PASS=0
FAIL=0

cleanup() {
  local status=$?
  if [[ "$KEEP" == true ]]; then
    echo
    echo "--- left running: site :$PORT_SITE, renderer :$PORT_RENDERER (pid ${RENDERER_PID:-none})"
    echo "--- logs in $LOG_DIR"
    return
  fi
  [[ -n "$RENDERER_PID" ]] && kill "$RENDERER_PID" 2>/dev/null
  ./gradlew :site:kobwebStop --console=plain >/dev/null 2>&1
  rm -rf "$LOG_DIR"
  exit $status
}
trap cleanup EXIT

# --- assertions ------------------------------------------------------------------------------

ok()   { PASS=$((PASS + 1)); printf '  \033[32mok\033[0m   %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; printf '       %s\n' "$2"; }

# fetch <file> <curl args...>  -> writes body, echoes the byte count
fetch() { local out=$1; shift; curl -sS -o "$out" -w '%{size_download}' "$@"; }

# assert_contains <label> <file> <needle>
assert_contains() {
  if grep -qF -- "$3" "$2"; then ok "$1"; else bad "$1" "expected to find: $3"; fi
}
assert_absent() {
  if grep -qF -- "$3" "$2"; then bad "$1" "did not expect to find: $3"; else ok "$1"; fi
}
# assert_eq <label> <actual> <expected>
assert_eq() {
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1" "got '$2', wanted '$3'"; fi
}
# assert_rendered / assert_fell_back distinguish a server-rendered page from Kobweb's shell by
# size. The shell is under 2 KB and a rendered page is over 60 KB, so the threshold is not a
# judgement call.
assert_rendered()  { if [[ "$2" -gt 20000 ]]; then ok "$1"; else bad "$1" "got $2 bytes, expected a rendered page"; fi; }
assert_fell_back() { if [[ "$2" -lt 20000 ]]; then ok "$1"; else bad "$1" "got $2 bytes, expected the client shell"; fi; }

start_site() {
  ./gradlew :site:kobwebStop --console=plain >/dev/null 2>&1
  if ! ./gradlew :site:kobwebStart "$@" --console=plain > "$LOG_DIR/start.log" 2>&1; then
    echo "could not start the site:"; tail -20 "$LOG_DIR/start.log"; exit 1
  fi
  for _ in $(seq 1 30); do curl -sf "http://localhost:$PORT_SITE/" -o /dev/null 2>/dev/null && return; sleep 1; done
  echo "site never answered"; exit 1
}

start_renderer() {
  [[ -n "$RENDERER_PID" ]] && kill "$RENDERER_PID" 2>/dev/null && sleep 1
  ./renderer/build/install/renderer/bin/renderer \
    --target "http://localhost:$PORT_SITE" --port "$PORT_RENDERER" --workers 1 \
    > "$LOG_DIR/renderer.log" 2>&1 &
  RENDERER_PID=$!
  for _ in $(seq 1 30); do curl -sf "http://localhost:$PORT_RENDERER/health" -o /dev/null 2>/dev/null && return; sleep 1; done
  echo "renderer never answered"; tail -20 "$LOG_DIR/renderer.log"; exit 1
}

# --- build -----------------------------------------------------------------------------------

echo "building…"
if ! ./gradlew :server-plugin:jar :renderer:installDist --console=plain > "$LOG_DIR/build.log" 2>&1; then
  echo "build failed:"; tail -30 "$LOG_DIR/build.log"; exit 1
fi

# The export has to happen with the plugin off. It drives a real browser against the running
# server, so a loaded plugin bakes its own answers into the static files — which is how M0 first
# produced a confident false positive.
echo "exporting with the plugin off…"
rm -rf site/.kobweb/server/plugins/* site/.kobweb/site
if ! ./gradlew :site:kobwebExport -PkobwebReuseServer=false -PkobwebEnv=DEV -PkobwebExportLayout=STATIC \
     --console=plain > "$LOG_DIR/export.log" 2>&1; then
  echo "export failed:"; tail -30 "$LOG_DIR/export.log"; exit 1
fi
./gradlew :site:kobwebStop --console=plain >/dev/null 2>&1   # export leaves its own server running

echo
echo "the export is honest (no plugin answers baked in)"
# Checked by the *absence* of the plugin's marker, not the presence of the client's. The first
# version asserted "RENDERED BY THE CLIENT" in every exported file and failed on three of them —
# only the collision pages carry that string. A completeness guard written from one example
# describes that example; what actually matters here is that nothing the plugin said got baked in.
for f in site/.kobweb/site/*.html; do
  assert_absent "$(basename "$f") carries no plugin output" "$f" "RENDERED BY THE SERVER PLUGIN"
done

# --- M0: where a plugin can and cannot take over a route ---------------------------------------

echo
echo "M0 — route interception"
start_site -PprobePlugin -PssrCache=false -PkobwebEnv=PROD -PkobwebRunLayout=STATIC
assert_contains "the plugin is loaded" site/.kobweb/server/logs/kobweb-server.log "[ssr-probe] plugin loaded"

fetch "$LOG_DIR/b" "http://localhost:$PORT_SITE/ssr-probe" >/dev/null
assert_contains "a plugin route nobody else owns answers" "$LOG_DIR/b" "RENDERED BY THE SERVER PLUGIN"

fetch "$LOG_DIR/b" "http://localhost:$PORT_SITE/collide" >/dev/null
assert_contains "static layout: routing { } loses to an exported page" "$LOG_DIR/b" "RENDERED BY THE CLIENT"

fetch "$LOG_DIR/b" "http://localhost:$PORT_SITE/collide2" >/dev/null
assert_contains "static layout: the interceptor wins" "$LOG_DIR/b" "early intercept"

start_site -PprobePlugin -PssrCache=false -PkobwebEnv=DEV -PkobwebRunLayout=FULLSTACK
fetch "$LOG_DIR/b" "http://localhost:$PORT_SITE/collide" >/dev/null
assert_contains "fullstack layout: routing { } beats the catch-all" "$LOG_DIR/b" "(routing)"

fetch "$LOG_DIR/b" "http://localhost:$PORT_SITE/collide2" >/dev/null
assert_contains "fullstack layout: the interceptor wins too" "$LOG_DIR/b" "early intercept"

# --- M1: the page comes back rendered ----------------------------------------------------------

echo
echo "M1 — server rendering"
start_renderer

# Measured in the fullstack layout on purpose: there the origin serves a shell, so "equal to the
# export" is a statement about fidelity. In the static layout the renderer would be handed the
# already-exported file and the comparison would be true by construction.
SHELL_BYTES=$(fetch "$LOG_DIR/shell" -H 'X-Kobweb-Ssr-Bypass: 1' "http://localhost:$PORT_SITE/ssr")
assert_fell_back "the origin serves only a shell without SSR" "$SHELL_BYTES"
assert_absent "the shell carries no page content" "$LOG_DIR/shell" "COMPOSED MARKER"

RENDERED_BYTES=$(fetch "$LOG_DIR/ssr" "http://localhost:$PORT_SITE/ssr")
assert_rendered "the same path is server-rendered" "$RENDERED_BYTES"
if cmp -s "$LOG_DIR/ssr" site/.kobweb/site/ssr.html; then
  ok "the rendered page is byte-identical to kobweb export"
else
  bad "the rendered page is byte-identical to kobweb export" "cmp reported a difference"
fi

# What a visitor without JavaScript, and a crawler, actually get.
python3 - "$LOG_DIR/ssr" "$LOG_DIR/ssr-nojs" <<'PY'
import re, sys
html = open(sys.argv[1]).read()
open(sys.argv[2], 'w').write(re.sub(r'<script\b.*?</script>', '', html, flags=re.S | re.I))
PY
assert_contains "content survives with every script removed" "$LOG_DIR/ssr-nojs" "COMPOSED MARKER"
assert_contains "styles survive with every script removed" "$LOG_DIR/ssr-nojs" ".ssr-marker"

# --- M3: what crosses the boundary --------------------------------------------------------------

echo
echo "M3 — composition state"
fetch "$LOG_DIR/state" "http://localhost:$PORT_SITE/state" >/dev/null
assert_contains "the saved state travels inside the document" "$LOG_DIR/state" "KOBWEB_SSR_STATE"
assert_contains "a rememberSaveable value is in it" "$LOG_DIR/state" "saveable-from-"
# The plain `remember` value is rendered into the page but must not be in the saved state, so look
# for it only inside the state script.
STATE_LINE=$(grep -o 'window.KOBWEB_SSR_STATE = .*' "$LOG_DIR/state" | head -1)
if [[ "$STATE_LINE" == *"plain-from-"* ]]; then
  bad "a plain remember value stays out of the state" "found plain-from- in the serialised state"
else
  ok "a plain remember value stays out of the state"
fi

# --- M5-01: refusing rather than serving the wrong page -----------------------------------------

echo
echo "M5-01 — refusing what cannot be rendered faithfully"
B=$(fetch "$LOG_DIR/b" -H 'Cookie: a=1' "http://localhost:$PORT_SITE/state")
assert_fell_back "a request with cookies is refused, not rendered" "$B"
B=$(fetch "$LOG_DIR/b" -H 'Authorization: Bearer x' "http://localhost:$PORT_SITE/state")
assert_fell_back "a request with credentials is refused" "$B"
assert_contains "the refusal says what it refused over" site/.kobweb/server/logs/kobweb-server.log "which the renderer would drop"
B=$(fetch "$LOG_DIR/b" -H 'Accept-Language: ru' "http://localhost:$PORT_SITE/state")
assert_rendered "a route declared free of language still renders for it" "$B"
B=$(fetch "$LOG_DIR/b" -H 'Cookie: a=1' "http://localhost:$PORT_SITE/ssr")
assert_rendered "a route declared free of everything renders regardless" "$B"

# --- M5-02: the request reaches the render -------------------------------------------------------

echo
echo "M5-02 — the visitor's request reaches the render"
# Reads a page's rendered text back out. Unescapes entities, because the value goes through HTML
# on the way here: the page renders "q=<absent>" and the document carries "q=&lt;absent&gt;", and
# comparing against the un-escaped form is comparing against something that was never there.
field() { python3 -c "
import html as htmllib, re, sys
document = open(sys.argv[1]).read()
found = re.search(r'id=\"' + sys.argv[2] + r'\"[^>]*>\s*([^<]*)', document)
print(htmllib.unescape(found.group(1)).strip() if found else '<not found>')
" "$1" "$2"; }

fetch "$LOG_DIR/e" "http://localhost:$PORT_SITE/echo?q=hello" >/dev/null
assert_eq "the query string reaches the page" "$(field "$LOG_DIR/e" q)" "q=hello"
fetch "$LOG_DIR/e" "http://localhost:$PORT_SITE/echo" >/dev/null
assert_eq "an absent query stays absent" "$(field "$LOG_DIR/e" q)" "q=<absent>"
fetch "$LOG_DIR/e" -H 'Accept-Language: ru' "http://localhost:$PORT_SITE/echo" >/dev/null
assert_eq "the language reaches navigator.language" "$(field "$LOG_DIR/e" lang)" "lang=ru"

fetch "$LOG_DIR/e" "http://localhost:$PORT_SITE/echo?q=x&_kobwebColorModeStrategy=EVIL" >/dev/null
assert_eq "a smuggled renderer flag is stripped" "$(field "$LOG_DIR/e" q)" "q=x"
if grep -q 'EVIL' "$LOG_DIR/renderer.log"; then
  bad "the renderer never saw the smuggled flag" "EVIL appears in the renderer log"
else
  ok "the renderer never saw the smuggled flag"
fi

echo
echo "M5-02 — the cache keys on the request, not the path"
start_site -PprobePlugin -PssrCache=true -PkobwebEnv=DEV -PkobwebRunLayout=FULLSTACK
start_renderer
curl -sS -o /dev/null "http://localhost:$PORT_SITE/echo?q=one"
curl -sS -o /dev/null "http://localhost:$PORT_SITE/echo?q=two"
curl -sS -o /dev/null "http://localhost:$PORT_SITE/echo?q=one"
STATS=$(curl -sS "http://localhost:$PORT_SITE/ssr-probe/cache")
assert_eq "two distinct queries make two entries" "$(grep -o 'entries=[0-9]*' <<<"$STATS")" "entries=2"
assert_eq "a repeat of the same request hits" "$(grep -o 'hits=[0-9]*' <<<"$STATS")" "hits=1"

# --- result --------------------------------------------------------------------------------------

echo
echo "-------------------------------------------------------"
printf '%d passed, %d failed\n' "$PASS" "$FAIL"
[[ "$FAIL" -eq 0 ]] || exit 1
