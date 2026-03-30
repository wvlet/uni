import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Uni',
  description: 'Essential Scala Utilities - Refined for Scala 3 with minimal dependencies',

  base: '/uni/',
  cleanUrls: true,

  markdown: {
    theme: {
      light: 'nord',
      dark: 'nord'
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
    logo: '/uni-logo-1024x1024.png',
    nav: [
      { text: 'Guide', link: '/guide/' },
      { text: 'Modules', link: '/core/' },
      {
        text: 'GitHub',
        link: 'https://github.com/wvlet/uni'
      }
    ],

    sidebar: {
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
            { text: 'Type Introspection', link: '/core/surface' },
            { text: 'IO', link: '/core/io' },
            { text: 'FileSystem', link: '/core/filesystem' },
            { text: 'Utilities', link: '/core/utilities' }
          ]
        },
        {
          text: 'Control Flow',
          items: [
            { text: 'Overview', link: '/control/' },
            { text: 'Retry Logic', link: '/control/retry' },
            { text: 'Circuit Breaker', link: '/control/circuit-breaker' },
            { text: 'Caching', link: '/control/cache' },
            { text: 'Resource Management', link: '/control/resource' }
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
          text: 'Agent Framework',
          items: [
            { text: 'Overview', link: '/agent/' },
            { text: 'LLM Agent', link: '/agent/llm-agent' },
            { text: 'Chat Sessions', link: '/agent/chat-session' },
            { text: 'Tool Integration', link: '/agent/tools' },
            { text: 'AWS Bedrock', link: '/agent/bedrock' }
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
            { text: 'Type Introspection', link: '/core/surface' },
            { text: 'IO', link: '/core/io' },
            { text: 'FileSystem', link: '/core/filesystem' },
            { text: 'Utilities', link: '/core/utilities' }
          ]
        },
        {
          text: 'Control Flow',
          items: [
            { text: 'Overview', link: '/control/' },
            { text: 'Retry Logic', link: '/control/retry' },
            { text: 'Circuit Breaker', link: '/control/circuit-breaker' },
            { text: 'Caching', link: '/control/cache' },
            { text: 'Resource Management', link: '/control/resource' }
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
          text: 'Agent Framework',
          items: [
            { text: 'Overview', link: '/agent/' },
            { text: 'LLM Agent', link: '/agent/llm-agent' },
            { text: 'Chat Sessions', link: '/agent/chat-session' },
            { text: 'Tool Integration', link: '/agent/tools' },
            { text: 'AWS Bedrock', link: '/agent/bedrock' }
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
