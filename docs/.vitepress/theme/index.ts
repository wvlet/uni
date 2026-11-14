import { h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import BlogPostMeta from './components/BlogPostMeta.vue'
import ImageZoom from './components/ImageZoom.vue'
import { oldAnchorTarget } from './oldAnchors'
import './custom.css'

export default {
  extends: DefaultTheme,
  // Show the date and author of blog posts above their content, and let readers enlarge post images
  Layout: () =>
    h(DefaultTheme.Layout, null, {
      'doc-before': () => h(BlogPostMeta),
      'layout-bottom': () => h(ImageZoom)
    }),
  enhanceApp({ router, siteData }) {
    if (typeof window === 'undefined') return
    const redirect = () => {
      const target = oldAnchorTarget(siteData.value.base, window.location.pathname, window.location.hash)
      if (target) router.go(target, { replace: true })
    }
    // Runs after the initial load too, which goes through router.go
    const previous = router.onAfterRouteChange
    router.onAfterRouteChange = async (to) => {
      await previous?.(to)
      redirect()
    }
    window.addEventListener('hashchange', redirect)
  }
} satisfies Theme
