<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="operations-filters operations-filters--compact">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索宿舍或楼栋" @press-enter="search" />
        <a-select v-model:value="filters.result" allow-clear placeholder="检查结果">
          <a-select-option value="优秀">优秀</a-select-option><a-select-option value="良好">良好</a-select-option><a-select-option value="一般">一般</a-select-option><a-select-option value="不合格">不合格</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('hygiene:write')" type="primary" @click="openCreate"><template #icon><PlusOutlined /></template>新增检查</a-button>
    </div>
    <a-table row-key="id" :columns="columns" :data-source="store.hygieneChecks" :loading="store.loading" :pagination="pagination" :scroll="{ x: 900 }" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'result'"><a-tag :color="resultColor(record.result)">{{ record.result }}</a-tag></template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="showDetail(record)">查看</a-button>
          <template v-if="auth.hasPermission('hygiene:write')">
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" danger @click="confirmDelete(record)">删除</a-button>
          </template>
        </template>
      </template>
    </a-table>
    <a-modal v-model:open="modalOpen" :title="editingId ? '编辑卫生检查' : '新增卫生检查'" :confirm-loading="store.saving" ok-text="确定" cancel-text="取消" destroy-on-close @ok="submit">
      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <div class="role-form-grid">
          <a-form-item label="宿舍名称" name="dormitory"><a-input v-model:value="form.dormitory" /></a-form-item>
          <a-form-item label="楼栋" name="building"><a-input v-model:value="form.building" /></a-form-item>
          <a-form-item label="检查人" name="inspector"><a-input v-model:value="form.inspector" /></a-form-item>
          <a-form-item label="卫生评分" name="score"><a-input-number v-model:value="form.score" :min="0" :max="100" class="modal-number-input" /></a-form-item>
        </div>
        <a-form-item label="检查备注"><a-textarea v-model:value="form.remark" :rows="3" :maxlength="255" show-count /></a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { HygieneCheckInput, OperationQuery } from '../api/operations'
import { useAuthStore } from '../stores/auth'
import { useOperationsStore } from '../stores/operations'
import type { HygieneCheck } from '../types/dormitory'

const auth = useAuthStore(); const store = useOperationsStore()
const currentPage = ref(1); const pageSize = ref(10); const modalOpen = ref(false); const editingId = ref<number | null>(null); const formRef = ref<FormInstance>()
const filters = reactive<Omit<OperationQuery, 'page' | 'pageSize'>>({})
const form = reactive<HygieneCheckInput>({ dormitory: '', building: '', inspector: '管理员', score: 90, remark: '' })
const columns: TableColumnsType = [
  { title: '宿舍名称', dataIndex: 'dormitory', key: 'dormitory', width: 160 }, { title: '楼栋', dataIndex: 'building', key: 'building', width: 100 },
  { title: '检查时间', dataIndex: 'date', key: 'date', width: 130 }, { title: '检查人', dataIndex: 'inspector', key: 'inspector', width: 120 },
  { title: '卫生评分', dataIndex: 'score', key: 'score', width: 100 }, { title: '检查结果', key: 'result', width: 110 }, { title: '检查备注', dataIndex: 'remark', key: 'remark', width: 200 }, { title: '操作', key: 'actions', width: 160, fixed: 'right' },
]
const rules = { dormitory: [{ required: true, message: '请输入宿舍名称' }], building: [{ required: true, message: '请输入楼栋' }], inspector: [{ required: true, message: '请输入检查人' }], score: [{ required: true, type: 'number', min: 0, max: 100, message: '评分需为 0-100' }] }
const query = computed<OperationQuery>(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({ current: currentPage.value, pageSize: pageSize.value, total: store.hygieneTotal, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }))
function resultColor(result: HygieneCheck['result']) { return ({ 优秀: 'green', 良好: 'blue', 一般: 'orange', 不合格: 'red' } as const)[result] }
async function load() { try { await store.loadHygieneChecks(query.value) } catch (error) { message.error(error instanceof Error ? error.message : '卫生检查加载失败') } }
function search() { currentPage.value = 1; void load() }
function resetFilters() { Object.assign(filters, { keyword: undefined, result: undefined }); currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
function openCreate() { editingId.value = null; Object.assign(form, { dormitory: '', building: '', inspector: '管理员', score: 90, remark: '' }); modalOpen.value = true }
function openEdit(check: HygieneCheck) { editingId.value = check.id; Object.assign(form, { dormitory: check.dormitory, building: check.building, inspector: check.inspector, score: check.score, remark: check.remark ?? '' }); modalOpen.value = true }
function showDetail(check: HygieneCheck) { Modal.info({ title: `${check.building} · ${check.dormitory}`, content: `检查人：${check.inspector}，评分：${check.score}，结果：${check.result}。${check.remark || '无备注'}` }) }
async function submit() { await formRef.value?.validate(); try { await store.saveHygiene(editingId.value, { ...form }, query.value); modalOpen.value = false; message.success(editingId.value ? '卫生检查已更新' : '卫生检查已新增') } catch (error) { message.error(error instanceof Error ? error.message : '卫生检查保存失败') } }
function confirmDelete(check: HygieneCheck) { Modal.confirm({ title: `确认删除 ${check.dormitory} 的检查记录？`, okText: '删除', okType: 'danger', cancelText: '取消', async onOk() { await store.removeHygiene(check.id, query.value); message.success('卫生检查已删除') } }) }
onMounted(load)
</script>
