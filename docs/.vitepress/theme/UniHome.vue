<script setup lang="ts">
import { useData, withBase } from 'vitepress'

const { theme } = useData()

// The hero example. It is the same program as the "Example" fence in docs/index.md, which
// docSnippets compiles; keep the two in sync by hand.
const heroCode = `import wvlet.uni.design.Design
import wvlet.uni.http.{Http, HttpMethod}
import wvlet.uni.http.router.{Endpoint, Router, RouterHandler}
import wvlet.uni.log.LogSupport
import wvlet.uni.weaver.Weaver

case class Greeting(name: String, message: String) derives Weaver

class Greeter:
  def greet(name: String): Greeting =
    Greeting(name, s"Hello, \${name}!")

class GreetingApi(greeter: Greeter) extends LogSupport:
  @Endpoint(HttpMethod.GET, "/hello/:name")
  def hello(name: String): Greeting =
    info(s"Greeting \${name}")
    greeter.greet(name)

@main def server =
  Design.newDesign.build[GreetingApi] { api =>
    val router = Router.of[GreetingApi]
    Http.server
      .withPort(8080)
      .withRxHandler(RouterHandler.withControllers(router, api))
      .start(_.awaitTermination())
  }`

const keywords = new Set([
  'import', 'case', 'class', 'trait', 'object', 'def', 'val', 'var', 'extends', 'with', 'derives',
  'if', 'then', 'else', 'match', 'new', 'given', 'using', 'enum', 'for', 'yield', 'return'
])

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/** A small Scala/sbt highlighter, run at build time, so the page needs no client-side highlighting. */
function highlight(code: string): string {
  const token = /(\/\/.*)|(s?"(?:[^"\\]|\\.)*")|(@\w+)|(%%?)|\b([A-Za-z_]\w*)\b|(\d+)/g
  let out = ''
  let last = 0
  for (const m of code.matchAll(token)) {
    out += escapeHtml(code.slice(last, m.index))
    const [text, comment, str, annotation, op, ident, num] = m
    const cls = comment
      ? 'tk-comment'
      : str
        ? 'tk-string'
        : annotation
          ? 'tk-annotation'
          : op
            ? 'tk-op'
            : num
              ? 'tk-number'
              : ident && keywords.has(ident)
                ? 'tk-keyword'
                : ident && /^[A-Z]/.test(ident)
                  ? 'tk-type'
                  : ''
    out += cls ? `<span class="${cls}">${escapeHtml(text)}</span>` : escapeHtml(text)
    last = m.index! + text.length
  }
  return out + escapeHtml(code.slice(last))
}

const heroHtml = highlight(heroCode)

const sbtHtml = highlight(`resolvers += "wvlet" at "https://repo.wvlet.org/maven2"

libraryDependencies += "org.wvlet.uni" %% "uni" % "${theme.value.uniVersion}"
libraryDependencies += "org.wvlet.uni" %% "uni-test" % "${theme.value.uniVersion}" % Test`)

const cliHtml = highlight(`//> using repository https://repo.wvlet.org/maven2
//> using dep org.wvlet.uni::uni:${theme.value.uniVersion}`)

const platforms = ['JVM', 'Node.js', 'Browser', 'Scala Native']

type Area = {
  name: string
  link: string
  description: string
  tags: { text: string; link: string; self?: boolean }[]
}

