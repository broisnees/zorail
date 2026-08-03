<script setup lang="ts">
import AppSidebar from '@/components/AppSidebar.vue'
import MobileNav from '@/components/MobileNav.vue'
</script>

<!--
  Two shells over one set of pages: a side rail on desktop, a bottom tab bar on
  phones. `h-dvh` rather than `h-screen` because mobile browsers report `100vh`
  as the height *excluding* their collapsing toolbars, which pushes the bottom
  bar off-screen until you scroll.
-->
<template>
  <div class="flex h-dvh flex-col overflow-hidden md:flex-row">
    <AppSidebar />
    <main class="flex min-h-0 min-w-0 flex-1 flex-col">
      <slot />
    </main>
    <MobileNav />
  </div>
</template>
