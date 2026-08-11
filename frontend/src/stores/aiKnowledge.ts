import { defineStore } from 'pinia'
import { getAiClient } from '../api/ai-client'
import type { AiKnowledgeJob, AiKnowledgeSource, AiKnowledgeUpload, AiKnowledgeVersion } from '../types/ai'

interface KnowledgeState {
  sources: AiKnowledgeSource[]
  total: number
  selectedSourceId: string
  selectedSource: AiKnowledgeSource | null
  uploadText: string
  upload: AiKnowledgeUpload | null
  version: AiKnowledgeVersion | null
  job: AiKnowledgeJob | null
  loading: boolean
  selecting: boolean
  saving: boolean
  jobRefreshing: boolean
  error: string | null
}

const sessionEpochs = new WeakMap<object, number>()
const sourceListEpochs = new WeakMap<object, number>()
const sourceSelectionEpochs = new WeakMap<object, number>()
const mutationEpochs = new WeakMap<object, number>()
const jobRefreshEpochs = new WeakMap<object, number>()

function currentEpoch(epochs: WeakMap<object, number>, target: object) {
  return epochs.get(target) ?? 0
}

function nextEpoch(epochs: WeakMap<object, number>, target: object) {
  const next = currentEpoch(epochs, target) + 1
  epochs.set(target, next)
  return next
}

