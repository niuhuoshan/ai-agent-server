<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { fetchNotifications } from '@/service/api';
import PlatformAvatar from '@/components/custom/soybean-avatar.vue';

defineOptions({
  name: 'ProjectNews'
});

interface NewsItem {
  id: string;
  content: string;
  time: string;
}

const newses = ref<NewsItem[]>([]);

onMounted(async () => {
  const { data, error } = await fetchNotifications(undefined, false);
  if (!error) {
    newses.value = data.slice(0, 8).map(item => ({
      id: item.id,
      content: item.title + (item.content ? ` · ${item.content}` : ''),
      time: item.createdAt
    }));
  }
});
</script>

<template>
  <NCard title="最近通知" :bordered="false" size="small" segmented class="card-wrapper">
    <template #header-extra><span class="text-#999">最近通知</span></template>
    <NList>
      <NListItem v-for="item in newses" :key="item.id">
        <template #prefix>
          <PlatformAvatar class="size-48px!" />
        </template>
        <NThing :title="item.content" :description="item.time" />
      </NListItem>
    </NList>
    <NEmpty v-if="newses.length === 0" description="暂无通知" />
  </NCard>
</template>

<style scoped></style>
