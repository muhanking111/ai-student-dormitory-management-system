import { afterEach, describe, expect, it, vi } from 'vitest'
import { fetchDormitoryData } from './dormitory'

function apiResponse(data: unknown) {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: 0, message: 'success', data }),
  }
}

function dormitory(id: number) {
  return {
    id,
    name: `${id}号宿舍`,
    type: '男生宿舍',
    building: '1号楼',
    beds: 4,
    occupied: id,
    vacant: 4 - id,
    status: '入住中',
  }
}

describe('dashboard dormitory api', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('根据服务端总数翻页获取全部宿舍', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = new URL(String(input), 'http://localhost')
      const page = Number(url.searchParams.get('page'))
      const data = page === 1
        ? { records: [dormitory(1)], total: 201, page: 1, pageSize: 100 }
        : { records: [dormitory(page)], total: 201, page, pageSize: 100 }
      return apiResponse(data)
    })
    vi.stubGlobal('fetch', fetchMock)

    const data = await fetchDormitoryData(['dormitory:read'])

    expect(data.dormitories.map((item) => item.id)).toEqual([1, 2, 3])
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toEqual(expect.arrayContaining([
      '/api/dormitories?page=1&pageSize=100',
      '/api/dormitories?page=2&pageSize=100',
      '/api/dormitories?page=3&pageSize=100',
    ]))
  })

  it('使用核心分页接口的服务端总数生成待办并只查询已发布公告', async () => {
    const fetchMock = vi.fn(async (input: string | URL | Request) => {
      const url = new URL(String(input), 'http://localhost')
      const status = url.searchParams.get('status')
      const emptyPage = { records: [], total: 0, page: 1, pageSize: 100 }

      if (url.pathname === '/api/repair-orders') {
        const total = status === '待处理' ? 7 : status === '处理中' ? 3 : 20
        return apiResponse({ ...emptyPage, total })
      }
      if (url.pathname === '/api/payment-bills') {
        const total = status === '未缴' ? 5 : status === '部分缴' ? 2 : 30
        return apiResponse({ ...emptyPage, total })
      }
      if (url.pathname === '/api/check-in-applications') {
        return apiResponse({ ...emptyPage, total: status === '待审核' ? 4 : 40 })
      }
      if (url.pathname === '/api/notices') {
        return apiResponse({
          records: [{ id: 1, title: '已发布通知', type: '宿舍通知', date: '2026-07-11', publisher: '管理员', status: '已发布' }],
          total: 1,
          page: 1,
          pageSize: 5,
        })
      }
      throw new Error(`未预期请求: ${url.pathname}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const data = await fetchDormitoryData(['repair:read', 'payment:read', 'checkin:read', 'notice:read'])

    expect(data.pendingCounts).toEqual({ repairs: 10, applications: 4, payments: 7 })
    expect(data.notices.map((notice) => notice.status)).toEqual(['已发布'])
    const urls = fetchMock.mock.calls.map(([url]) => new URL(String(url), 'http://localhost'))
    expect(urls.some((url) => url.pathname === '/api/repairs')).toBe(false)
    expect(urls.some((url) => url.pathname === '/api/payments')).toBe(false)
    expect(urls.filter((url) => url.pathname === '/api/notices')
      .every((url) => url.searchParams.get('status') === '已发布')).toBe(true)
  })
})
