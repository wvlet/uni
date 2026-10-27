import { h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import BlogPostMeta from './components/BlogPostMeta.vue'
import './custom.css'

export default {
  extends: DefaultTheme,
  // Show the date and author of blog posts above their content
  Layout: () => h(DefaultTheme.Layout, null, { 'doc-before': () => h(BlogPostMeta) })
}
