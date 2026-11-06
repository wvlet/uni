import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import tailwindcss from '@tailwindcss/vite'
import { scalaJS } from './scripts/use-repo-sbt.mjs'

const ROOT = '../..'

// The Electron app: electron-vite drives three Vite builds (main / preload / renderer).
// The Scala.js modules are projects in the Uni repository's root build (`cwd: '../..'`);
// `projectID` selects the one that `import 'scalajs:main.js'` resolves to.
export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin(), scalaJS(ROOT, { projectID: 'exampleHarnessServer' })],
    build: { minify: true }
  },
  preload: {
    plugins: [externalizeDepsPlugin()]
  },
  renderer: {
    plugins: [scalaJS(ROOT, { projectID: 'exampleHarnessRenderer' }), tailwindcss()],
    build: { minify: true }
  }
})
