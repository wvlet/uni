<script setup lang="ts">
import { computed } from 'vue'
import { authors } from '../authors'

const props = defineProps<{ name: string }>()
const author = computed(() => authors[props.name])
</script>

<template>
  <a
    v-if="author"
    class="blog-author"
    :href="`https://github.com/${author.github}`"
    target="_blank"
    rel="noopener"
  >
    <img :src="`https://github.com/${author.github}.png?size=64`" alt="" width="24" height="24" loading="lazy" />
    <span>{{ name }}</span>
  </a>
  <span v-else class="blog-author">{{ name }}</span>
</template>
