<script setup lang="ts">
import { useData } from 'vitepress'

const { frontmatter, page } = useData()
const date = () => new Date(frontmatter.value.date).toISOString().slice(0, 10)
</script>

<template>
  <p v-if="frontmatter.date && page.relativePath.startsWith('blog/')" class="blog-meta">
    <time :datetime="date()">{{ date() }}</time>
    <template v-if="frontmatter.author"> · {{ frontmatter.author }}</template>
  </p>
</template>
