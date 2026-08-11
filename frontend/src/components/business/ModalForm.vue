<template>
  <a-modal
    :open="open"
    :title="title"
    :confirm-loading="loading"
    :ok-text="okText"
    :cancel-text="cancelText"
    :destroy-on-close="destroyOnClose"
    @ok="handleOk"
    @cancel="emit('cancel')"
  >
    <a-form ref="formRef" :model="model" :rules="rules" layout="vertical">
      <slot />
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import type { FormInstance, FormProps } from 'ant-design-vue'
import { ref } from 'vue'

const props = withDefaults(defineProps<{
  open: boolean
  title: string
  model: object
  rules?: FormProps['rules']
  loading?: boolean
  okText?: string
  cancelText?: string
  destroyOnClose?: boolean
}>(), {
  okText: '确定',
  cancelText: '取消',
  destroyOnClose: true,
})

const emit = defineEmits<{
  cancel: []
  submit: [values: Record<string, unknown>]
}>()

const formRef = ref<FormInstance>()

const handleOk = async () => {
  await formRef.value?.validate()
  emit('submit', { ...props.model })
}
</script>
