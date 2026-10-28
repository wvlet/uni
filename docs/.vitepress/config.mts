import { defineConfig, type DefaultTheme } from 'vitepress'
import { withMermaid } from 'vitepress-plugin-mermaid'
import llmstxt from 'vitepress-plugin-llms'
import { fetchLatestVersion } from './fetchLatestVersion'
import { readdirSync, readFileSync } from 'node:fs'
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FALLBACK_VERSION = '2026.1.22'
const VERSION_TOKEN = '__UNI_VERSION__'
const uniVersion = await fetchLatestVersion(FALLBACK_VERSION)

// The Scala version uni is built with (SCALA_3 in the root build.sbt). uni's artifacts need a
// compiler of at least that minor, so samples must not drift behind it.
const SCALA_VERSION_TOKEN = '__SCALA_VERSION__'
const scalaVersion = (() => {
  const rootBuild = readFileSync(fileURLToPath(new URL('../../build.sbt', import.meta.url)), 'utf8')
  const m = /val SCALA_3\s*=\s*"([^"]+)"/.exec(rootBuild)
  if (!m) throw new Error('Could not find `val SCALA_3 = "..."` in the root build.sbt')
  return m[1]
})()

const replaceVersionTokens = (text: string): string =>
  text.split(VERSION_TOKEN).join(uniVersion).split(SCALA_VERSION_TOKEN).join(scalaVersion)

const SITE_URL = 'https://wvlet.org/uni/'
const SITE_TITLE = 'Uni - One Scala 3 Library for the Whole Stack'
// Keep in sync with the hero and `description` in docs/index.md and the README intro
const SITE_DESCRIPTION =
  'Uni is the Scala 3 successor of Airframe: object wiring, logging, JSON/MessagePack, Rx, HTTP, RPC, MCP servers, and testing with minimal dependencies, on JVM, Scala.js, and Scala Native.'

// Structured data so search engines and AI answer engines can identify Uni as a software library.
const structuredData = {
  '@context': 'https://schema.org',
  '@type': 'SoftwareSourceCode',
  name: 'Uni',
  description: SITE_DESCRIPTION,
  url: SITE_URL,
  codeRepository: 'https://github.com/wvlet/uni',
  programmingLanguage: 'Scala',
  runtimePlatform: ['JVM', 'Scala.js', 'Scala Native'],
  license: 'https://www.apache.org/licenses/LICENSE-2.0',
  version: uniVersion,
  identifier: `org.wvlet.uni:uni_3:${uniVersion}`,
  author: { '@type': 'Organization', name: 'wvlet', url: 'https://github.com/wvlet' }
}

// Blog posts are docs/blog/YYYY-MM-DD-slug.md with title/date/author/description frontmatter.
// The rewrite serves them at /blog/slug, so shared links stay short and stable.
const BLOG_POST_SOURCE = /^blog\/(\d{4}-\d{2}-\d{2})-(.+)\.md$/
const blogDir = fileURLToPath(new URL('../blog/', import.meta.url))

interface BlogPost {
  slug: string
  title: string
  date: string
  author: string
  description: string
  image?: string
}

