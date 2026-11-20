---
title: Uni Blog
description: Announcements, release highlights, and articles about Uni, the Scala 3 library for object wiring, logging, JSON/MessagePack, HTTP, RPC, MCP servers, and testing on the JVM, Scala.js, and Scala Native.
---

<script setup>
import { data as posts } from './posts.data'
import { withBase } from 'vitepress'
import BlogAuthor from '../.vitepress/theme/components/BlogAuthor.vue'
</script>

# Uni Blog

Announcements, release highlights, and articles about Uni.
Subscribe with the [Atom feed](https://wvlet.org/uni/blog/feed.xml).

<div class="blog-list">
  <article v-for="post of posts" :key="post.url" class="blog-entry">
    <h2><a :href="withBase(post.url)">{{ post.title }}</a></h2>
    <p class="blog-meta"><BlogAuthor :name="post.author" /><time :datetime="post.date">{{ post.date }}</time></p>
    <p>{{ post.description }}</p>
  </article>
</div>
