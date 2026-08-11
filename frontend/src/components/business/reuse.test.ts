import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import DataTable from './DataTable.vue'
import ModalForm from './ModalForm.vue'
import SearchForm from './SearchForm.vue'

const InputStub = defineComponent({
  name: 'AInput',
  inheritAttrs: false,
  props: { value: String, placeholder: String },
  emits: ['update:value', 'pressEnter'],
  setup(props, { attrs, emit }) {
    return () => h('input', {
      ...attrs,
      value: props.value ?? '',
      placeholder: props.placeholder,
      onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value),
      onKeyup: (event: KeyboardEvent) => {
        if (event.key === 'Enter') emit('pressEnter')
      },
    })
  },
})

const ButtonStub = defineComponent({
  name: 'AButton',
  emits: ['click'],
  setup(_, { emit, slots }) {
    return () => h('button', { type: 'button', onClick: () => emit('click') }, slots.default?.())
  },
})

describe('复用业务组件', () => {
  it('SearchForm 保持查询在前、重置在后，并支持回车查询', async () => {
    const wrapper = mount(SearchForm, {
      props: {
        fields: [{ key: 'keyword', type: 'input', placeholder: '搜索关键字' }],
      },
      global: {
        stubs: {
          AInput: InputStub,
          ASelect: true,
          ASelectOption: true,
          AButton: ButtonStub,
          SearchOutlined: true,
          ReloadOutlined: true,
        },
      },
    })

    expect(wrapper.findAll('button').map((button) => button.text())).toEqual(['查询', '重置'])
    const input = wrapper.get('input')
    await input.setValue('一号楼')
    await input.trigger('keyup', { key: 'Enter' })
    expect(wrapper.emitted('search')?.[0]).toEqual([{ keyword: '一号楼' }])

    await wrapper.findAll('button')[1]?.trigger('click')
    expect(wrapper.emitted('reset')).toHaveLength(1)
    expect((input.element as HTMLInputElement).value).toBe('')
  })

  it('DataTable 复用 Ant Table 并透传单元格插槽和分页事件', async () => {
    const TableStub = defineComponent({
      name: 'ATable',
      props: ['columns', 'dataSource', 'loading', 'pagination', 'scroll', 'rowKey'],
      emits: ['change'],
      setup(props, { emit, slots }) {
        return () => h('div', { class: 'ant-table-stub' }, [
          slots.bodyCell?.({ column: props.columns[0], record: props.dataSource[0], text: props.dataSource[0].name }),
          h('button', { class: 'page-change', onClick: () => emit('change', { current: 2, pageSize: 20 }) }, '翻页'),
        ])
      },
    })
    const rows = [{ id: 1, name: '一号楼' }]
    const pagination = { current: 1, pageSize: 10, total: 1 }
    const wrapper = mount(DataTable, {
      props: {
        columns: [{ title: '名称', dataIndex: 'name', key: 'name' }],
        rows,
        loading: true,
        pagination,
        scroll: { x: 800 },
      } as never,
      slots: {
        bodyCell: ({ record }: { record: { name: string } }) => h('span', { class: 'custom-cell' }, record.name),
      },
      global: { stubs: { ATable: TableStub } },
    })

    expect(wrapper.findComponent(TableStub).props('dataSource')).toEqual(rows)
    expect(wrapper.findComponent(TableStub).props('loading')).toBe(true)
    expect(wrapper.get('.custom-cell').text()).toBe('一号楼')
    await wrapper.get('.page-change').trigger('click')
    expect(wrapper.emitted('change')?.[0]).toEqual([{ current: 2, pageSize: 20 }])
  })

  it('ModalForm 复用表单容器、校验模型并保留业务表单插槽', async () => {
    const validate = vi.fn().mockResolvedValue(undefined)
    const ModalStub = defineComponent({
      name: 'AModal',
      props: ['open', 'title'],
      emits: ['ok', 'cancel'],
      setup(props, { emit, slots }) {
        return () => h('section', { class: 'modal-stub' }, [
          h('h2', String(props.title)),
          slots.default?.(),
          h('button', { class: 'ok', onClick: () => emit('ok') }, '确定'),
          h('button', { class: 'cancel', onClick: () => emit('cancel') }, '取消'),
        ])
      },
    })
    const FormStub = defineComponent({
      name: 'AForm',
      setup(_, { expose, slots }) {
        expose({ validate })
        return () => h('form', slots.default?.())
      },
    })
    const model = { name: '一号楼' }
    const wrapper = mount(ModalForm, {
      props: { open: true, title: '编辑楼栋', model, rules: {} } as never,
      slots: { default: () => h('div', { class: 'business-fields' }, '业务字段') },
      global: { stubs: { AModal: ModalStub, AForm: FormStub } },
    })

    expect(wrapper.get('.business-fields').text()).toBe('业务字段')
    await wrapper.get('.ok').trigger('click')
    expect(validate).toHaveBeenCalledOnce()
    expect(wrapper.emitted('submit')?.[0]).toEqual([{ name: '一号楼' }])
    await wrapper.get('.cancel').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
  })
})
