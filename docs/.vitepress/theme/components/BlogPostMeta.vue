<script setup lang="ts">
import { useData } from 'vitepress'
import BlogAuthor from './BlogAuthor.vue'

const { frontmatter, page } = useData()
const date = () => new Date(frontmatter.value.date).toISOString().slice(0, 10)
</script>

<template>
  <p v-if="frontmatter.date && page.relativePath.startsWith('blog/')" class="blog-meta">
    <BlogAuthor v-if="frontmatter.author" :name="frontmatter.author" />
    <time :datetime="date()">{{ date() }}</time>
  </p>
</template>
