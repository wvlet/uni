import { defineConfig, type DefaultTheme } from 'vitepress'
import { fetchLatestVersion } from './fetchLatestVersion'

const FALLBACK_VERSION = '2026.1.6'
const VERSION_TOKEN = '__UNI_VERSION__'
const uniVersion = await fetchLatestVersion(FALLBACK_VERSION)

interface UniThemeConfig extends DefaultTheme.Config {
  uniVersion: string
}

export default defineConfig<UniThemeConfig>({
  title: 'Uni',
  description: 'Essential Scala Utilities - Refined for Scala 3 with minimal dependencies',

  base: '/uni/',
  cleanUrls: true,

  // Exclude contributor-only files from the rendered site.
  srcExclude: ['**/CLAUDE.md'],

  markdown: {
    theme: {
      light: 'nord',
      dark: 'nord'
    },
    config(md) {
      const replace = (text: string): string =>
        text.includes(VERSION_TOKEN) ? text.split(VERSION_TOKEN).join(uniVersion) : text
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
    ['link', { rel: 'icon', href: '/uni/favicon.ico' }],
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
    // Open Graph
    ['meta', { property: 'og:type', content: 'website' }],
    ['meta', { property: 'og:title', content: 'Uni - Essential Scala Utilities' }],
    ['meta', { property: 'og:description', content: 'Essential Scala Utilities - Refined for Scala 3 with minimal dependencies' }],
    ['meta', { property: 'og:image', content: 'https://wvlet.org/uni/uni-banner-1280x640.png' }],
    ['meta', { property: 'og:url', content: 'https://wvlet.org/uni/' }],
    // Twitter Card
    ['meta', { name: 'twitter:card', content: 'summary_large_image' }],
    ['meta', { name: 'twitter:title', content: 'Uni - Essential Scala Utilities' }],
    ['meta', { name: 'twitter:description', content: 'Essential Scala Utilities - Refined for Scala 3 with minimal dependencies' }],
    ['meta', { name: 'twitter:image', content: 'https://wvlet.org/uni/uni-banner-1280x640.png' }]
  ],

  themeConfig: {
    uniVersion,
    logo: '/uni-logo-1024x1024.png',
    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'Book', link: '/book/' },
      { text: 'Modules', link: '/core/' },
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
            { text: '4. Logging That Finds You', link: '/book/ch04-00-logging' },
            { text: '5. JSON & MessagePack', link: '/book/ch05-00-data' }
          ]
        },
        {
          text: 'Part IV — Async & Control Flow',
          items: [
            { text: '6. Rx, the Composable Stream', link: '/book/ch06-00-rx' },
            { text: '7. Retry, Circuit Breakers, Resources', link: '/book/ch07-00-control' }
          ]
        },
        {
          text: 'Part V — HTTP & RPC',
          items: [
            { text: '8. HTTP Clients and Servers', link: '/book/ch08-00-http' },
            { text: '9. Typed RPC', link: '/book/ch09-00-rpc' }
          ]
        },
        {
          text: 'Part VI — Cross-Platform',
          items: [
            { text: '10. One Codebase, Three Runtimes', link: '/book/ch10-00-cross-platform' }
          ]
        },
        {
          text: 'Part VII — Testing',
          items: [
            { text: '11. Testing with UniTest', link: '/book/ch11-00-testing' }
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
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/' },
            { text: 'Installation', link: '/guide/installation' },
            { text: 'Design Principles', link: '/guide/principles' }
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
            { text: 'Result', link: '/core/result' },
            { text: 'Utilities', link: '/core/utilities' }
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
            { text: 'Server-Sent Events', link: '/http/sse' },
            { text: 'Retry Strategies', link: '/http/retry' }
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
        }
      ],
      '/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Introduction', link: '/guide/' },
            { text: 'Installation', link: '/guide/installation' },
            { text: 'Design Principles', link: '/guide/principles' }
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
            { text: 'Result', link: '/core/result' },
            { text: 'Utilities', link: '/core/utilities' }
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
            { text: 'Server-Sent Events', link: '/http/sse' },
            { text: 'Retry Strategies', link: '/http/retry' }
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
})
