<script setup lang="ts">
import { getGenerationStatus } from '@/utils/appStatus'
defineProps<{ status?: string }>()
</script>

<template>
  <a-tag :color="getGenerationStatus(status).color">{{ getGenerationStatus(status).label }}</a-tag>
</template>
