// The UI docs used to be two long pages, /dom/ (RxElement) and /dom/ui (uni-ui). Their sections now
// have pages of their own; send links to an old section to its new home. Unlisted anchors stay put.
const rx = (hash: string) => ['dom/rxelement', hash] as const
const OLD_ANCHORS: Record<string, Record<string, readonly [string, string]>> = {
  'dom/': {
    components: rx('components'),
    rxelement: rx('rxelement'),
    rxcomponent: rx('rxcomponent'),
    'html-dsl': rx('html-dsl'),
    tags: rx('tags'),
    attributes: rx('attributes'),
    events: rx('events'),
    'inline-styles-and-css': rx('inline-styles-and-css'),
    conditionals: rx('conditionals'),
    svg: rx('svg'),
    reactivity: rx('reactivity'),
    'embedding-rx': rx('embedding-rx'),
    'lists-that-update-item-by-item': rx('lists-that-update-item-by-item'),
    'two-way-form-binding': rx('two-way-form-binding'),
    'mounting-and-teardown': rx('mounting-and-teardown'),
    'browser-integrations': rx('browser-integrations'),
    'persisting-values-with-storage': rx('persisting-values-with-storage'),
    'enter-in-text-fields': rx('enter-in-text-fields'),
    'resizable-panes': rx('resizable-panes'),
    'testing-components': ['dom/testing', ''],
    querying: ['dom/testing', 'querying'],
    'firing-events': ['dom/testing', 'firing-events'],
    waiting: ['dom/testing', 'waiting'],
    'checking-aria-patterns': ['dom/testing', 'checking-aria-patterns']
  },
  'dom/ui': {
    setup: ['dom/', 'setup'],
    components: ['dom/controls', ''],
    overlays: ['dom/overlays', ''],
    collections: ['dom/collections', ''],
    dock: ['dom/layout', 'dock'],
    changes: ['dom/changes', ''],
    agents: ['dom/agents', ''],
    parts: ['dom/changes', 'parts'],
    themes: ['dom/theming', ''],
    testing: ['dom/testing', 'testing-uni-ui-components'],
    'checking-aria-patterns': ['dom/testing', 'checking-aria-patterns']
  }
}

// The new URL for an old `/dom/` or `/dom/ui` anchor, or undefined to stay on the page
export function oldAnchorTarget(base: string, pathname: string, hash: string): string | undefined {
  if (!hash || !pathname.startsWith(base)) return undefined
  const page = pathname
    .slice(base.length)
    .replace(/\.html$/, '')
    .replace(/(^|\/)index$/, '$1')
    .replace(/^dom$/, 'dom/')
  const target = OLD_ANCHORS[page]?.[decodeURIComponent(hash.slice(1))]
  if (!target) return undefined
  const [path, anchor] = target
  return `${base}${path}${anchor ? `#${anchor}` : ''}`
}
