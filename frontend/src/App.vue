<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/library" aria-label="研序 首页">
        <span class="brand-mark">研</span>
        <span>
          <strong>研序</strong>
          <small>技术研究 Agent</small>
        </span>
      </RouterLink>

      <nav class="app-nav" aria-label="主导航">
        <RouterLink to="/research-tasks">研究任务</RouterLink>
        <RouterLink to="/library">资料库</RouterLink>
        <RouterLink to="/chat">知识问答</RouterLink>
        <RouterLink to="/upgrade-research">升级研究</RouterLink>
        <RouterLink to="/memories">回答偏好</RouterLink>
      </nav>

      <div class="environment-pill"><span></span> 本地工作台</div>
    </header>

    <main class="app-content">
      <RouterView />
    </main>
  </div>
</template>
