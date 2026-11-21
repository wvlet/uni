<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useData, withBase } from 'vitepress'

// The uni-ui gallery (examples/ui-gallery) in an iframe. `story` opens one story, and `embed`
// drops the gallery's header and sidebar. The frame follows the docs' light or dark theme.
const props = withDefaults(defineProps<{ story?: string; embed?: boolean; height?: string }>(), {
  story: '',
  embed: false,
  height: '720px'
})

const { isDark } = useData()
const built = ref(true)

const src = computed(() => {
  const params = new URLSearchParams({ theme: isDark.value ? 'dark' : 'light' })
  if (props.embed) params.set('embed', '')
  const hash = props.story ? `#/${props.story}` : ''
  return withBase(`/gallery/index.html?${params}${hash}`)
})

// `pnpm docs:dev` without the linked gallery: say how to build it instead of showing a 404 page
onMounted(async () => {
  try {
    const res = await fetch(withBase('/gallery/main.js'), { method: 'HEAD' })
    built.value = res.ok
  } catch {
    built.value = false
  }
})
</script>

<template>
  <ClientOnly>
    <iframe
      v-if="built"
      class="ui-gallery-frame"
      :src="src"
      :style="{ height: props.height }"
      title="uni-ui component gallery"
      loading="lazy"
    />
    <div v-else class="ui-gallery-missing">
      The gallery isn't built. Run <code>./sbt exampleUiGallery/galleryDocs</code> in the repository
      root, then reload this page.
    </div>
  </ClientOnly>
</template>

<style scoped>
.ui-gallery-frame {
  display: block;
  width: 100%;
  border: 1px solid var(--vp-c-divider);
  border-radius: 8px;
  background: var(--vp-c-bg);
}
.ui-gallery-missing {
  padding: 24px;
  border: 1px dashed var(--vp-c-divider);
  border-radius: 8px;
  color: var(--vp-c-text-2);
}
</style>
