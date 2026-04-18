<script setup>
import { useData } from 'vitepress'

const { theme } = useData()
</script>

<template>
  <div class="hero-code">
    <div class="code-block">
      <div class="code-header">
        <span class="dot red"></span>
        <span class="dot yellow"></span>
        <span class="dot green"></span>
        <span class="code-title">build.sbt</span>
      </div>
      <pre><code><span class="comment">// Add to your build.sbt</span>
<span class="key">libraryDependencies</span> += <span class="string">"org.wvlet.uni"</span> %% <span class="string">"uni"</span> % <span class="string">"{{ theme.uniVersion }}"</span></code></pre>
    </div>
    <div class="code-block">
      <div class="code-header">
        <span class="dot red"></span>
        <span class="dot yellow"></span>
        <span class="dot green"></span>
        <span class="code-title">Main.scala</span>
      </div>
      <pre><code><span class="keyword">import</span> wvlet.uni.log.LogSupport

<span class="keyword">class</span> <span class="type">MyApp</span> <span class="keyword">extends</span> <span class="type">LogSupport</span>:
  <span class="keyword">def</span> <span class="func">run</span>() =
    info(<span class="string">"Hello, Uni!"</span>)

<span class="keyword">@main</span> <span class="keyword">def</span> <span class="func">main</span> = <span class="type">MyApp</span>().run()</code></pre>
    </div>
  </div>
</template>

<style scoped>
.hero-code {
  display: none;
  flex-direction: column;
  gap: 16px;
  max-width: 520px;
  width: 100%;
}

/* Only show code on larger screens */
@media (min-width: 960px) {
  .hero-code {
    display: flex;
  }
}

.code-block {
  background: #2e3440;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}

.code-header {
  background: #3b4252;
  padding: 8px 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
}

.dot.red { background: #bf616a; }
.dot.yellow { background: #ebcb8b; }
.dot.green { background: #a3be8c; }

.code-title {
  margin-left: 8px;
  color: #616e88;
  font-size: 12px;
  font-family: var(--vp-font-family-mono);
}

.code-block pre {
  margin: 0;
  padding: 16px;
  overflow-x: auto;
}

.code-block code {
  font-family: var(--vp-font-family-mono);
  font-size: 13px;
  line-height: 1.6;
  color: #d8dee9;
}

/* Nord color scheme - vivid */
.comment { color: #7c889b; }
.keyword { color: #5ccfe6; }
.key { color: #5ccfe6; }
.string { color: #c3e88d; }
.type { color: #ffcb6b; }
.func { color: #89ddff; }
</style>
