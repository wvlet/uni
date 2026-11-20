// Blog authors, keyed by the `author` frontmatter of a post. The avatar comes from the GitHub
// profile, so it stays current without an image in the repository. An author missing here is shown
// by name only.
export interface Author {
  github: string
}

export const authors: Record<string, Author> = {
  'Taro L. Saito': { github: 'xerial' }
}
