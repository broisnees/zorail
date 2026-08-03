<script setup lang="ts">
import { NAV, isNavActive } from '@/composables/useNav'

const route = useRoute()
</script>

<!--
  Bottom tab bar for phones, replacing the sidebar below `md`.

  Bottom rather than top because it is the only part of a tall phone screen a
  thumb reaches comfortably. Every target is at least 44px tall, and the bar
  pads itself past the home-gesture area with env(safe-area-inset-bottom) so the
  last row of labels is not sitting under the gesture pill.
-->
<template>
  <nav
    class="flex flex-none items-stretch border-t bg-background/95 backdrop-blur md:hidden"
    style="padding-bottom: env(safe-area-inset-bottom, 0px)"
    aria-label="Primary"
  >
    <NuxtLink
      v-for="item in NAV"
      :key="item.key"
      :to="item.path"
      class="flex min-h-[52px] flex-1 flex-col items-center justify-center gap-1 px-1 py-1.5 transition-colors active:bg-accent"
      :class="isNavActive(item, route.path) ? 'text-foreground' : 'text-muted-foreground'"
      :aria-current="isNavActive(item, route.path) ? 'page' : undefined"
    >
      <component :is="item.icon" class="size-[21px]" />
      <!-- Labels stay: icon-only bottom bars are a guessing game. -->
      <span class="text-[10.5px] leading-none">{{ item.label }}</span>
    </NuxtLink>
  </nav>
</template>
