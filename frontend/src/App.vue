<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import '@/styles/reading.css'
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/books" aria-label="研序 首页">
        <span class="brand-mark">研</span>
        <span>
          <strong>研序</strong>
          <small>技术研究 Agent</small>
        </span>
      </RouterLink>

      <nav class="app-nav" aria-label="主导航">
        <RouterLink to="/books">书架</RouterLink>
        <el-dropdown trigger="click">
          <el-button text>原有工作区 ▾</el-button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item><RouterLink to="/project-sources">项目资料</RouterLink></el-dropdown-item>
            <el-dropdown-item><RouterLink to="/research-tasks">研究任务</RouterLink></el-dropdown-item>
            <el-dropdown-item><RouterLink to="/library">资料库</RouterLink></el-dropdown-item>
            <el-dropdown-item><RouterLink to="/chat">知识问答</RouterLink></el-dropdown-item>
            <el-dropdown-item><RouterLink to="/upgrade-research">升级研究</RouterLink></el-dropdown-item>
            <el-dropdown-item><RouterLink to="/memories">回答偏好</RouterLink></el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
      </nav>

      <div class="environment-pill"><span></span> 本地工作台</div>
    </header>

    <main class="app-content">
      <RouterView :key="$route.path" />
    </main>
  </div>
</template>
