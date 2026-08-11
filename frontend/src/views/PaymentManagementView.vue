<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="operations-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索学号或姓名" @press-enter="search" />
        <a-select v-model:value="filters.type" allow-clear placeholder="费用类型"><a-select-option value="住宿费">住宿费</a-select-option><a-select-option value="水电费">水电费</a-select-option></a-select>
        <a-select v-model:value="filters.status" allow-clear placeholder="缴费状态"><a-select-option value="未缴">未缴</a-select-option><a-select-option value="部分缴">部分缴</a-select-option><a-select-option value="已缴">已缴</a-select-option></a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('payment:write')" type="primary" @click="openCreate"><template #icon><PlusOutlined /></template>新增账单</a-button>
    </div>
    <a-table row-key="id" :columns="columns" :data-source="store.paymentBills" :loading="store.loading" :pagination="pagination" :scroll="{ x: 980 }" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'amountDue'">{{ Number(record.amountDue).toFixed(2) }}</template>
        <template v-else-if="column.key === 'amountPaid'">{{ Number(record.amountPaid).toFixed(2) }}</template>
        <template v-else-if="column.key === 'status'"><a-tag :color="paymentStatusColor(record.status)">{{ record.status }}</a-tag></template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="showDetail(record)">查看</a-button>
          <a-button v-if="record.status !== '已缴' && auth.hasPermission('payment:write')" type="link" size="small" @click="openPay(record)">缴费</a-button>
        </template>
      </template>
    </a-table>
    <a-modal v-model:open="createOpen" title="新增费用账单" :confirm-loading="store.saving" ok-text="确定" cancel-text="取消" destroy-on-close @ok="submitCreate">
      <a-form ref="createFormRef" :model="createForm" :rules="createRules" layout="vertical">
        <div class="role-form-grid">
          <a-form-item label="学号" name="studentNo"><a-input v-model:value="createForm.studentNo" /></a-form-item>
          <a-form-item label="姓名" name="name"><a-input v-model:value="createForm.name" /></a-form-item>
          <a-form-item label="费用类型" name="type"><a-select v-model:value="createForm.type"><a-select-option value="住宿费">住宿费</a-select-option><a-select-option value="水电费">水电费</a-select-option></a-select></a-form-item>
          <a-form-item label="应缴金额" name="amountDue"><a-input-number v-model:value="createForm.amountDue" :min="0.01" :precision="2" class="modal-number-input" /></a-form-item>
          <a-form-item label="截止日期" name="deadline"><a-input v-model:value="createForm.deadline" placeholder="2026-08-31" /></a-form-item>
        </div>
      </a-form>
    </a-modal>
    <a-modal v-model:open="payOpen" title="登记缴费" :confirm-loading="store.saving" ok-text="确认缴费" cancel-text="取消" destroy-on-close @ok="submitPay">
      <a-alert v-if="currentBill" :message="`待缴金额：${remainingAmount.toFixed(2)} 元`" type="info" show-icon class="checkout-alert" />
      <a-form ref="payFormRef" :model="payForm" :rules="payRules" layout="vertical">
        <a-form-item label="缴费金额" name="amount"><a-input-number v-model:value="payForm.amount" :min="0.01" :max="remainingAmount" :precision="2" class="modal-number-input" /></a-form-item>
        <a-form-item label="缴费方式" name="method"><a-input v-model:value="payForm.method" /></a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { OperationQuery, PaymentBillInput, PaymentRecordInput } from '../api/operations'
import { useAuthStore } from '../stores/auth'
import { useOperationsStore } from '../stores/operations'
import type { Payment } from '../types/dormitory'

const auth = useAuthStore(); const store = useOperationsStore()
const currentPage = ref(1); const pageSize = ref(10); const createOpen = ref(false); const payOpen = ref(false); const currentBill = ref<Payment | null>(null)
const createFormRef = ref<FormInstance>(); const payFormRef = ref<FormInstance>()
const filters = reactive<Omit<OperationQuery, 'page' | 'pageSize'>>({})
const createForm = reactive<PaymentBillInput>({ studentNo: '', name: '', type: '住宿费', amountDue: 800, deadline: '2026-08-31' })
const payForm = reactive<PaymentRecordInput>({ amount: 0, method: '现金' })
const columns: TableColumnsType = [
  { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 130 }, { title: '姓名', dataIndex: 'name', key: 'name', width: 100 },
  { title: '费用类型', dataIndex: 'type', key: 'type', width: 120 }, { title: '应缴金额', key: 'amountDue', width: 120 }, { title: '已缴金额', key: 'amountPaid', width: 120 },
  { title: '状态', key: 'status', width: 90 }, { title: '截止日期', dataIndex: 'deadline', key: 'deadline', width: 130 }, { title: '操作', key: 'actions', width: 130, fixed: 'right' },
]
const createRules = { studentNo: [{ required: true, message: '请输入学号' }], name: [{ required: true, message: '请输入姓名' }], amountDue: [{ required: true, type: 'number', min: 0.01, message: '请输入应缴金额' }], deadline: [{ required: true, message: '请输入截止日期' }] }
const payRules = { amount: [{ required: true, type: 'number', min: 0.01, message: '请输入缴费金额' }], method: [{ required: true, message: '请输入缴费方式' }] }
const query = computed<OperationQuery>(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({ current: currentPage.value, pageSize: pageSize.value, total: store.paymentTotal, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }))
const remainingAmount = computed(() => currentBill.value ? Number(currentBill.value.amountDue) - Number(currentBill.value.amountPaid) : 0)
function paymentStatusColor(status: Payment['status']) { return ({ 已缴: 'green', 部分缴: 'orange', 未缴: 'red' } as const)[status] }
async function load() { try { await store.loadPaymentBills(query.value) } catch (error) { message.error(error instanceof Error ? error.message : '费用数据加载失败') } }
function search() { currentPage.value = 1; void load() }
function resetFilters() { Object.assign(filters, { keyword: undefined, type: undefined, status: undefined }); currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
function openCreate() { Object.assign(createForm, { studentNo: '', name: '', type: '住宿费', amountDue: 800, deadline: '2026-08-31' }); createOpen.value = true }
function openPay(bill: Payment) { currentBill.value = bill; Object.assign(payForm, { amount: remainingAmount.value, method: '现金' }); payOpen.value = true }
function showDetail(bill: Payment) { Modal.info({ title: `${bill.name} · ${bill.type}`, content: `应缴 ${Number(bill.amountDue).toFixed(2)} 元，已缴 ${Number(bill.amountPaid).toFixed(2)} 元，状态：${bill.status}` }) }
async function submitCreate() { await createFormRef.value?.validate(); try { await store.createPayment({ ...createForm }, query.value); createOpen.value = false; message.success('账单已创建') } catch (error) { message.error(error instanceof Error ? error.message : '账单创建失败') } }
async function submitPay() { if (!currentBill.value) return; await payFormRef.value?.validate(); try { await store.payBill(currentBill.value.id, { ...payForm }, query.value); payOpen.value = false; message.success('缴费已登记') } catch (error) { message.error(error instanceof Error ? error.message : '缴费登记失败') } }
onMounted(load)
</script>