async function sha256(value: string) {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

export const useAiKnowledgeStore = defineStore('aiKnowledge', {
  state: (): KnowledgeState => ({
    sources: [], total: 0, selectedSourceId: '', selectedSource: null, uploadText: '', upload: null,
    version: null, job: null, loading: false, selecting: false, saving: false, jobRefreshing: false, error: null,
  }),
  actions: {
    async loadSources(page = 1, pageSize = 20) {
      const client = getAiClient()
      if (!client.listKnowledgeSources) throw new Error('当前 AI client 不支持知识管理')
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const requestEpoch = nextEpoch(sourceListEpochs, this)
      this.loading = true; this.error = null
      try {
        const result = await client.listKnowledgeSources({ page, pageSize })
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(sourceListEpochs, this) !== requestEpoch) return
        this.sources = result.records; this.total = result.total
        if (!this.selectedSourceId && this.sources[0]) await this.selectSource(this.sources[0].id)
      } catch (error) {
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(sourceListEpochs, this) !== requestEpoch) return
        this.error = error instanceof Error ? error.message : '知识来源加载失败'
        throw error
      } finally {
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(sourceListEpochs, this) === requestEpoch) this.loading = false
      }
    },
    async selectSource(id: string) {
      const client = getAiClient()
      if (!client.getKnowledgeSource) throw new Error('当前 AI client 不支持知识详情')
      if (this.saving) throw new Error('知识操作正在进行，暂不能切换来源')
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const selectionEpoch = nextEpoch(sourceSelectionEpochs, this)
      this.selectedSourceId = id
      this.selectedSource = null
      this.upload = null; this.version = null; this.job = null; this.uploadText = ''
      this.selecting = true; this.error = null
      try {
        const selected = await client.getKnowledgeSource(id)
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(sourceSelectionEpochs, this) !== selectionEpoch
          || this.selectedSourceId !== id) return
        if (selected.id !== id) throw new Error('知识来源详情响应与选择不匹配')
        this.selectedSource = selected
        return selected
      } catch (error) {
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(sourceSelectionEpochs, this) !== selectionEpoch
          || this.selectedSourceId !== id) return
        this.error = error instanceof Error ? error.message : '知识详情加载失败'
        throw error
      } finally {
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(sourceSelectionEpochs, this) === selectionEpoch) this.selecting = false
      }
    },
    async createSource(input: { name: string; classification: 'L0' | 'L1' | 'L2'; matchMode: 'ANY' | 'ALL'; permissions: string[] }) {
      const client = getAiClient()
      if (!client.createKnowledgeSource) throw new Error('当前 AI client 不支持创建知识来源')
      if (this.saving) throw new Error('知识操作正在进行，请稍候')
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const mutationEpoch = nextEpoch(mutationEpochs, this)
      this.saving = true; this.error = null
      try {
        const source = await client.createKnowledgeSource(input)
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(mutationEpochs, this) !== mutationEpoch) return source
        nextEpoch(sourceSelectionEpochs, this)
        this.sources.unshift(source); this.total += 1
        this.selectedSourceId = source.id; this.selectedSource = source
        this.upload = null; this.version = null; this.job = null; this.uploadText = ''
        return source
      } catch (error) {
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(mutationEpochs, this) !== mutationEpoch) return
        this.error = error instanceof Error ? error.message : '知识来源创建失败'
        throw error
      } finally {
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(mutationEpochs, this) === mutationEpoch) this.saving = false
      }
    },
    async updateSelectedSource(input: {
      name?: string
      classification?: 'L0' | 'L1' | 'L2'
      matchMode?: 'ANY' | 'ALL'
      status?: 'ACTIVE' | 'PAUSED'
      permissions?: string[]
    }) {
      const client = getAiClient()
      if (!this.selectedSource || !client.updateKnowledgeSource) {
        throw new Error('当前 AI client 不支持更新知识来源')
      }
      if (this.saving) throw new Error('知识操作正在进行，请稍候')
      if (this.selectedSource.id !== this.selectedSourceId) throw new Error('知识来源选择状态不一致，请刷新后重试')
      const selected = this.selectedSource
      const sourceId = selected.id
      const aclVersion = selected.aclVersion
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const mutationEpoch = nextEpoch(mutationEpochs, this)
      this.saving = true; this.error = null
      try {
        const updated = await client.updateKnowledgeSource(sourceId, {
          expectedAclVersion: aclVersion,
          ...input,
        })
        if (updated.id !== sourceId) throw new Error('知识来源更新响应与提交对象不匹配')
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(mutationEpochs, this) !== mutationEpoch) return updated
        const index = this.sources.findIndex((source) => source.id === sourceId)
        if (index >= 0) this.sources[index] = updated
        if (this.selectedSourceId === sourceId && this.selectedSource?.id === sourceId) {
          this.selectedSource = updated
        }
        return updated
      } catch (error) {
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(mutationEpochs, this) !== mutationEpoch) return
        this.error = error instanceof Error ? error.message : '知识来源更新失败'
        throw error
      } finally {
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(mutationEpochs, this) === mutationEpoch) this.saving = false
      }
    },
    async ingestText(input: { externalKey: string; title: string; version: string }) {
      const client = getAiClient()
      const content = this.uploadText
      const sourceId = this.selectedSourceId
      if (!sourceId) throw new Error('请先选择知识来源')
      if (!content.trim()) throw new Error('知识正文不能为空')
      if (!client.createKnowledgeUpload || !client.putKnowledgeUploadContent || !client.finalizeKnowledgeUpload
        || !client.createKnowledgeVersion || !client.getKnowledgeVersion || !client.getKnowledgeJob) {
        throw new Error('当前 AI client 不支持知识摄取')
      }
      if (this.saving) throw new Error('知识操作正在进行，请稍候')
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const mutationEpoch = nextEpoch(mutationEpochs, this)
      const isCurrentSession = () => currentEpoch(sessionEpochs, this) === sessionEpoch
        && currentEpoch(mutationEpochs, this) === mutationEpoch
      this.saving = true; this.error = null
      try {
        const bytes = new TextEncoder().encode(content)
        const upload = await client.createKnowledgeUpload(sourceId, {
          mimeType: 'text/plain', sizeBytes: bytes.byteLength, sha256: await sha256(content),
        })
        if (!isCurrentSession()) return
        await client.putKnowledgeUploadContent(upload, content)
        if (!isCurrentSession()) return
        const finalized = await client.finalizeKnowledgeUpload(upload.id)
        if (!isCurrentSession()) return
        if (finalized.state !== 'FINALIZED' || finalized.observedSizeBytes !== bytes.byteLength) {
          throw new Error('知识上传完成证明不一致')
        }
        const scheduled = await client.createKnowledgeVersion(sourceId, { uploadSessionId: upload.id, ...input })
        if (!isCurrentSession()) return scheduled
        const loadedVersion = await client.getKnowledgeVersion(scheduled.versionId)
        if (!isCurrentSession()) return scheduled
        const loadedJob = await client.getKnowledgeJob(scheduled.jobId)
        if (!isCurrentSession()) return scheduled
        if (loadedVersion.id !== scheduled.versionId || loadedJob.id !== scheduled.jobId
          || loadedJob.versionId !== scheduled.versionId) {
          throw new Error('知识摄取结果与调度对象不匹配')
        }
        if (this.selectedSourceId === sourceId) {
          this.upload = upload
          this.version = loadedVersion
          this.job = loadedJob
          if (this.uploadText === content) this.uploadText = ''
        }
        return scheduled
      } catch (error) {
        if (!isCurrentSession()) return
        this.error = error instanceof Error ? error.message : '知识摄取失败'
        throw error
      } finally {
        if (isCurrentSession()) this.saving = false
      }
    },
    async refreshJob() {
      const client = getAiClient()
      if (!this.job || !client.getKnowledgeJob || this.jobRefreshing) return
      const sourceId = this.selectedSourceId
      const jobId = this.job.id
      const versionId = this.version?.id
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const refreshEpoch = nextEpoch(jobRefreshEpochs, this)
      const isCurrentRefresh = () => currentEpoch(sessionEpochs, this) === sessionEpoch
        && currentEpoch(jobRefreshEpochs, this) === refreshEpoch
      this.jobRefreshing = true
      this.error = null
      try {
        const loadedJob = await client.getKnowledgeJob(jobId)
        if (!isCurrentRefresh()) return
        const loadedVersion = versionId && client.getKnowledgeVersion
          ? await client.getKnowledgeVersion(versionId)
          : null
        if (!isCurrentRefresh()
          || this.selectedSourceId !== sourceId || this.job?.id !== jobId) return
        if (loadedJob.id !== jobId || (loadedVersion && loadedVersion.id !== versionId)) {
          throw new Error('知识任务刷新响应与当前对象不匹配')
        }
        this.job = loadedJob
        if (loadedVersion && this.version?.id === versionId) this.version = loadedVersion
      } catch (error) {
        if (!isCurrentRefresh()) return
        this.error = error instanceof Error ? error.message : '知识任务刷新失败'
        throw error
      } finally {
        if (isCurrentRefresh()) this.jobRefreshing = false
      }
    },
    async activate() {
      const client = getAiClient()
      if (!this.version || !client.activateKnowledgeVersion) return
      if (this.saving) throw new Error('知识操作正在进行，请稍候')
      const sourceId = this.selectedSourceId
      const versionId = this.version.id
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const mutationEpoch = nextEpoch(mutationEpochs, this)
      this.saving = true; this.error = null
      try {
        await client.activateKnowledgeVersion(versionId)
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(mutationEpochs, this) === mutationEpoch
          && this.selectedSourceId === sourceId && this.version?.id === versionId) {
          this.version = { ...this.version, status: 'ACTIVE' }
        }
      } catch (error) {
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(mutationEpochs, this) !== mutationEpoch) return
        this.error = error instanceof Error ? error.message : '知识版本激活失败'
        throw error
      } finally {
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(mutationEpochs, this) === mutationEpoch) this.saving = false
      }
    },
    async retire() {
      const client = getAiClient()
      if (!this.version || !client.retireKnowledgeVersion) return
      if (this.saving) throw new Error('知识操作正在进行，请稍候')
      const sourceId = this.selectedSourceId
      const versionId = this.version.id
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const mutationEpoch = nextEpoch(mutationEpochs, this)
      this.saving = true; this.error = null
      try {
        await client.retireKnowledgeVersion(versionId)
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(mutationEpochs, this) === mutationEpoch
          && this.selectedSourceId === sourceId && this.version?.id === versionId) {
          this.version = { ...this.version, status: 'RETIRED' }
        }
      } catch (error) {
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(mutationEpochs, this) !== mutationEpoch) return
        this.error = error instanceof Error ? error.message : '知识版本退休失败'
        throw error
      } finally {
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(mutationEpochs, this) === mutationEpoch) this.saving = false
      }
    },
    async approvePublic(password: string) {
      const client = getAiClient()
      if (!this.version || !client.approvePublicKnowledgeVersion) {
        throw new Error('当前 AI client 不支持知识公开审批')
      }
      const approvalSnapshotHash = this.version.approvalSnapshotHash
      if (!approvalSnapshotHash) throw new Error('知识公开审批快照缺失')
      if (this.saving) throw new Error('知识操作正在进行，请稍候')
      const sourceId = this.selectedSourceId
      const selectedVersion = this.version
      const versionId = selectedVersion.id
      const contentHash = selectedVersion.contentHash
      const sessionEpoch = currentEpoch(sessionEpochs, this)
      const mutationEpoch = nextEpoch(mutationEpochs, this)
      this.saving = true; this.error = null
      try {
        await client.approvePublicKnowledgeVersion(versionId, {
          contentHash,
          approvalSnapshotHash,
          password,
        })
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(mutationEpochs, this) !== mutationEpoch) return
        const refreshed = client.getKnowledgeVersion ? await client.getKnowledgeVersion(versionId) : null
        if (refreshed && refreshed.id !== versionId) throw new Error('知识公开审批响应与固定版本不匹配')
        if (refreshed && currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(mutationEpochs, this) === mutationEpoch
          && this.selectedSourceId === sourceId && this.version?.id === versionId) {
          this.version = refreshed
        }
      } catch (error) {
        if (currentEpoch(sessionEpochs, this) !== sessionEpoch
          || currentEpoch(mutationEpochs, this) !== mutationEpoch) return
        this.error = error instanceof Error ? error.message : '知识公开审批失败'
        throw error
      } finally {
        if (currentEpoch(sessionEpochs, this) === sessionEpoch
          && currentEpoch(mutationEpochs, this) === mutationEpoch) this.saving = false
      }
    },
    resetSession() {
      nextEpoch(sessionEpochs, this)
      nextEpoch(sourceListEpochs, this)
      nextEpoch(sourceSelectionEpochs, this)
      nextEpoch(mutationEpochs, this)
      nextEpoch(jobRefreshEpochs, this)
      this.$reset()
    },
  },
})
