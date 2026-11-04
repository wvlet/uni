import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import scalaJSPlugin from '@scala-js/vite-plugin-scalajs'
import tailwindcss from '@tailwindcss/vite'

// The Electron app: electron-vite drives three Vite builds (main / preload / renderer).
// `projectID` selects the sbt project that `import 'scalajs:main.js'` resolves to.
export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin(), scalaJSPlugin({ cwd: '.', projectID: 'server' })],
    build: { minify: true }
  },
  preload: {
    plugins: [externalizeDepsPlugin()]
  },
  renderer: {
    plugins: [scalaJSPlugin({ cwd: '.', projectID: 'renderer' }), tailwindcss()],
    build: { minify: true }
  }
})
