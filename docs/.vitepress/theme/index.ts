import { h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import BlogPostMeta from './components/BlogPostMeta.vue'
import ImageZoom from './components/ImageZoom.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  // Show the date and author of blog posts above their content, and let readers enlarge post images
  Layout: () =>
    h(DefaultTheme.Layout, null, {
      'doc-before': () => h(BlogPostMeta),
      'layout-bottom': () => h(ImageZoom)
    })
}
