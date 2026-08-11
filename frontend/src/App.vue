<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/library" aria-label="ContextPilot 首页">
        <span class="brand-mark">CP</span>
        <span>
          <strong>ContextPilot</strong>
          <small>Knowledge workspace</small>
        </span>
      </RouterLink>

      <nav class="app-nav" aria-label="主导航">
        <RouterLink to="/library">资料库</RouterLink>
        <RouterLink to="/chat">知识问答</RouterLink>
      </nav>

      <div class="environment-pill"><span></span> 本地工作台</div>
    </header>

    <main class="app-content">
      <RouterView />
    </main>
  </div>
</template>