// The six areas of the module index, each linking to its main pages. /modules lists every page.
const areas: Area[] = [
  {
    name: 'Core',
    link: '/core/design',
    description:
      'Object wiring with lifecycles, logging with source locations, codecs from one derives Weaver, reactive streams, and retry, caching, and resource handling.',
    tags: [
      { text: 'Design', link: '/core/design' },
      { text: 'Logging', link: '/core/logging' },
      { text: 'JSON & MessagePack', link: '/core/weaver' },
      { text: 'YAML & Markdown', link: '/text/yaml' },
      { text: 'Rx', link: '/rx/' },
      { text: 'Control flow', link: '/control/' },
      { text: 'Files & IO', link: '/core/io' }
    ]
  },
  {
    name: 'HTTP & RPC',
    link: '/http/',
    description:
      'Sync and async clients with retry, a built-in server on every platform, and RPC services defined as Scala traits with type-safe clients.',
    tags: [
      { text: 'Client', link: '/http/client' },
      { text: 'Server', link: '/http/server' },
      { text: 'RPC', link: '/http/rpc' },
      { text: 'OpenAPI', link: '/http/openapi' },
      { text: 'SSE', link: '/http/sse' },
      { text: 'WebSocket', link: '/http/websocket' }
    ]
  },
  {
    name: 'Cloud Data',
    link: '/cloud/',
    description:
      'Read and write Parquet files and Iceberg tables in S3, GCS, and Azure Blob, and read Delta Lake tables, in pure Scala with no Hadoop or cloud SDKs.',
    tags: [
      { text: 'Object storage', link: '/cloud/storage' },
      { text: 'Parquet', link: '/cloud/parquet' },
      { text: 'Iceberg', link: '/cloud/iceberg' },
      { text: 'Delta Lake', link: '/cloud/delta' },
      { text: 'Arrow IPC', link: '/cloud/arrow' }
    ]
  },
  {
    name: 'AI',
    link: '/ai/',
    description:
      'Typed Anthropic, OpenAI, and Gemini APIs with converters between vendors, MCP servers built from a Scala trait, and UIs that agents can operate.',
    tags: [
      { text: 'LLM APIs', link: '/ai/' },
      { text: 'MCP servers', link: '/mcp/' },
      { text: 'UiAgent', link: '/dom/agents' }
    ]
  },
  {
    name: 'UI & CLI',
    link: '/dom/',
    description:
      'Reactive web UIs and ready-made components on Scala.js, Electron desktop apps, and terminal tools with colors, progress bars, and line editing.',
    tags: [
      { text: 'uni-dom', link: '/dom/' },
      { text: 'uni-ui', link: '/dom/ui' },
      { text: 'Gallery', link: '/gallery/index.html', self: true },
      { text: 'Electron', link: '/http/electron' },
      { text: 'CLI', link: '/cli/' }
    ]
  },
  {
    name: 'Testing & Build',
    link: '/core/unitest',
    description:
      'UniTest with shouldBe-style assertions on all three platforms, and sbt plugins for RPC client generation, browser tests, and cross-building.',
    tags: [
      { text: 'UniTest', link: '/core/unitest' },
      { text: 'sbt-uni', link: '/build/sbt-uni' },
      { text: 'Playwright', link: '/build/sbt-uni-playwright' },
      { text: 'Cross-build', link: '/build/sbt-uni-crossproject' }
    ]
  }
]

const artifacts = [
  { name: 'uni', detail: 'Everything above, except uni-ui', platforms: 'JVM · JS · Native' },
  { name: 'uni-test', detail: 'UniTest framework', platforms: 'JVM · JS · Native' },
  { name: 'uni-netty', detail: 'Optional Netty server backend', platforms: 'JVM' },
  { name: 'uni-ui', detail: 'UI components for uni-dom', platforms: 'Scala.js' }
]

const steps = [
  {
    title: 'Minimal dependencies',
    body: 'On the JVM, <code>uni</code> depends only on the Scala 3 standard library. Scala.js and Scala Native add only the platform shims they need, such as <code>java.time</code>.'
  },
  {
    title: 'One version to upgrade',
    body: 'Object wiring, logging, codecs, HTTP, and tests are released together, so they never disagree on transitive dependencies.'
  },
  {
    title: 'One codebase on three platforms',
    body: 'The same code runs on the JVM, in Node.js or the browser, and as a Scala Native binary.'
  },
  {
    title: 'One set of conventions',
    body: 'A case class becomes JSON, an RPC payload, and MCP tool arguments through the same <code>Weaver</code> codecs.'
  }
]

const agentDocs = [
  { name: 'llms.txt', detail: 'Page index', href: 'https://wvlet.org/uni/llms.txt' },
  { name: 'llms-full.txt', detail: 'All docs in one file', href: 'https://wvlet.org/uni/llms-full.txt' },
  { name: 'Context7', detail: 'context7.com/wvlet/uni', href: 'https://context7.com/wvlet/uni' }
]

const agentHtml = [
  '<span class="tk-comment"># Add the uni skill to Claude Code</span>',
  '<span class="tk-prompt">&gt;</span> /plugin marketplace add wvlet/uni',
  '<span class="tk-prompt">&gt;</span> /plugin install uni@uni'
].join('\n')
</script>

