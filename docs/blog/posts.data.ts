import { createContentLoader } from 'vitepress'

export interface Post {
  title: string
  url: string
  date: string
  author: string
  description: string
}

declare const data: Post[]
export { data }

// Posts are docs/blog/YYYY-MM-DD-slug.md, served at /blog/slug by the rewrite in config.mts
export default createContentLoader('blog/*.md', {
  transform(pages): Post[] {
    return pages
      .filter((p) => p.frontmatter.date)
      .map((p) => ({
        title: p.frontmatter.title,
        url: p.url,
        date: new Date(p.frontmatter.date).toISOString().slice(0, 10),
        author: p.frontmatter.author,
        description: p.frontmatter.description
      }))
      .sort((a, b) => b.date.localeCompare(a.date))
  }
})
