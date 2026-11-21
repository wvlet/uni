<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useData, withBase } from 'vitepress'

// The uni-ui gallery (examples/ui-gallery) in an iframe. `story` opens one story, and `embed`
// shows only its card, with `view` (preview, code, or split) as the first view. The frame follows
// the docs' light or dark theme: the first theme goes in the URL, and later switches go by
// postMessage, so the story keeps its state.
const props = withDefaults(
  defineProps<{ story?: string; embed?: boolean; view?: string; height?: string }>(),
  { story: '', embed: false, view: '', height: '720px' }
)

const { isDark } = useData()
const frame = ref<HTMLIFrameElement | null>(null)
const src = ref('')
const built = ref(true)

const theme = (dark: boolean) => (dark ? 'dark' : 'light')

watch(isDark, (dark) => {
  frame.value?.contentWindow?.postMessage({ type: 'uni-gallery-theme', theme: theme(dark) }, '*')
})

onMounted(async () => {
  const params = new URLSearchParams({ theme: theme(isDark.value) })
  if (props.embed) params.set('embed', '')
  if (props.view) params.set('view', props.view)
  const hash = props.story ? `#/${props.story}` : ''
  // `pnpm docs:dev` without the linked gallery: say how to build it instead of showing a 404 page
  try {
    const res = await fetch(withBase('/gallery/main.js'), { method: 'HEAD' })
    built.value = res.ok
  } catch {
    built.value = false
  }
  src.value = withBase(`/gallery/index.html?${params}${hash}`)
})
</script>

<template>
  <ClientOnly>
    <iframe
      v-if="built && src"
      ref="frame"
      class="ui-gallery-frame"
      :src="src"
      :style="{ height: props.height }"
      title="uni-ui component gallery"
      loading="lazy"
    />
    <div v-else-if="!built" class="ui-gallery-missing">
      The gallery isn't built. Run <code>./sbt exampleUiGallery/galleryDocs</code> in the repository
      root, then reload this page.
    </div>
  </ClientOnly>
</template>

<style scoped>
.ui-gallery-frame {
  display: block;
  width: 100%;
  margin: 16px 0;
  border: 1px solid var(--vp-c-divider);
  border-radius: 12px;
  background: var(--vp-c-bg);
  box-shadow: 0 18px 40px -28px rgb(6 17 20 / 0.45);
}
.ui-gallery-missing {
  margin: 16px 0;
  padding: 24px;
  border: 1px dashed var(--vp-c-divider);
  border-radius: 12px;
  color: var(--vp-c-text-2);
}
</style>