<template>
  <div class="uni-home">
    <header class="uh-hero">
      <div class="uh-aurora" aria-hidden="true"><span /><span /><span /></div>
      <div class="uh-hero-text">
        <div class="uh-brand">
          <img :src="withBase('/uni-logo.svg')" alt="" width="64" height="64" />
          <span>Uni</span>
        </div>
        <p class="uh-eyebrow">
          <span class="uh-dot" />
          Open source · Apache 2.0 ·
          <a :href="withBase('/guide/for-ai-agents')">Built for AI agents too</a>
        </p>
        <h1 class="uh-title">
          One Scala 3 library<br />
          <em class="uh-shimmer">for the whole stack</em>
        </h1>
        <p class="uh-lede">
          Object wiring, logging, JSON and MessagePack, HTTP and RPC, Parquet and Iceberg on
          object storage, LLM APIs, MCP servers, web UI, and testing, from one dependency with
          minimal transitive ones. The same code runs on the JVM, in Node.js and the browser, and
          as a Scala Native binary.
        </p>
        <div class="uh-ctas">
          <a class="uh-btn uh-btn-solid" :href="withBase('/guide/')">Get started →</a>
          <a class="uh-btn uh-btn-ghost" :href="withBase('/modules')">Browse modules</a>
          <a class="uh-btn uh-btn-ghost" href="https://github.com/wvlet/uni">GitHub</a>
        </div>
        <p class="uh-coming">
          Coming from <a :href="withBase('/guide/for-ai-agents#how-do-i-migrate-from-airframe')">Airframe</a>
          or using the <a :href="withBase('/guide/uni-vs-scala-toolkit')">Scala Toolkit</a>?
        </p>
      </div>

      <div class="uh-hero-visual">
        <div class="uh-glow" aria-hidden="true" />
        <div class="uh-window">
          <div class="uh-bar">
            <i /><i /><i />
            <span class="uh-bar-title">Main.scala</span>
            <span class="uh-badge">scala 3</span>
          </div>
          <pre class="uh-code"><code v-html="heroHtml" /></pre>
        </div>
        <div class="uh-runs" aria-hidden="true"><span>↓</span> runs on JVM · Node.js · Native</div>
        <div class="uh-window uh-output">
          <input id="uh-tab-out" type="radio" name="uh-tab" checked />
          <input id="uh-tab-sbt" type="radio" name="uh-tab" />
          <input id="uh-tab-cli" type="radio" name="uh-tab" />
          <div class="uh-bar">
            <i /><i /><i />
            <div class="uh-tabs">
              <label for="uh-tab-out" class="uh-t-out">Output</label>
              <label for="uh-tab-sbt" class="uh-t-sbt">build.sbt</label>
              <label for="uh-tab-cli" class="uh-t-cli">Scala CLI</label>
            </div>
          </div>
          <div class="uh-panels">
            <pre class="uh-code uh-p-out"><code><span class="tk-prompt">$</span> curl localhost:8080/hello/Uni
<span class="tk-string">{"name":"Uni","message":"Hello, Uni!"}</span>

