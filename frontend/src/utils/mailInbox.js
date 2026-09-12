/** 将邮箱列表和正文请求绑定到当前选择，防止旧请求覆盖新邮箱。 */
export function createInboxReader(http, state, onError) {
  let generation = 0
  let detailGeneration = 0
  return {
    /** 切换邮箱或刷新时清空旧邮件，并仅接受最新分页响应。 */
    async load(accountId, page = 1) {
      const current = ++generation
      detailGeneration++
      Object.assign(state, { accountId, page, items: [], total: 0, detail: null, uidValidity: '', loading: false, detailLoading: false })
      if (!accountId) return
      state.loading = true
      try {
        const { data } = await http.get(`/mail/inbox/${accountId}/messages`, { params: { page, size: 20 } })
        if (current === generation) Object.assign(state, { items: data.items, total: data.total, uidValidity: data.uidValidity })
      } catch (error) {
        if (current === generation) onError(error)
      } finally {
        if (current === generation) state.loading = false
      }
    },
    /** 正文必须使用当前列表返回的 UIDVALIDITY，选择改变后丢弃旧响应。 */
    async open(row) {
      const current = generation
      const detailRequest = ++detailGeneration
      state.detail = null
      state.detailLoading = true
      try {
        const { data } = await http.get(`/mail/inbox/${state.accountId}/messages/${row.uid}`, { params: { uidValidity: state.uidValidity } })
        if (current === generation && detailRequest === detailGeneration) state.detail = data
      } catch (error) {
        if (current === generation && detailRequest === detailGeneration) onError(error)
      } finally {
        if (current === generation && detailRequest === detailGeneration) state.detailLoading = false
      }
    },
    /** 离开页面后使所有在途响应失效。 */
    dispose() { generation++; detailGeneration++ }
  }
}
