import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'
import tailwindcss from '@tailwindcss/vite'

// electron-vite drives three separate Vite builds (main / preload / renderer). The Scala.js plugin
// is applied to `main` and `renderer`; its `projectID` selects which sbt project to link, and
// `import 'scalajs:main.js'` resolves to that project's linker output.
export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin(), scalaJSPlugin({ cwd: '.', projectID: 'main' })]
  },
  preload: {
    plugins: [externalizeDepsPlugin()]
  },
  renderer: {
    plugins: [scalaJSPlugin({ cwd: '.', projectID: 'renderer' }), tailwindcss()]
  }
})