<span class="tk-comment"># server log</span>
<span class="tk-level">info</span> [GreetingApi] Greeting Uni  <span class="tk-comment">- (Main.scala:16)</span></code></pre>
            <pre class="uh-code uh-p-sbt"><code v-html="sbtHtml" /></pre>
            <pre class="uh-code uh-p-cli"><code v-html="cliHtml" /></pre>
          </div>
        </div>
      </div>
    </header>

    <section class="uh-strip" aria-label="Where Uni runs">
      <div class="uh-strip-group">
        <span class="uh-kicker">Runs on</span>
        <ul>
          <li v-for="p in platforms" :key="p">{{ p }}</li>
        </ul>
      </div>
      <div class="uh-strip-group uh-strip-deps">
        <span class="uh-kicker">Depends on</span>
        <ul>
          <li>Scala 3 standard library <small>on the JVM</small></li>
        </ul>
      </div>
    </section>

    <section class="uh-why">
      <div class="uh-section-head">
        <p class="uh-kicker">Why one library</p>
        <h2>Many libraries, <em class="uh-shimmer">or one Uni</em></h2>
        <p>
          A Scala 3 service needs object wiring, logging, codecs, HTTP, and tests, and usually a
          library for each. Uni ships them together, with one style for you and your AI agent.
        </p>
        <ol class="uh-steps">
          <li v-for="(s, i) in steps" :key="s.title">
            <b>{{ String(i + 1).padStart(2, '0') }}</b>
            <div>
              <h3>{{ s.title }}</h3>
              <p v-html="s.body" />
            </div>
          </li>
        </ol>
        <p class="uh-note">
          Focused libraries and ecosystems such as sttp, circe, ZIO, and MacWire are good choices,
          and Uni works alongside them. Uni fits best when you want these pieces from one place,
          especially when targeting Scala.js or Scala Native.
        </p>
        <p class="uh-readmore">
          <a :href="withBase('/blog/introducing-uni')">Read: Introducing Uni →</a>
        </p>
      </div>
      <figure class="uh-why-visual">
        <div class="uh-window">
          <img
            :src="withBase('/blog/many-libraries-or-one-uni.webp')"
            alt="Many libraries, or one Uni? A tangled tower of separate libraries next to a unicorn carrying all of them in one bag."
            loading="lazy"
          />
        </div>
        <figcaption>
          <a href="https://github.com/wvlet/wvlet">Wvlet</a>, a flow-style query language with its
          own CLI and server, is built on <code>uni</code>, <code>uni-test</code>, and
          <code>uni-netty</code>.
        </figcaption>
      </figure>
    </section>

    <section class="uh-areas">
      <div class="uh-areas-head">
        <p class="uh-kicker">What's inside</p>
        <h2>Everything an app needs, <em class="uh-shimmer">in one dependency</em></h2>
        <p>
          Add <code>"org.wvlet.uni" %% "uni"</code> and every area below is available, except the
          uni-ui components (<code>uni-ui</code>). Each card links to its main pages; <a :href="withBase('/modules')">All Modules</a> lists them all.
        </p>
      </div>
      <div class="uh-grid">
        <article v-for="a in areas" :key="a.name" class="uh-card">
          <h3><a :href="withBase(a.link)">{{ a.name }}</a></h3>
          <p>{{ a.description }}</p>
          <ul class="uh-tags">
            <li v-for="t in a.tags" :key="t.text">
              <a :href="withBase(t.link)" :target="t.self ? '_self' : undefined">{{ t.text }}</a>
            </li>
          </ul>
        </article>
      </div>
      <div class="uh-artifacts">
        <span class="uh-kicker">Artifacts</span>
        <ul>
          <li v-for="a in artifacts" :key="a.name">
            <code>{{ a.name }}</code>
            <span>{{ a.detail }}</span>
            <small>{{ a.platforms }}</small>
          </li>
        </ul>
      </div>
    </section>

    <section class="uh-agents">
      <div class="uh-agents-text">
        <p class="uh-kicker">For AI agents</p>
        <h3>Docs your coding agent can read</h3>
        <p>
          Every page is also published as Markdown. Agents get canonical imports, an Airframe
          migration table, and a list of common mistakes from
          <a :href="withBase('/guide/for-ai-agents')">Uni for AI Agents</a>, and Claude Code can
          install it as a skill.
        </p>
        <ul class="uh-list">
          <li v-for="d in agentDocs" :key="d.name">
            <a :href="d.href"><b>{{ d.name }}</b></a>
            <span>{{ d.detail }}</span>
          </li>
        </ul>
      </div>
      <div class="uh-window">
        <div class="uh-bar">
          <i /><i /><i />
          <span class="uh-bar-title">claude</span>
        </div>
        <pre class="uh-code"><code v-html="agentHtml" /></pre>
      </div>
    </section>

    <section class="uh-closing">
      <div class="uh-closing-panel">
        <img :src="withBase('/uni-logo.svg')" alt="" width="80" height="80" />
        <h2>Build your whole stack <em class="uh-shimmer">in Scala 3</em></h2>
        <div class="uh-ctas">
          <a class="uh-btn uh-btn-solid uh-btn-lg" :href="withBase('/guide/installation')">Install Uni</a>
          <a class="uh-btn uh-btn-ghost uh-btn-lg" :href="withBase('/book/')">Read the Uni Book</a>
          <a class="uh-btn uh-btn-ghost uh-btn-lg" :href="withBase('/gallery/index.html')" target="_self">Open the UI gallery</a>
        </div>
      </div>
    </section>
  </div>
</template>

<style>
.uni-home {
  --uh-bg: var(--vp-c-bg);
  --uh-bg-2: var(--vp-c-bg-soft);
  --uh-card: var(--vp-c-bg);
  --uh-ink: var(--vp-c-text-1);
  --uh-ink-2: var(--vp-c-text-2);
  --uh-line: var(--vp-c-divider);
  --uh-primary: var(--vp-c-brand-1);
  --uh-glow-1: hsl(186 90% 52% / 0.22);
  --uh-glow-2: hsl(192 75% 45% / 0.12);
  --uh-glow-3: hsl(198 90% 60% / 0.12);
  --uh-grid: hsl(190 30% 20% / 0.055);
  --uh-shine-1: 192 92% 22%;
  --uh-shine-2: 186 100% 38%;
  --uh-shine-3: 198 70% 34%;
  --uh-term: #0f1c20;
  --uh-radius: 18px;
  --uh-shadow: 0 30px 80px -30px hsl(196 60% 15% / 0.35), 0 8px 24px -12px hsl(196 60% 15% / 0.2);
  --uh-mono: var(--vp-font-family-mono);

  color: var(--uh-ink);
  background: var(--uh-bg);
  overflow-x: clip;
}

