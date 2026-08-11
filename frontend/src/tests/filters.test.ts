import { describe, expect, it } from 'vitest'
import { filterByKeyword, formatCurrency } from '../utils/filters'

describe('filters', () => {
  it('按多个字段过滤关键字', () => {
    const rows = [
      { id: 1, name: '张三', college: '计算机学院' },
      { id: 2, name: '李四', college: '外国语学院' },
    ]

    expect(filterByKeyword(rows, '计算机', ['name', 'college'])).toEqual([rows[0]])
  })

  it('空关键字返回原始列表', () => {
    const rows = [{ id: 1, name: '张三' }]

    expect(filterByKeyword(rows, ' ', ['name'])).toBe(rows)
  })

  it('忽略大小写并将空字段视为不匹配', () => {
    const rows = [
      { id: 1, name: 'Alice', college: null },
      { id: 2, name: 'Bob', college: undefined },
    ]

    expect(filterByKeyword(rows, 'ALICE', ['name', 'college'])).toEqual([rows[0]])
    expect(filterByKeyword(rows, '学院', ['college'])).toEqual([])
  })

  it('格式化金额为两位小数', () => {
    expect(formatCurrency(30)).toBe('30.00')
  })
})
