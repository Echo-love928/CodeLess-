<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { StarOutlined } from '@ant-design/icons-vue'
import { applyFeatured } from '@/api/appDiscoveryController'
import { featureLabel } from '@/utils/appPrivacy'
const props = defineProps<{ app?: API.AppVO; owner: boolean }>()
const emit = defineEmits<{ updated: [] }>()
const reason = ref('')
const submitting = ref(false)
const eligible = computed(
  () =>
    props.owner &&
    props.app?.visibility === 'public' &&
    props.app?.generationStatus !== 'generating' &&
    Boolean(props.app?.version || props.app?.generationStatus === 'completed'),
)
const submit = async () => {
  if (!eligible.value || !props.app?.id || submitting.value) return
  submitting.value = true
  try {
    const res = await applyFeatured(props.app.id, reason.value.trim())
    if (res.data.code !== 0 || !res.data.data) throw new Error(res.data.message || '申请失败')
    message.success('申请已提交，等待管理员审核')
    reason.value = ''
    emit('updated')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '申请失败，请重试')
  } finally {
    submitting.value = false
  }
}
</script>
<template>
  <section class="feature-panel" v-if="owner">
    <div class="feature-panel__heading">
      <h3><StarOutlined />申请精选</h3>
      <a-tag
        :color="
          app?.featureStatus === 'approved'
            ? 'green'
            : app?.featureStatus === 'pending'
              ? 'processing'
              : 'default'
        "
        >{{ featureLabel(app?.featureStatus) }}</a-tag
      >
    </div>
    <p>公开且已生成的优质应用可以申请平台推荐，审核通过后展示在精选区。</p>
    <a-alert
      v-if="app?.featureStatus === 'pending'"
      type="info"
      message="申请正在审核中，请耐心等待。"
      show-icon
    />
    <a-alert
      v-if="app?.featureStatus === 'rejected'"
      type="warning"
      :message="`审核未通过：${app.featureReviewMessage || '请完善应用后重新申请'}`"
      show-icon
    />
    <template v-if="!['pending', 'approved'].includes(app?.featureStatus ?? '')">
      <p v-if="!eligible" class="feature-panel__hint">
        请先保存为公开应用并完成代码生成，再提交申请。
      </p>
      <a-textarea
        v-model:value="reason"
        :disabled="!eligible || submitting"
        :maxlength="500"
        :rows="3"
        show-count
        placeholder="说明应用的用途、亮点和适合的人群（选填）"
        aria-label="精选申请说明"
      />
      <a-button type="primary" :disabled="!eligible" :loading="submitting" @click="submit">{{
        app?.featureStatus === 'rejected' ? '重新申请' : '提交精选申请'
      }}</a-button>
    </template>
  </section>
</template>
<style scoped>
.feature-panel {
  margin: 24px 0;
  padding: 24px;
  border: 1px solid #e3eaf2;
  border-radius: 14px;
  background: #fff;
}
.feature-panel__heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.feature-panel h3 {
  margin: 0;
  color: #243149;
  font-size: 18px;
}
.feature-panel p {
  color: #718096;
  font-size: 13px;
  line-height: 1.7;
}
.feature-panel .ant-btn {
  margin-top: 18px;
}
.feature-panel .ant-alert {
  margin: 14px 0;
}
.feature-panel__hint {
  color: #a16b14 !important;
}
</style>
