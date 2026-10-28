<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

// Click an image in a blog post to view it at full size; click again or press Esc to close
const src = ref<string | null>(null)
const alt = ref('')

const onClick = (e: MouseEvent) => {
  const img = e.target
  if (img instanceof HTMLImageElement && img.closest('.vp-doc') && location.pathname.includes('/blog/')) {
    src.value = img.currentSrc || img.src
    alt.value = img.alt
  }
}
const onKey = (e: KeyboardEvent) => {
  if (e.key === 'Escape') src.value = null
}

onMounted(() => {
  document.addEventListener('click', onClick)
  document.addEventListener('keydown', onKey)
})
onUnmounted(() => {
  document.removeEventListener('click', onClick)
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <div v-if="src" class="image-zoom" @click="src = null">
    <img :src="src" :alt="alt" />
  </div>
</template>