// Reads the flat `key: value` frontmatter of the posts; used for the sidebar and the Atom feed
const blogPosts: BlogPost[] = readdirSync(blogDir)
  .map((f) => BLOG_POST_SOURCE.exec(`blog/${f}`))
  .filter((m): m is RegExpExecArray => m !== null)
  .map((m) => {
    const text = readFileSync(join(blogDir, `${m[1]}-${m[2]}.md`), 'utf8')
    const fm: Record<string, string> = {}
    for (const line of (/^---\n([\s\S]*?)\n---/.exec(text)?.[1] ?? '').split('\n')) {
      const kv = /^(\w+):\s*(.*)$/.exec(line)
      if (kv) fm[kv[1]] = kv[2].replace(/^"(.*)"$/, '$1').replace(/\\"/g, '"')
    }
    return {
      slug: m[2],
      title: fm.title,
      date: fm.date ?? m[1],
      author: fm.author,
      description: fm.description,
      image: fm.image
    }
  })
  .sort((a, b) => b.date.localeCompare(a.date))

const escapeXml = (s: string): string =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

const atomFeed = (): string => {
  const entries = blogPosts.map((p) => `  <entry>
    <title>${escapeXml(p.title)}</title>
    <link href="${SITE_URL}blog/${p.slug}"/>
    <id>${SITE_URL}blog/${p.slug}</id>
    <published>${p.date}T00:00:00Z</published>
    <updated>${p.date}T00:00:00Z</updated>
    <author><name>${escapeXml(p.author)}</name></author>
    <summary>${escapeXml(p.description)}</summary>
  </entry>`)
  return `<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Uni Blog</title>
  <link href="${SITE_URL}blog/"/>
  <link rel="self" href="${SITE_URL}blog/feed.xml"/>
  <id>${SITE_URL}blog/</id>
  <updated>${blogPosts[0]?.date ?? '1970-01-01'}T00:00:00Z</updated>
${entries.join('\n')}
</feed>
`
}

interface UniThemeConfig extends DefaultTheme.Config {
  uniVersion: string
}

export default withMermaid(defineConfig<UniThemeConfig>({
  title: 'Uni',
  description: SITE_DESCRIPTION,

  base: '/uni/',
  cleanUrls: true,
  rewrites: (page) => page.replace(BLOG_POST_SOURCE, 'blog/$2.md'),
  lastUpdated: true,

  sitemap: {
    hostname: SITE_URL
  },

  vite: {
    // Publish llms.txt, llms-full.txt, and a Markdown copy of each page so AI agents can read the docs directly
    plugins: [
      llmstxt({
        domain: 'https://wvlet.org',
        description: 'One Scala 3 library for the whole stack, on JVM, Scala.js, and Scala Native',
        details: SITE_DESCRIPTION +
          ' Start with /uni/guide/for-ai-agents.md for canonical imports, an Airframe migration table, and common mistakes.',
        // The `/guide/` and `/` sidebars are identical; list them once so the llms.txt TOC has no duplicates
        sidebar: (sidebar) =>
          sidebar && !Array.isArray(sidebar)
            ? [...sidebar['/book/'], ...sidebar['/'], ...sidebar['/blog/']]
            : sidebar,
        // Publish blog posts (the plugin skips blog/* by default); the index is a Vue-rendered list
        excludeBlog: false,
        ignoreFiles: ['blog/index.md'],
        // Publish the home page as /uni/index.md and in llms-full.txt; its body is the answer-first summary
        excludeIndexPage: false
      })
    ]
  },

  // The llms plugin copies Markdown sources verbatim, so resolve the version tokens in its output too
  async buildEnd(siteConfig) {
    await writeFile(join(siteConfig.outDir, 'blog', 'feed.xml'), atomFeed())
    const files = await readdir(siteConfig.outDir, { recursive: true })
    await Promise.all(
      files
        .filter((f) => f.endsWith('.md') || f.endsWith('.txt'))
        .map(async (f) => {
          const path = join(siteConfig.outDir, f)
          const text = await readFile(path, 'utf8')
          const replaced = replaceVersionTokens(text)
          if (replaced !== text) await writeFile(path, replaced)
        })
    )
  },

  // Per-page canonical URL; frontmatter `description` already becomes <meta name="description">
  transformPageData(pageData) {
    const path = pageData.relativePath.replace(/(^|\/)index\.md$/, '$1').replace(/\.md$/, '')
    pageData.frontmatter.head ??= []
    pageData.frontmatter.head.push(['link', { rel: 'canonical', href: `${SITE_URL}${path}` }])

    // Blog posts: article metadata, a per-post share image, and BlogPosting structured data.
    // mergeHead lets page meta tags replace site-wide ones with the same name or property.
    const post = blogPosts.find((p) => `blog/${p.slug}` === path)
    if (post) {
      const url = `${SITE_URL}${path}`
      const image = post.image ? `${SITE_URL}${post.image.replace(/^\//, '')}` : `${SITE_URL}uni-banner-1280x640.png`
      pageData.frontmatter.head.push(
        ['meta', { property: 'og:type', content: 'article' }],
        ['meta', { property: 'og:title', content: post.title }],
        ['meta', { property: 'og:description', content: post.description }],
        ['meta', { property: 'og:url', content: url }],
        ['meta', { property: 'og:image', content: image }],
        ['meta', { property: 'article:published_time', content: post.date }],
        ['meta', { name: 'twitter:title', content: post.title }],
        ['meta', { name: 'twitter:description', content: post.description }],
        ['meta', { name: 'twitter:image', content: image }],
        [
          'script',
          { type: 'application/ld+json' },
          JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'BlogPosting',
            headline: post.title,
            description: post.description,
            datePublished: post.date,
            author: { '@type': 'Person', name: post.author },
            publisher: structuredData.author,
            image,
            url,
            mainEntityOfPage: url,
            about: { '@type': 'SoftwareSourceCode', name: 'Uni', codeRepository: structuredData.codeRepository }
          })
        ]
      )
    }
  },

  // Exclude contributor-only files from the rendered site.
  srcExclude: ['**/AGENTS.md', 'snippets/**'],

  markdown: {
    theme: {
      light: 'nord',
      dark: 'nord'
    },
    config(md) {
      const replace = replaceVersionTokens
      for (const rule of ['fence', 'code_inline'] as const) {
        const original = md.renderer.rules[rule]
        md.renderer.rules[rule] = (tokens, idx, options, env, self) => {
          tokens[idx].content = replace(tokens[idx].content)
          return original
            ? original(tokens, idx, options, env, self)
            : self.renderToken(tokens, idx, options)
        }
      }
    }
  },

  head: [
    ['link', { rel: 'icon', href: '/uni/favicon.ico', sizes: 'any' }],
    ['link', { rel: 'icon', href: '/uni/favicon.svg', type: 'image/svg+xml' }],
    ['link', { rel: 'alternate', type: 'application/atom+xml', title: 'Uni Blog', href: `${SITE_URL}blog/feed.xml` }],
    // Google Analytics
    ['script', { async: '', src: 'https://www.googletagmanager.com/gtag/js?id=G-55G099WF5N' }],
    [
      'script',
      {},
      `window.dataLayer = window.dataLayer || [];
      function gtag(){dataLayer.push(arguments);}
      gtag('js', new Date());
      gtag('config', 'G-55G099WF5N');`
    ],
    ['script', { type: 'application/ld+json' }, JSON.stringify(structuredData)],
    // Open Graph
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: SITE_TITLE }],
    ['meta', { property: 'og:description', content: SITE_DESCRIPTION }],
    ['meta', { property: 'og:image', content: 'https://wvlet.org/uni/uni-banner-1280x640.png' }],
    ['meta', { property: 'og:url', content: 'https://wvlet.org/uni/' }],
    // Twitter Card
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: SITE_TITLE }],
    ['meta', { name: 'twitter:description', content: SITE_DESCRIPTION }],
    ['meta', { name: 'twitter:image', content: 'https://wvlet.org/uni/uni-banner-1280x640.png' }]
  ],

  themeConfig: {
    uniVersion,
    logo: '/uni-logo.svg',
    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'For AI Agents', link: '/guide/for-ai-agents' },
      { text: 'Book', link: '/book/' },
      { text: 'Modules', link: '/core/' },
      { text: 'Blog', link: '/blog/' },
      {
        text: 'GitHub',
        link: 'https://github.com/wvlet/uni'
      }
    ],

    sidebar: {
      '/book/': [
        {
          text: 'The Uni Book',
          items: [
            { text: 'Overview', link: '/book/' },
            { text: 'Foreword', link: '/book/foreword' }
          ]
        },
        {
          text: 'Part I — Getting Started',
          items: [
            { text: '1. Getting Started', link: '/book/ch01-00-getting-started' },
            { text: '1.1 Installation', link: '/book/ch01-01-installation' },
            { text: '1.2 Hello, Uni!', link: '/book/ch01-02-hello-uni' }
          ]
        },
        {
          text: 'Part II — Building a CLI Application',
          items: [
            { text: '2. A URL Fetcher', link: '/book/ch02-00-cli-app' }
          ]
        },
        {
          text: 'Part III — Core Concepts',
          items: [
            { text: '3. Wiring with Design', link: '/book/ch03-00-design' },
            { text: '4. Testing with UniTest', link: '/book/ch04-00-testing' },
            { text: '5. Logging That Finds You', link: '/book/ch05-00-logging' },
            { text: '6. JSON & MessagePack', link: '/book/ch06-00-data' }
          ]
        },
        {
          text: 'Part IV — Async & Control Flow',
          items: [
            { text: '7. Rx, the Composable Stream', link: '/book/ch07-00-rx' },
            { text: '8. Retry, Circuit Breakers, Resources', link: '/book/ch08-00-control' }
          ]
        },
        {
          text: 'Part V — HTTP & RPC',
          items: [
            { text: '9. HTTP Clients and Servers', link: '/book/ch09-00-http' },
            { text: '10. Typed RPC', link: '/book/ch10-00-rpc' }
          ]
        },
        {
          text: 'Part VI — Cross-Platform',
          items: [
            { text: '11. One Codebase, Three Runtimes', link: '/book/ch11-00-cross-platform' }
          ]
        },
        {
          text: 'Part VII — Scala.js & the JS Ecosystem',
          items: [
            { text: '12. Calling NPM Modules', link: '/book/ch12-00-npm-facades' },
            { text: '13. Bundling with Vite', link: '/book/ch13-00-vite' }
          ]
        },
        {
          text: 'Part VIII — Scala Native & the C/Rust World',
          items: [
            { text: '14. Calling C from Scala Native', link: '/book/ch14-00-calling-c' },
            { text: '15. Exposing Scala Native to C & Rust', link: '/book/ch15-00-exposing-native' }
          ]
        },
        {
          text: 'Part IX — Capstone',
          items: [
            { text: '16. A Bookmarks Service', link: '/book/ch16-00-capstone' }
          ]
        },
        {
          text: 'Appendices',
          items: [
            { text: 'A. Scala 3 Syntax Notes', link: '/book/appendix-a-scala3' },
            { text: 'B. Uni and Airframe', link: '/book/appendix-b-airframe' },
            { text: 'C. Glossary', link: '/book/appendix-c-glossary' }
          ]
        }
      ],
      '/blog/': [
        {
          text: 'Blog',
          items: [
            { text: 'All Posts', link: '/blog/' },
            ...blogPosts.map((p) => ({ text: p.title, link: `/blog/${p.slug}` }))
          ]
        }
      ],
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/' },
            { text: 'Installation', link: '/guide/installation' },
            { text: 'Design Principles', link: '/guide/principles' },
            { text: 'For AI Agents', link: '/guide/for-ai-agents' },
            { text: 'Uni and the Scala Toolkit', link: '/guide/uni-vs-scala-toolkit' }
          ]
        },
        {
          text: 'Core',
          items: [
            { text: 'Overview', link: '/core/' },
            { text: 'Design', link: '/core/design' },
            { text: 'UniTest', link: '/core/unitest' },
            { text: 'Logging', link: '/core/logging' },
            { text: 'JSON Processing', link: '/core/json' },
            { text: 'MessagePack', link: '/core/msgpack' },
            { text: 'Weaver (Serialization)', link: '/core/weaver' },
            { text: 'Type Introspection', link: '/core/surface' },
            { text: 'IO', link: '/core/io' },
            { text: 'FileSystem', link: '/core/filesystem' },
            { text: 'SettingStore', link: '/core/settings' },
            { text: 'Result', link: '/core/result' },
            { text: 'Utilities', link: '/core/utilities' }
          ]
        },
        {
          text: 'Text Formats',
          items: [
            { text: 'Overview', link: '/text/' },
            { text: 'YAML', link: '/text/yaml' },
            { text: 'Markdown', link: '/text/markdown' }
          ]
        },
        {
          text: 'Control Flow',
          items: [
            { text: 'Overview', link: '/control/' },
            { text: 'Retry Logic', link: '/control/retry' },
            { text: 'Circuit Breaker', link: '/control/circuit-breaker' },
            { text: 'Rate Limiter', link: '/control/rate-limiter' },
            { text: 'Caching', link: '/control/cache' },
            { text: 'Resource Management', link: '/control/resource' },
            { text: 'Background Task', link: '/control/background-task' }
          ]
        },
        {
          text: 'HTTP',
          items: [
            { text: 'Overview', link: '/http/' },
            { text: 'HTTP Client', link: '/http/client' },
            { text: 'REST Server', link: '/http/server' },
            { text: 'Router', link: '/http/router' },
            { text: 'RPC', link: '/http/rpc' },
            { text: 'OpenAPI', link: '/http/openapi' },
            { text: 'Desktop Apps (Electron)', link: '/http/electron' },
            { text: 'Electron Tutorial', link: '/http/electron-tutorial' },
            { text: 'Server-Sent Events', link: '/http/sse' },
            { text: 'WebSocket Client', link: '/http/websocket' },
            { text: 'Retry Strategies', link: '/http/retry' }
          ]
        },
        {
          text: 'MCP',
          items: [
            { text: 'Build an MCP Server', link: '/mcp/' }
          ]
        },
        {
          text: 'Reactive Streams',
          items: [
            { text: 'Overview', link: '/rx/' },
            { text: 'Basics', link: '/rx/basics' },
            { text: 'Operators', link: '/rx/operators' },
            { text: 'Concurrency', link: '/rx/concurrency' }
          ]
        },
        {
          text: 'CLI',
          items: [
            { text: 'Overview', link: '/cli/' },
            { text: 'Terminal Styling', link: '/cli/tint' },
            { text: 'Progress Indicators', link: '/cli/progress' },
            { text: 'Command Launcher', link: '/cli/launcher' }
          ]
        },
        {
          text: 'Web UI (Scala.js)',
          items: [
            { text: 'RxElement', link: '/dom/' }
          ]
        },
        {
          text: 'Build Tooling',
          items: [
            { text: 'sbt-uni Plugin', link: '/build/sbt-uni' },
            { text: 'sbt-uni-playwright Plugin', link: '/build/sbt-uni-playwright' },
            { text: 'sbt-uni-crossproject Plugin', link: '/build/sbt-uni-crossproject' }
          ]
        }
      ],
      '/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/' },
            { text: 'Installation', link: '/guide/installation' },
            { text: 'Design Principles', link: '/guide/principles' },
            { text: 'For AI Agents', link: '/guide/for-ai-agents' },
            { text: 'Uni and the Scala Toolkit', link: '/guide/uni-vs-scala-toolkit' }
          ]
        },
        {
          text: 'Core',
          items: [
            { text: 'Overview', link: '/core/' },
            { text: 'Design', link: '/core/design' },
            { text: 'UniTest', link: '/core/unitest' },
            { text: 'Logging', link: '/core/logging' },
            { text: 'JSON Processing', link: '/core/json' },
            { text: 'MessagePack', link: '/core/msgpack' },
            { text: 'Weaver (Serialization)', link: '/core/weaver' },
            { text: 'Type Introspection', link: '/core/surface' },
            { text: 'IO', link: '/core/io' },
            { text: 'FileSystem', link: '/core/filesystem' },
            { text: 'SettingStore', link: '/core/settings' },
            { text: 'Result', link: '/core/result' },
            { text: 'Utilities', link: '/core/utilities' }
          ]
        },
        {
          text: 'Text Formats',
          items: [
            { text: 'Overview', link: '/text/' },
            { text: 'YAML', link: '/text/yaml' },
            { text: 'Markdown', link: '/text/markdown' }
          ]
        },
        {
          text: 'Control Flow',
          items: [
            { text: 'Overview', link: '/control/' },
            { text: 'Retry Logic', link: '/control/retry' },
            { text: 'Circuit Breaker', link: '/control/circuit-breaker' },
            { text: 'Rate Limiter', link: '/control/rate-limiter' },
            { text: 'Caching', link: '/control/cache' },
            { text: 'Resource Management', link: '/control/resource' },
            { text: 'Background Task', link: '/control/background-task' }
          ]
        },
        {
          text: 'HTTP',
          items: [
            { text: 'Overview', link: '/http/' },
            { text: 'HTTP Client', link: '/http/client' },
            { text: 'REST Server', link: '/http/server' },
            { text: 'Router', link: '/http/router' },
            { text: 'RPC', link: '/http/rpc' },
            { text: 'OpenAPI', link: '/http/openapi' },
            { text: 'Desktop Apps (Electron)', link: '/http/electron' },
            { text: 'Electron Tutorial', link: '/http/electron-tutorial' },
            { text: 'Server-Sent Events', link: '/http/sse' },
            { text: 'WebSocket Client', link: '/http/websocket' },
            { text: 'Retry Strategies', link: '/http/retry' }
          ]
        },
        {
          text: 'MCP',
          items: [
            { text: 'Build an MCP Server', link: '/mcp/' }
          ]
        },
        {
          text: 'Reactive Streams',
          items: [
            { text: 'Overview', link: '/rx/' },
            { text: 'Basics', link: '/rx/basics' },
            { text: 'Operators', link: '/rx/operators' },
            { text: 'Concurrency', link: '/rx/concurrency' }
          ]
        },
        {
          text: 'CLI',
          items: [
            { text: 'Overview', link: '/cli/' },
            { text: 'Terminal Styling', link: '/cli/tint' },
            { text: 'Progress Indicators', link: '/cli/progress' },
            { text: 'Command Launcher', link: '/cli/launcher' }
          ]
        },
        {
          text: 'Web UI (Scala.js)',
          items: [
            { text: 'RxElement', link: '/dom/' }
          ]
        },
        {
          text: 'Build Tooling',
          items: [
            { text: 'sbt-uni Plugin', link: '/build/sbt-uni' },
            { text: 'sbt-uni-playwright Plugin', link: '/build/sbt-uni-playwright' },
            { text: 'sbt-uni-crossproject Plugin', link: '/build/sbt-uni-crossproject' }
          ]
        }
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/wvlet/uni' }
    ],

    search: {
      provider: 'local'
    },

    footer: {
      message: 'Released under the Apache 2.0 License.',
      copyright: 'Copyright © 2025 wvlet'
    }
  }
}))