.dark .uni-home {
  --uh-card: var(--vp-c-bg-soft);
  --uh-bg-2: var(--vp-c-bg-alt);
  --uh-glow-1: hsl(186 100% 46% / 0.2);
  --uh-glow-2: hsl(186 92% 24% / 0.4);
  --uh-glow-3: hsl(186 100% 62% / 0.09);
  --uh-grid: hsl(186 100% 70% / 0.05);
  --uh-shine-1: 186 100% 46%;
  --uh-shine-2: 186 100% 74%;
  --uh-shine-3: 186 92% 40%;
  --uh-term: #071215;
  --uh-shadow: 0 30px 80px -30px #000000b3, 0 8px 24px -12px #00000080;
}

.uni-home h1,
.uni-home h2,
.uni-home h3 {
  margin: 0;
  border: 0;
  padding: 0;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.08;
  color: var(--uh-ink);
}

.uni-home em {
  font-style: normal;
}

.uni-home a {
  color: var(--uh-ink);
  text-decoration: underline;
  text-decoration-color: color-mix(in srgb, var(--uh-primary) 45%, transparent);
  text-underline-offset: 3px;
}

.uni-home a:hover {
  text-decoration-color: var(--uh-primary);
}

.uni-home p code,
.uni-home li code,
.uni-home figcaption code {
  padding: 1px 6px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--uh-primary) 12%, transparent);
  font-family: var(--uh-mono);
  font-size: 0.86em;
}

.uh-kicker {
  margin: 0 0 16px;
  color: var(--uh-primary);
  font: 500 13px var(--uh-mono);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.uh-shimmer {
  background: linear-gradient(
    100deg,
    hsl(var(--uh-shine-1)),
    hsl(var(--uh-shine-2)) 25%,
    hsl(var(--uh-shine-3)) 50%,
    hsl(var(--uh-shine-2)) 75%,
    hsl(var(--uh-shine-1))
  );
  background-size: 200% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  animation: uh-shimmer 8s linear infinite;
}

@keyframes uh-shimmer {
  to { background-position: -200% 0; }
}

/* ---------- Buttons ---------- */

.uh-ctas {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.uni-home .uh-btn {
  display: inline-flex;
  align-items: center;
  padding: 12px 22px;
  border: 1px solid transparent;
  border-radius: 999px;
  font-size: 15px;
  font-weight: 600;
  line-height: 1;
  white-space: nowrap;
  text-decoration: none;
  transition: transform 0.2s, box-shadow 0.2s;
}

.uni-home .uh-btn:hover { transform: translateY(-1px); }
.uni-home .uh-btn:focus-visible { outline: 2px solid var(--uh-primary); outline-offset: 3px; }

.uni-home .uh-btn-solid {
  background: var(--uh-ink);
  color: var(--uh-bg);
  box-shadow: 0 10px 30px -12px color-mix(in srgb, var(--uh-ink) 60%, transparent);
}

.uni-home .uh-btn-ghost {
  border-color: var(--uh-line);
  background: color-mix(in srgb, var(--uh-card) 60%, transparent);
  backdrop-filter: blur(8px);
  color: var(--uh-ink);
}

.uni-home .uh-btn-lg { padding: 16px 28px; font-size: 16px; }

/* ---------- Hero ---------- */

.uh-hero {
  position: relative;
  isolation: isolate;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr);
  align-items: center;
  gap: 48px;
  max-width: 1320px;
  margin: 0 auto;
  padding: 64px clamp(16px, 5vw, 72px) 96px;
}

.uh-aurora {
  position: absolute;
  inset: 0 -10%;
  z-index: -1;
  overflow: hidden;
  pointer-events: none;
}

/* Hairline grid, faded toward the edges so it reads as texture */
.uh-aurora::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(to right, var(--uh-grid) 1px, transparent 1px),
    linear-gradient(to bottom, var(--uh-grid) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse 60% 70% at 60% 30%, #000 20%, transparent 75%);
  -webkit-mask-image: radial-gradient(ellipse 60% 70% at 60% 30%, #000 20%, transparent 75%);
}

.uh-aurora::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(to bottom, transparent 70%, var(--uh-bg));
}

.uh-aurora span {
  position: absolute;
  border-radius: 50%;
  animation: uh-drift 30s ease-in-out infinite alternate;
}

.uh-aurora span:nth-child(1) {
  width: 62vmax;
  height: 50vmax;
  top: -14%;
  right: 2%;
  background: radial-gradient(closest-side, var(--uh-glow-1), transparent);
}

.uh-aurora span:nth-child(2) {
  width: 48vmax;
  height: 40vmax;
  bottom: -20%;
  left: 0;
  background: radial-gradient(closest-side, var(--uh-glow-2), transparent);
  animation-delay: -10s;
}

.uh-aurora span:nth-child(3) {
  width: 30vmax;
  height: 24vmax;
  top: -12%;
  left: 18%;
  background: radial-gradient(closest-side, var(--uh-glow-3), transparent);
  animation-delay: -20s;
}

@keyframes uh-drift {
  to { transform: translate(3vmax, -2vmax) scale(1.06); }
}

.uh-brand {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 24px;
  font-size: 36px;
  font-weight: 700;
  letter-spacing: -0.03em;
}

.uh-brand img {
  filter: drop-shadow(0 14px 24px hsl(190 80% 30% / 0.3));
}

.uh-eyebrow {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 0 0 24px;
  padding: 7px 14px;
  border: 1px solid var(--uh-line);
  border-radius: 999px;
  background: color-mix(in srgb, var(--uh-card) 60%, transparent);
  backdrop-filter: blur(8px);
  color: var(--uh-ink-2);
  font-size: 13px;
  font-weight: 500;
}

.uni-home .uh-eyebrow a { font-weight: 600; }

.uh-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: hsl(var(--uh-shine-2));
  box-shadow: 0 0 0 4px hsl(var(--uh-shine-2) / 0.22);
}

