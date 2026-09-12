<template>
  <div class="panel">
    <div class="section-head"><div><h2>{{ t('mailInbox.title') }}</h2><p>{{ t('mailInbox.description') }}</p></div></div>
    <div class="inbox-toolbar">
      <el-select v-model="selected" :placeholder="t('mailInbox.select')" @change="selectAccount">
        <el-option v-for="account in accounts" :key="account.id" :value="account.id" :label="`${account.name} (${account.address})${account.available ? '' : ' — ' + t('mailInbox.unavailable')}`" />
      </el-select>
      <el-button :disabled="!available || state.loading" @click="reader.load(selected, state.page)">{{ t('mailInbox.refresh') }}</el-button>
    </div>
    <el-empty v-if="!accounts.length" :description="t('mailInbox.noAccounts')" />
    <el-alert v-else-if="selected && !available" :title="t('mailInbox.unavailable')" type="info" :closable="false" />
    <template v-else-if="selected">
      <el-table v-loading="state.loading" :data="state.items" :empty-text="t('mailInbox.empty')" @row-click="reader.open">
        <el-table-column :label="t('mailInbox.subject')" min-width="220"><template #default="scope"><el-button link type="primary" @click.stop="reader.open(scope.row)">{{ scope.row.subject || t('mailInbox.noSubject') }}</el-button></template></el-table-column>
        <el-table-column prop="from" :label="t('mailInbox.from')" min-width="200" />
        <el-table-column prop="date" :label="t('mailInbox.date')" min-width="200" />
      </el-table>
      <el-pagination :current-page="state.page" :page-size="20" :total="state.total" layout="prev, pager, next, total" :disabled="state.loading" @current-change="page => reader.load(selected, page)" />
      <section v-loading="state.detailLoading" class="inbox-detail">
        <template v-if="state.detail">
          <h3>{{ state.detail.subject || t('mailInbox.noSubject') }}</h3>
          <p>{{ t('mailInbox.from') }}: {{ state.detail.from }}</p>
          <p>{{ t('mailInbox.to') }}: {{ state.detail.to }}</p>
          <p>{{ t('mailInbox.date') }}: {{ state.detail.date }}</p>
          <el-alert v-if="state.detail.truncated" :title="t('mailInbox.truncated')" type="warning" :closable="false" />
          <pre>{{ state.detail.body || t('mailInbox.noBody') }}</pre>
        </template>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { createInboxReader } from '../utils/mailInbox'

const { t } = useI18n()
const accounts = ref([])
const selected = ref(null)
const state = reactive({ accountId: null, page: 1, items: [], total: 0, uidValidity: '', detail: null, loading: false, detailLoading: false })
const reader = createInboxReader(http, state, error => showHttpError(error))
const available = computed(() => accounts.value.find(account => account.id === selected.value)?.available)
/** 切换邮箱时立即清理先前列表和正文。 */
function selectAccount() { return reader.load(available.value ? selected.value : null) }
/** 初始化服务端已按角色过滤的邮箱列表。 */
onMounted(async () => {
  try {
    accounts.value = (await http.get('/mail/inbox/accounts')).data || []
    selected.value = accounts.value.find(account => account.available)?.id || accounts.value[0]?.id || null
    await selectAccount()
  } catch (error) { showHttpError(error) }
})
onBeforeUnmount(() => reader.dispose())
</script>

<style scoped>
.inbox-toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.inbox-toolbar .el-select { width: min(520px, 75%); }
.inbox-detail { min-height: 60px; overflow-wrap: anywhere; }
.inbox-detail pre { white-space: pre-wrap; overflow-wrap: anywhere; font-family: inherit; }
</style>