.uni-home .uh-title {
  font-size: clamp(38px, 4.4vw, 60px);
  letter-spacing: -0.04em;
}

.uh-title em { display: inline-block; }

.uh-section-head h2 em,
.uh-areas-head h2 em { white-space: nowrap; }

.uh-lede {
  max-width: 34em;
  margin: 24px 0 32px;
  color: var(--uh-ink-2);
  font-size: clamp(16px, 1.3vw, 18px);
  line-height: 1.65;
}

.uh-coming {
  margin: 20px 0 0;
  color: var(--uh-ink-2);
  font-size: 14px;
}

.uh-hero-visual {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.uh-glow {
  position: absolute;
  top: 50%;
  left: 50%;
  z-index: -1;
  width: 80%;
  aspect-ratio: 1;
  translate: -50% -50%;
  border-radius: 50%;
  background: radial-gradient(closest-side, var(--uh-glow-1), transparent);
}

.uh-runs {
  align-self: center;
  display: inline-flex;
  gap: 8px;
  padding: 4px 12px;
  border: 1px solid var(--uh-line);
  border-radius: 999px;
  background: color-mix(in srgb, var(--uh-card) 70%, transparent);
  color: var(--uh-primary);
  font: 500 12px var(--uh-mono);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

/* ---------- Window frame ---------- */

.uh-window {
  overflow: hidden;
  border: 1px solid var(--uh-line);
  border-radius: var(--uh-radius);
  background: var(--uh-term);
  box-shadow: var(--uh-shadow);
}

.uh-window img {
  display: block;
  width: 100%;
}

.uh-bar {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 11px 14px;
  border-bottom: 1px solid hsl(0 0% 100% / 0.07);
  background: hsl(0 0% 100% / 0.03);
}

.uh-bar i {
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: hsl(0 0% 100% / 0.16);
}

.uh-bar-title {
  margin-left: 8px;
  color: hsl(190 15% 75%);
  font: 500 12px var(--uh-mono);
}

.uh-badge {
  margin-left: auto;
  padding: 3px 10px;
  border-radius: 999px;
  background: hsl(186 100% 42% / 0.16);
  color: hsl(186 85% 66%);
  font: 500 11px var(--uh-mono);
}

.uni-home .uh-code {
  margin: 0;
  padding: 16px 18px;
  overflow-x: auto;
  background: transparent;
  color: hsl(190 20% 88%);
  font: 13px/1.65 var(--uh-mono);
  white-space: pre;
}

.uni-home .uh-code code {
  padding: 0;
  background: none;
  font: inherit;
  color: inherit;
}

.uh-code .tk-keyword { color: #5ccfe6; }
.uh-code .tk-type { color: #ffcb6b; }
.uh-code .tk-string { color: #c3e88d; }
.uh-code .tk-annotation { color: #c792ea; }
.uh-code .tk-number { color: #f78c6c; }
.uh-code .tk-op { color: #89ddff; }
.uh-code .tk-comment { color: #7c889b; }
.uh-code .tk-prompt { color: #5ccfe6; }
.uh-code .tk-level { color: #5ccfe6; }

/* Tabs are CSS-only radio inputs, so the page needs no client state */
.uh-output > input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.uh-tabs {
  display: flex;
  gap: 4px;
  margin-left: 8px;
}

.uh-tabs label {
  padding: 3px 10px;
  border-radius: 6px;
  color: hsl(190 15% 62%);
  font: 500 12px var(--uh-mono);
  white-space: nowrap;
  cursor: pointer;
}

.uh-tabs label:hover { color: hsl(190 20% 85%); }

#uh-tab-out:checked ~ .uh-bar .uh-t-out,
#uh-tab-sbt:checked ~ .uh-bar .uh-t-sbt,
#uh-tab-cli:checked ~ .uh-bar .uh-t-cli {
  background: hsl(0 0% 100% / 0.08);
  color: hsl(190 25% 92%);
}

#uh-tab-out:focus-visible ~ .uh-bar .uh-t-out,
#uh-tab-sbt:focus-visible ~ .uh-bar .uh-t-sbt,
#uh-tab-cli:focus-visible ~ .uh-bar .uh-t-cli {
  outline: 2px solid hsl(186 85% 60%);
  outline-offset: 1px;
}

/* Same height for every tab, so switching does not shift the layout */
.uh-panels { min-height: 150px; }

/* Wrap long dependency lines instead of hiding their ends */
.uni-home .uh-panels .uh-code { white-space: pre-wrap; }

.uh-p-sbt,
.uh-p-cli,
#uh-tab-sbt:checked ~ .uh-panels .uh-p-out,
#uh-tab-cli:checked ~ .uh-panels .uh-p-out {
  display: none;
}

#uh-tab-sbt:checked ~ .uh-panels .uh-p-sbt,
#uh-tab-cli:checked ~ .uh-panels .uh-p-cli {
  display: block;
}

/* ---------- Platform strip ---------- */

.uh-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 14px 28px;
  max-width: 1320px;
  margin: 0 auto;
  padding: 28px clamp(16px, 5vw, 72px);
  border-top: 1px solid var(--uh-line);
  border-bottom: 1px solid var(--uh-line);
}

.uh-strip-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 12px 24px;
}

.uh-strip-deps {
  padding-left: 36px;
  border-left: 1px solid var(--uh-line);
}

.uh-strip .uh-kicker { margin: 0; }

.uh-strip ul {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px 32px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.uh-strip li {
  color: var(--uh-ink-2);
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.uh-strip small {
  margin-left: 8px;
  color: var(--uh-primary);
  font: 500 12px var(--uh-mono);
}

/* ---------- Why ---------- */

.uh-why {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: 56px;
  max-width: 1320px;
  margin: 0 auto;
  padding: 112px clamp(16px, 5vw, 72px) 72px;
}

.uni-home .uh-section-head h2,
.uni-home .uh-areas-head h2 {
  font-size: clamp(34px, 4vw, 54px);
}

.uh-section-head h2 + p,
.uh-areas-head h2 + p {
  max-width: 38em;
  margin: 22px 0 0;
  color: var(--uh-ink-2);
  font-size: 18px;
  line-height: 1.6;
}

.uh-steps {
  display: grid;
  gap: 20px;
  margin: 32px 0 0;
  padding: 0;
  list-style: none;
}

.uh-steps li {
  display: flex;
  gap: 18px;
}

.uh-steps b {
  color: var(--uh-primary);
  font: 500 13px/1.9 var(--uh-mono);
}

.uni-home .uh-steps h3 {
  font-size: 19px;
  letter-spacing: -0.02em;
}

.uh-steps p {
  margin: 6px 0 0;
  color: var(--uh-ink-2);
  line-height: 1.6;
}

.uh-note {
  margin: 28px 0 0;
  padding-left: 16px;
  border-left: 3px solid hsl(var(--uh-shine-2));
  color: var(--uh-ink-2);
  font-size: 15px;
  line-height: 1.6;
}

.uh-readmore {
  margin: 24px 0 0;
  font-weight: 600;
}

.uh-why-visual {
  margin: 0;
}

.uh-why-visual figcaption {
  margin-top: 18px;
  color: var(--uh-ink-2);
  font-size: 15px;
  line-height: 1.6;
}

/* ---------- Module areas ---------- */

.uh-areas {
  max-width: 1320px;
  margin: 0 auto;
  padding: 40px clamp(16px, 5vw, 72px) 72px;
}

.uh-areas-head { margin-bottom: 40px; }

.uh-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.uh-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 26px 24px 22px;
  border: 1px solid var(--uh-line);
  border-radius: var(--uh-radius);
  background:
    radial-gradient(120% 80% at 0% 0%, hsl(186 90% 50% / 0.07), transparent 60%),
    var(--uh-card);
  transition: transform 0.25s, box-shadow 0.25s, border-color 0.25s;
}

.uh-card:hover {
  transform: translateY(-3px);
  border-color: color-mix(in srgb, var(--uh-primary) 40%, transparent);
  box-shadow: var(--uh-shadow);
}

.uni-home .uh-card h3 { font-size: 21px; letter-spacing: -0.02em; }
.uni-home .uh-card h3 a { text-decoration: none; }

.uh-card p {
  flex: 1;
  margin: 0;
  color: var(--uh-ink-2);
  line-height: 1.6;
}

.uh-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.uni-home .uh-tags a {
  display: inline-block;
  padding: 3px 9px;
  border: 1px solid var(--uh-line);
  border-radius: 999px;
  color: var(--uh-primary);
  font: 500 12px var(--uh-mono);
  text-decoration: none;
}

.uni-home .uh-tags a:hover {
  border-color: var(--uh-primary);
}

.uh-artifacts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 24px;
  margin-top: 28px;
}

.uh-artifacts .uh-kicker { margin: 0; }

.uh-artifacts ul {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.uh-artifacts li {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 10px;
  padding: 8px 14px;
  border: 1px solid var(--uh-line);
  border-radius: 12px;
  background: var(--uh-card);
  font-size: 14px;
}

.uni-home .uh-artifacts code {
  padding: 0;
  background: none;
  color: var(--uh-primary);
  font-weight: 600;
}

.uh-artifacts span { color: var(--uh-ink-2); }

.uh-artifacts small {
  color: var(--uh-ink-2);
  font: 500 11px var(--uh-mono);
  opacity: 0.8;
}

/* ---------- AI agents ---------- */

.uh-agents {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: 48px;
  max-width: calc(1320px - 2 * clamp(16px, 5vw, 72px));
  margin: 24px auto 0;
  padding: 40px;
  border: 1px solid var(--uh-line);
  border-radius: calc(var(--uh-radius) + 6px);
  background:
    radial-gradient(80% 100% at 100% 0%, hsl(186 90% 50% / 0.08), transparent 60%),
    var(--uh-bg-2);
}

.uni-home .uh-agents-text h3 {
  font-size: clamp(24px, 2.4vw, 32px);
  letter-spacing: -0.02em;
}

.uh-agents-text > p:not(.uh-kicker) {
  margin: 14px 0 0;
  color: var(--uh-ink-2);
  line-height: 1.6;
}

.uh-list {
  display: grid;
  gap: 10px;
  margin: 24px 0 0;
  padding: 0;
  list-style: none;
}

.uh-list li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: 4px 16px;
  padding: 10px 14px;
  border: 1px solid var(--uh-line);
  border-radius: 12px;
  background: var(--uh-card);
}

.uh-list span {
  color: var(--uh-ink-2);
  font: 500 12.5px var(--uh-mono);
}

/* ---------- Closing ---------- */

.uh-closing {
  padding: 96px clamp(16px, 5vw, 72px) 32px;
}

.uh-closing-panel {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 28px;
  max-width: 1176px;
  margin: 0 auto;
  padding: 80px 24px 88px;
  border: 1px solid var(--uh-line);
  border-radius: 28px;
  background:
    radial-gradient(ellipse 90% 70% at 50% 100%, var(--uh-glow-1), transparent 75%),
    radial-gradient(ellipse 60% 45% at 50% 0%, var(--uh-glow-3), transparent 75%),
    linear-gradient(to bottom, var(--uh-card), var(--uh-bg-2));
  text-align: center;
}

.uh-closing-panel::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: -1;
  background-image:
    linear-gradient(to right, var(--uh-grid) 1px, transparent 1px),
    linear-gradient(to bottom, var(--uh-grid) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(ellipse 70% 80% at 50% 45%, #000 10%, transparent 70%);
  -webkit-mask-image: radial-gradient(ellipse 70% 80% at 50% 45%, #000 10%, transparent 70%);
}

.uni-home .uh-closing h2 { font-size: clamp(32px, 4.2vw, 56px); }
.uh-closing-panel .uh-ctas { justify-content: center; }

/* ---------- Responsive & motion ---------- */

@media (max-width: 1100px) {
  .uh-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 960px) {
  .uh-hero,
  .uh-why,
  .uh-agents {
    grid-template-columns: minmax(0, 1fr);
    gap: 40px;
  }
  .uh-hero { padding-top: 40px; padding-bottom: 64px; }
  .uh-why { padding-top: 72px; }
  .uh-agents { margin: 24px 16px 0; padding: 24px 16px; }
  .uh-strip-deps { padding-left: 0; border-left: 0; }
}

@media (max-width: 640px) {
  .uh-grid { grid-template-columns: minmax(0, 1fr); }
  .uni-home .uh-code { font-size: 12px; }
  .uh-strip li { font-size: 17px; }
}

@media (prefers-reduced-motion: reduce) {
  .uh-aurora span,
  .uh-shimmer {
    animation: none;
  }
}
</style>
