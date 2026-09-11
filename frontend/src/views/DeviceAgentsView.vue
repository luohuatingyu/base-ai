<template>
  <div class="device-agents-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>{{ $t('deviceAgents.description') }}</span>
          <!-- 接入入口对任何部署都可用：Agent 装在远程 Mac 上，控制台跑在哪台机器与能否接入无关 -->
          <div>
            <el-button @click="$router.push('/automation/device-agents/config-guide')">
              {{ $t('deviceAgentGuide.entry') }}
            </el-button>
            <el-button @click="$router.push('/automation/device-agents/onboarding')">
              {{ $t('deviceAgentOnboarding.entry') }}
            </el-button>
            <el-button v-if="can('create')" type="primary" @click="handleCreatePairing">
              {{ $t('deviceAgents.createPairing') }}
            </el-button>
          </div>
        </div>
      </template>

      <!-- Agent 配置管理与配对码管理分成两个 Tab；创建配对码等页面级操作留在卡片头部，两个 Tab 下都能触发 -->
      <el-tabs v-model="activeTab" class="device-agents-tabs" @tab-change="handleTabChange">
        <el-tab-pane :label="$t('deviceAgents.title')" name="agents">
          <!-- 搜索和筛选 -->
          <div class="filters">
            <el-input
              v-model="searchQuery"
              :placeholder="$t('deviceAgents.searchPlaceholder')"
              style="width: 300px"
              clearable
              @clear="loadAgents"
              @keyup.enter="loadAgents"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>

            <el-select
              v-model="statusFilter"
              :placeholder="$t('deviceAgents.statusFilter')"
              style="width: 200px; margin-left: 10px"
              clearable
              @change="loadAgents"
            >
              <el-option :label="$t('deviceAgents.status.paired')" value="PAIRED" />
              <el-option :label="$t('deviceAgents.status.pending')" value="PENDING" />
              <el-option :label="$t('deviceAgents.status.revoked')" value="REVOKED" />
            </el-select>

            <el-button type="primary" :icon="Search" @click="loadAgents" style="margin-left: 10px">
              {{ $t('common.search') }}
            </el-button>
          </div>

          <!-- Agent 列表 -->
          <el-table :data="agents" v-loading="loading" style="margin-top: 20px">
            <el-table-column :label="$t('deviceAgents.agentDeviceName')" min-width="180">
              <template #default="{ row }">
                <span>{{ row.deviceName || '-' }}</span>
                <el-button v-if="can('update') && !row.revokedAt" link type="primary" @click="handleEditDeviceName(row)">
                  {{ $t('deviceAgents.editDeviceName') }}
                </el-button>
              </template>
            </el-table-column>

            <!-- 默认标记跟着 Agent ID 走：控制台的环境检测、诊断下发和任务执行都以默认实例为准，
                 单独开一列会让「当前在用哪台」离标识太远 -->
            <el-table-column :label="$t('deviceAgents.agentId')" min-width="240">
              <template #default="{ row }">
                <span>{{ row.agentId }}</span>
                <el-tag v-if="row.isDefault" type="success" size="small" style="margin-left: 6px">
                  {{ $t('deviceAgents.defaultAgent') }}
                </el-tag>
              </template>
            </el-table-column>

            <!-- 回连地址列：这台 Agent 该连哪个后端入口是逐台绑定的，
                 未绑定的实例沿用平台全局 base-url，必须能一眼看出区别 -->
            <el-table-column :label="$t('deviceAgents.backendUrl')" min-width="240">
              <template #default="{ row }">
                <span v-if="row.backendUrl" class="backend-url-text">{{ row.backendUrl }}</span>
                <span v-else class="version-unknown">{{ $t('deviceAgents.backendUrlInherited') }}</span>
                <el-button
                  v-if="can('update') && !row.revokedAt"
                  link
                  type="primary"
                  @click="handleEditBackendUrl(row)"
                >
                  {{ $t('common.edit') }}
                </el-button>
              </template>
            </el-table-column>

            <el-table-column :label="$t('deviceAgents.status.title')" width="120">
              <template #default="{ row }">
                <el-tag :type="getStatusType(row.pairingStatus)">
                  {{ $t(`deviceAgents.status.${row.pairingStatus.toLowerCase()}`) }}
                </el-tag>
              </template>
            </el-table-column>

            <!-- 离线只是结论，原因挂在标签的浮层里：不给原因等于把排查起点藏起来 -->
            <el-table-column :label="$t('deviceAgents.onlineStatus')" width="120">
              <template #default="{ row }">
                <el-tag v-if="isAgentOnline(row)" type="success">{{ $t('deviceAgents.online') }}</el-tag>
                <el-popover v-else placement="top" :width="380" trigger="hover">
                  <template #reference>
                    <el-tag :type="isAuthRejected(row) ? 'danger' : 'info'" class="offline-tag">
                      {{ $t('deviceAgents.offline') }}
                    </el-tag>
                  </template>
                  <div class="offline-detail">
                    <div>{{ offlineReason(row) }}</div>
                    <div class="code-hint">{{ offlineAdvice(row) }}</div>
                  </div>
                </el-popover>
              </template>
            </el-table-column>

            <!-- 配置状态列：Agent 拿不到后台配置时会静默回退本地缓存继续运行，
                 页面上配置显示得好好的，Agent 实际用的却是另一套值。
                 没有这一列，这种失配只能靠翻 Mac 上的日志才能发现 -->
            <el-table-column :label="$t('deviceAgents.configStatus.title')" width="150">
              <template #default="{ row }">
                <el-tag v-if="!row.lastErrorCode" type="success" effect="plain">
                  {{ $t('deviceAgents.configStatus.delivered') }}
                </el-tag>
                <el-popover v-else placement="top" :width="360" trigger="hover">
                  <template #reference>
                    <el-tag :type="configStatusType(row)" class="offline-tag">
                      {{ configStatusLabel(row) }}
                    </el-tag>
                  </template>
                  <div class="offline-detail">
                    <div>{{ configStatusDetail(row) }}</div>
                    <div class="code-hint">{{ $t('deviceAgents.configStatus.advice') }}</div>
                  </div>
                </el-popover>
              </template>
            </el-table-column>

            <!-- 版本列同时承担"是否该升级"的提示：已是最新打勾，落后于服务端时标黄 -->
            <el-table-column :label="$t('deviceAgents.version')" width="190">
              <template #default="{ row }">
                <span v-if="!row.lastAgentVersion" class="version-unknown">
                  {{ $t('deviceAgents.versionUnknown') }}
                </span>
                <template v-else>
                  <span class="version-text">{{ row.lastAgentVersion }}</span>
                  <!-- 等待重启的标记要排在最前：此刻上报的还是旧版本号，
                       继续标黄"可升级"等于催用户再点一次已经做完的升级 -->
                  <el-tag v-if="isRestarting(row)" size="small" type="info" effect="plain">
                    {{ $t('deviceAgents.versionRestarting') }}
                  </el-tag>
                  <el-tag v-else-if="isLatestVersion(row)" size="small" type="success" effect="plain">
                    {{ $t('deviceAgents.versionLatest') }}
                  </el-tag>
                  <el-tag v-else-if="latestVersion" size="small" type="warning" effect="plain">
                    {{ $t('deviceAgents.versionOutdated') }}
                  </el-tag>
                </template>
              </template>
            </el-table-column>

            <el-table-column :label="$t('deviceAgents.features')" min-width="200">
              <template #default="{ row }">
                <el-tag
                  v-if="row.featureAutomation === 'ENABLED'"
                  type="success"
                  size="small"
                  style="margin-right: 5px"
                >
                  IDA
                </el-tag>
                <el-tag
                  v-if="row.featureDiagnostics === 'ENABLED'"
                  type="info"
                  size="small"
                  style="margin-right: 5px"
                >
                  {{ $t('deviceAgents.diagnostics') }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column :label="$t('deviceAgents.lastOnline')" width="180">
              <template #default="{ row }">
                {{ row.lastOnlineAt ? formatTime(row.lastOnlineAt) : '-' }}
              </template>
            </el-table-column>

            <!-- 操作列固定右侧且单元格禁止换行，列宽需容纳中英文最长文案，否则末尾按钮会被裁剪。
                 「重新颁发配对码」已下沉到配对码管理 Tab，这里只保留 Agent 自身的配置与生命周期操作 -->
            <el-table-column :label="$t('common.actions')" width="720" fixed="right">
              <template #default="{ row }">
                <div class="table-actions">
                  <!-- 只有已配对且未吊销的实例能设为默认，其余状态没有可用密钥，设了也收不到诊断 -->
                  <el-button
                    v-if="can('update') && !row.isDefault && row.pairingStatus === 'PAIRED' && !row.revokedAt"
                    link
                    type="primary"
                    @click="handleSetDefault(row)"
                  >
                    {{ $t('deviceAgents.setDefault') }}
                  </el-button>
                  <!-- 重新下发配置：配置状态告警的唯一自助出口。
                       Agent 只在启动和收到 UPDATE_CONFIG 时才重新拉取后台配置，
                       没有这个入口，用户改完配置只能重启 Mac 上的 Agent 才能生效 -->
                  <el-button
                    v-if="can('execute') && row.pairingStatus === 'PAIRED' && !row.revokedAt"
                    link
                    type="primary"
                    :loading="resendingAgentId === row.agentId"
                    @click="handleResendConfig(row)"
                  >
                    {{ $t('deviceAgents.configStatus.resend') }}
                  </el-button>
                  <!-- 升级按钮的禁用原因逐条区分并写进 tooltip，置灰时用户能直接看到是缺哪一项 -->
                  <el-tooltip v-if="can('execute')" :content="upgradeState(row).tip" placement="top">
                    <span class="upgrade-button-wrap">
                      <el-button
                        link
                        type="primary"
                        :disabled="upgradeState(row).disabled"
                        :loading="upgradingAgentId === row.agentId"
                        @click="handleUpgrade(row)"
                      >
                        {{ $t('deviceAgents.upgrade') }}
                      </el-button>
                    </span>
                  </el-tooltip>
                  <!-- 回退目标只列 Agent 本机实际保留的版本：服务端不归档历史包，其余版本切过去必然失败 -->
                  <el-dropdown
                    v-if="can('execute') && rollbackTargets(row).length"
                    trigger="click"
                    @command="(version) => handleRollback(row, version)"
                  >
                    <!-- 等待重启期间同样禁用回退：此刻切版本会打断正在生效的那一次切换 -->
                    <el-button
                      link
                      type="primary"
                      :disabled="Boolean(upgradingAgentId) || isRestarting(row)"
                    >
                      {{ $t('deviceAgents.rollback') }}
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <el-dropdown-item
                          v-for="version in rollbackTargets(row)"
                          :key="version"
                          :command="version"
                        >
                          {{ version }}
                        </el-dropdown-item>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                  <el-button link type="primary" @click="handleDevicePool(row)">
                    {{ $t('deviceAgents.devicePool.title') }}
                  </el-button>
                  <el-button link type="primary" @click="handleConfigWda(row)">
                    {{ $t('deviceAgents.configWda') }}
                  </el-button>
                  <el-button
                    v-if="can('update') && row.pairingStatus === 'PAIRED' && !row.revokedAt"
                    link
                    type="primary"
                    @click="handleOperationSpeed(row)"
                  >
                    {{ $t('deviceAgents.operationSpeed.title') }}
                  </el-button>
                  <el-button
                    v-if="row.pairingStatus === 'PAIRED' && !row.revokedAt"
                    link
                    type="primary"
                    @click="handleRegistry(row)"
                  >
                    {{ $t('deviceAgents.registry.title') }}
                  </el-button>
                  <el-button v-if="can('update')" link type="primary" @click="handleUpdateFeatures(row)">
                    {{ $t('deviceAgents.features') }}
                  </el-button>
                  <el-button v-if="can('delete') && !row.revokedAt" link type="danger" @click="handleRevoke(row)">
                    {{ $t('deviceAgents.revoke') }}
                  </el-button>
                  <el-button v-if="can('delete')" link type="danger" @click="handleDelete(row)">
                    {{ $t('common.delete') }}
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <!-- 分页 -->
          <el-pagination
            v-model:current-page="pagination.page"
            v-model:page-size="pagination.size"
            :page-sizes="[10, 20, 50, 100]"
            :total="pagination.total"
            layout="total, sizes, prev, pager, next, jumper"
            @size-change="loadAgents"
            @current-change="loadAgents"
            style="margin-top: 20px; justify-content: center"
          />
        </el-tab-pane>

        <!-- 配对码管理：列表只展示元数据，明文仅在「查看」弹窗内按需向后端单独索取 -->
        <el-tab-pane :label="$t('deviceAgents.pairingCodes')" name="pairingCodes">
          <div class="pairing-toolbar">
            <el-checkbox v-model="pairingIncludeInactive" @change="loadPairingCodes">
              {{ $t('deviceAgents.pairingIncludeInactive') }}
            </el-checkbox>
            <!-- 重新颁发是配对码生命周期操作，入口随配对码管理一起收敛到本 Tab -->
            <el-button v-if="can('create')" link type="primary" @click="openReissueDialog">
              {{ $t('deviceAgents.reissuePairing') }}
            </el-button>
            <el-button link type="primary" @click="loadPairingCodes">{{ $t('common.refresh') }}</el-button>
          </div>

          <div class="form-hint">{{ $t('deviceAgents.pairingCodesHint') }}</div>

          <el-table :data="pairingCodes" v-loading="pairingCodesLoading" style="margin-top: 12px">
            <el-table-column prop="agentId" :label="$t('deviceAgents.agentId')" min-width="200" />

            <el-table-column :label="$t('deviceAgents.status.title')" width="120">
              <template #default="{ row }">
                <el-tag :type="pairingStatusType(row.status)">
                  {{ $t(`deviceAgents.pairingStatus.${row.status.toLowerCase()}`) }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column :label="$t('deviceAgents.pairingRemaining')" width="140">
              <template #default="{ row }">
                {{ row.status === 'ACTIVE' ? formatRemaining(row.expiresAt) : '-' }}
              </template>
            </el-table-column>

            <el-table-column :label="$t('deviceAgents.features')" min-width="180">
              <template #default="{ row }">
                <el-tag v-for="feature in row.requestedFeatures" :key="feature" size="small" style="margin-right: 4px">
                  {{ $t(`deviceAgents.featureNames.${feature}`) }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column prop="failedAttempts" :label="$t('deviceAgents.pairingFailedAttempts')" width="110" />

            <el-table-column :label="$t('deviceAgents.createdAt')" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>

            <el-table-column :label="$t('common.actions')" width="180" fixed="right">
              <template #default="{ row }">
                <div class="table-actions">
                  <el-button v-if="row.status === 'ACTIVE'" link type="primary" @click="viewPairingCode(row)">
                    {{ $t('deviceAgents.pairingView') }}
                  </el-button>
                  <el-button v-if="can('delete') && row.status === 'ACTIVE'" link type="danger" @click="revokePairingCode(row)">
                    {{ $t('deviceAgents.pairingRevoke') }}
                  </el-button>
                  <!-- 仍可领取的配对码必须先作废：直接删除等于一次没有留痕的作废 -->
                  <el-button v-else-if="can('delete')" link type="danger" @click="deletePairingCodeRecord(row)">
                    {{ $t('common.delete') }}
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="pairingPagination.page"
            v-model:page-size="pairingPagination.size"
            :page-sizes="[10, 20, 50]"
            :total="pairingPagination.total"
            layout="total, sizes, prev, pager, next"
            @size-change="loadPairingCodes"
            @current-change="loadPairingCodes"
            style="margin-top: 20px; justify-content: center"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 创建配对码对话框 -->
    <el-dialog
      v-model="pairingDialogVisible"
      :title="pairingMode === 'reissue' ? $t('deviceAgents.reissuePairing') : $t('deviceAgents.createPairing')"
      width="640px"
    >
      <el-alert v-if="pairingMode === 'reissue'" type="warning" :closable="false" show-icon>
        <template #title>{{ $t('deviceAgents.reissuePairingTitle', { agentId: pairingForm.agentId }) }}</template>
        {{ $t('deviceAgents.reissuePairingHint') }}
      </el-alert>

      <el-form :model="pairingForm" label-width="120px">
        <el-form-item v-if="pairingMode === 'create'" :label="$t('deviceAgents.selectFeatures')">
          <el-checkbox-group v-model="pairingForm.features">
            <el-checkbox :label="PAIRING_FEATURES.diagnostics">{{ $t('deviceAgents.diagnostics') }}</el-checkbox>
            <el-checkbox :label="PAIRING_FEATURES.appiumWda">IDA</el-checkbox>
            <el-checkbox :label="PAIRING_FEATURES.autostart">{{ $t('deviceAgents.autostart') }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-form-item v-if="pairingMode === 'create'" :label="$t('deviceAgents.agentId')" required>
          <el-input v-model="pairingForm.agentId" maxlength="64"
                    :placeholder="$t('deviceAgents.agentIdPlaceholder')">
            <template #append>
              <el-button :loading="identitySuggestionLoading" @click="generatePairingIdentity">
                {{ $t('deviceAgents.generateAgentIdentity') }}
              </el-button>
            </template>
          </el-input>
          <div class="form-hint">{{ $t('deviceAgents.agentIdHint') }}</div>
        </el-form-item>

        <el-form-item v-if="pairingMode === 'create'" :label="$t('deviceAgents.agentDeviceName')">
          <el-input v-model="pairingForm.deviceName" maxlength="128"
                    :placeholder="$t('deviceAgents.agentDeviceNamePlaceholder')" />
          <div class="form-hint">{{ $t('deviceAgents.deviceMetadataOptionalHint') }}</div>
        </el-form-item>


        <el-form-item :label="$t('deviceAgents.backendUrl')">
          <el-input v-model="pairingForm.backendUrl" :placeholder="$t('deviceAgents.backendUrlPlaceholder')" />
          <div class="form-hint">{{ $t('deviceAgents.backendUrlHint') }}</div>
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.insecureEnv')">
          <el-switch v-model="pairingForm.insecure" @change="insecureTouched = true" />
          <div class="form-hint">{{ $t(insecureHintKeyFor(pairingForm.backendUrl)) }}</div>
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.npmRegistryOption')">
          <el-input v-model="pairingForm.npmRegistry" :placeholder="$t('deviceAgents.npmRegistryOptionPlaceholder')" />
          <div class="form-hint">{{ $t('deviceAgents.npmRegistryOptionHint') }}</div>
        </el-form-item>
      </el-form>

      <template v-if="pairingCode">
        <el-divider />
        <div class="pairing-code-display">
          <template v-if="installCommand">
            <div class="code-label">{{ $t('deviceAgents.installCommand') }}</div>
            <div class="install-command">
              <code>{{ installCommand }}</code>
              <el-button link type="primary" @click="copyInstallCommand">
                <el-icon><DocumentCopy /></el-icon>
              </el-button>
            </div>
            <div class="code-hint">{{ $t('deviceAgents.installCommandHint') }}</div>
            <el-divider />
          </template>
          <div class="code-label">{{ $t('deviceAgents.pairingCode') }}</div>
          <div class="code-value">
            {{ pairingCode }}
            <el-button link type="primary" @click="copyPairingCode">
              <el-icon><DocumentCopy /></el-icon>
            </el-button>
          </div>
          <div class="code-hint">
            {{ $t('deviceAgents.pairingCodeHint', { seconds: pairingExpiresIn }) }}
          </div>
        </div>
      </template>

      <template #footer>
        <el-button @click="pairingDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button v-if="can('create')" type="primary" @click="createPairingCode"
                   :disabled="pairingCode !== null || (pairingMode === 'create' && !pairingForm.agentId.trim())">
          {{ $t('deviceAgents.generate') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Agent ID 保持不可变；这里只允许维护用于人工识别的设备名称。 -->
    <el-dialog v-model="deviceNameDialogVisible" :title="$t('deviceAgents.editDeviceName')" width="480px">
      <el-form :model="deviceNameForm" label-width="120px">
        <el-form-item :label="$t('deviceAgents.agentId')">
          <span>{{ deviceNameForm.agentId }}</span>
        </el-form-item>
        <el-form-item :label="$t('deviceAgents.agentDeviceName')">
          <el-input v-model="deviceNameForm.deviceName" maxlength="128" show-word-limit
                    :placeholder="$t('deviceAgents.agentDeviceNamePlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deviceNameDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button v-if="can('update')" type="primary" :loading="deviceNameSaving" @click="saveDeviceName">
          {{ $t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Registry 由 Mac 上的 root LaunchDaemon 持有；管理端只展示脱敏状态并下发固定动作。 -->
    <el-dialog
      v-model="registryDialogVisible"
      :title="$t('deviceAgents.registry.dialogTitle')"
      width="720px"
      @closed="stopRegistryPolling"
    >
      <div v-loading="registryLoading">
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="$t('deviceAgents.agentId')" :span="2">
            {{ registryForm.agentId }}
          </el-descriptions-item>
          <el-descriptions-item :label="$t('deviceAgents.registry.desiredState')">
            <el-tag :type="registryStateType(registryForm.desiredState)">
              {{ registryStateLabel(registryForm.desiredState) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="$t('deviceAgents.registry.observedState')">
            <el-tag :type="registryStateType(registryForm.observedState)">
              {{ registryStateLabel(registryForm.observedState) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="$t('deviceAgents.registry.effectivePort')">
            {{ registryForm.effectivePort || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="$t('deviceAgents.registry.tunnelCount')">
            {{ registryForm.tunnelCount }}
          </el-descriptions-item>
          <el-descriptions-item :label="$t('deviceAgents.registry.helperVersion')">
            {{ registryForm.helperVersion || '-' }}
          </el-descriptions-item>
          <el-descriptions-item :label="$t('deviceAgents.registry.lastReportedAt')">
            {{ registryForm.lastReportedAt ? formatTime(registryForm.lastReportedAt) : '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="registryForm.lastErrorCode"
                                :label="$t('deviceAgents.registry.errorCode')" :span="2">
            <code>{{ registryForm.lastErrorCode }}</code>
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="registryForm.observedState === 'NOT_INSTALLED'"
          type="warning"
          :closable="false"
          show-icon
          :title="$t('deviceAgents.registry.notInstalledHint')"
          style="margin-top: 16px"
        />

        <el-form :model="registryForm" label-width="170px" style="margin-top: 20px">
          <el-form-item :label="$t('deviceAgents.registry.useGlobalPort')">
            <el-switch v-model="registryForm.useGlobalPort" :disabled="!can('update')" />
            <span class="form-hint registry-inline-hint">
              {{ $t('deviceAgents.registry.globalPortHint', { port: registryForm.defaultPort || '-' }) }}
            </span>
          </el-form-item>
          <el-form-item :label="$t('deviceAgents.registry.portOverride')">
            <el-input-number
              v-model="registryForm.portOverride"
              :min="1024"
              :max="65535"
              :disabled="!can('update') || registryForm.useGlobalPort"
            />
            <div class="form-hint">{{ $t('deviceAgents.registry.portHint') }}</div>
          </el-form-item>
        </el-form>
      </div>

      <template #footer>
        <el-button :disabled="registryLoading" @click="loadRegistry">
          {{ $t('common.refresh') }}
        </el-button>
        <el-button v-if="can('update')" :loading="registrySaving" @click="saveRegistryPort">
          {{ $t('common.save') }}
        </el-button>
        <el-button
          v-if="can('execute')"
          type="success"
          :loading="registryActionLoading === 'ONLINE'"
          :disabled="Boolean(registryActionLoading) || registryForm.desiredState === 'ONLINE'"
          @click="dispatchRegistryAction('ONLINE')"
        >
          {{ $t('deviceAgents.registry.online') }}
        </el-button>
        <el-button
          v-if="can('execute')"
          type="warning"
          :loading="registryActionLoading === 'RECREATE'"
          :disabled="Boolean(registryActionLoading) || registryForm.desiredState !== 'ONLINE'"
          @click="dispatchRegistryAction('RECREATE')"
        >
          {{ $t('deviceAgents.registry.recreate') }}
        </el-button>
        <el-button
          v-if="can('execute')"
          type="danger"
          :loading="registryActionLoading === 'OFFLINE'"
          :disabled="Boolean(registryActionLoading) || registryForm.desiredState === 'OFFLINE'"
          @click="dispatchRegistryAction('OFFLINE')"
        >
          {{ $t('deviceAgents.registry.offline') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 回连地址编辑：已配对 Agent 保存后自动下发改址命令，旧地址已失效时改用本机命令 -->
    <el-dialog
      v-model="backendUrlDialogVisible"
      :title="$t('deviceAgents.backendUrlEdit')"
      width="680px"
    >
      <el-form :model="backendUrlForm" label-width="120px">
        <el-form-item :label="$t('deviceAgents.agentId')">
          <span>{{ backendUrlForm.agentId }}</span>
        </el-form-item>
        <el-form-item :label="$t('deviceAgents.backendUrl')">
          <el-input v-model="backendUrlForm.backendUrl" :placeholder="$t('deviceAgents.backendUrlPlaceholder')" />
          <div class="form-hint">{{ $t('deviceAgents.backendUrlHint') }}</div>
        </el-form-item>
        <el-form-item :label="$t('deviceAgents.selfSignedEnv')">
          <el-switch v-model="backendUrlForm.selfSigned" />
          <div class="form-hint">{{ $t('deviceAgents.selfSignedEnvHint') }}</div>
        </el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" show-icon :title="$t('deviceAgents.backendUrlDispatchHint')" />

      <!-- 本机改址命令：旧地址已失效时后端触达不到 Agent，这条是唯一的修复路径。
           保存前就展示，避免用户改完地址、Agent 失联后再也找不到这条命令 -->
      <template v-if="setServerCommand">
        <el-divider />
        <div class="code-label">{{ $t('deviceAgents.backendUrlSetServerTitle') }}</div>
        <div class="install-command">
          <code>{{ setServerCommand }}</code>
          <el-button link type="primary" @click="copySetServerCommand">
            <el-icon><DocumentCopy /></el-icon>
          </el-button>
        </div>
        <div class="code-hint">{{ $t('deviceAgents.backendUrlSetServerHint') }}</div>
      </template>

      <template #footer>
        <el-button @click="backendUrlDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button v-if="can('update')" type="primary" :loading="backendUrlSaving" @click="saveBackendUrl">
          {{ $t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 查看配对码对话框：明文由后端按 id 单独下发，仅可领取状态可用 -->
    <el-dialog
      v-model="pairingViewDialogVisible"
      :title="$t('deviceAgents.pairingView')"
      width="640px"
      @closed="stopPairingViewCountdown"
    >
      <el-form :model="pairingViewForm" label-width="120px">
        <el-form-item :label="$t('deviceAgents.agentId')">
          <span>{{ pairingViewForm.agentId }}</span>
        </el-form-item>

        <!-- 安装命令所需参数没有随配对码存储，只能由用户在此重填 -->

        <!-- 地址是该 Agent 的绑定值，改动请回列表编辑：此处改了只会让命令与注册记录不一致 -->
        <el-form-item :label="$t('deviceAgents.backendUrl')">
          <span v-if="pairingViewForm.backendUrl" class="backend-url-text">{{ pairingViewForm.backendUrl }}</span>
          <span v-else class="version-unknown">{{ $t('deviceAgents.backendUrlInherited') }}</span>
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.insecureEnv')">
          <el-switch v-model="pairingViewForm.insecure" @change="insecureTouched = true" />
          <div class="form-hint">{{ $t(insecureHintKeyFor(pairingViewForm.backendUrl)) }}</div>
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.npmRegistryOption')">
          <el-input v-model="pairingViewForm.npmRegistry" :placeholder="$t('deviceAgents.npmRegistryOptionPlaceholder')" />
        </el-form-item>
      </el-form>

      <el-divider />
      <div class="pairing-code-display">
        <template v-if="pairingViewInstallCommand">
          <div class="code-label">{{ $t('deviceAgents.installCommand') }}</div>
          <div class="install-command">
            <code>{{ pairingViewInstallCommand }}</code>
            <el-button link type="primary" @click="copyPairingViewInstallCommand">
              <el-icon><DocumentCopy /></el-icon>
            </el-button>
          </div>
          <div class="code-hint">{{ $t('deviceAgents.installCommandHint') }}</div>
          <el-divider />
        </template>
        <div class="code-label">{{ $t('deviceAgents.pairingCode') }}</div>
        <div class="code-value">
          {{ pairingViewForm.pairingCode }}
          <el-button link type="primary" @click="copyPairingViewCode">
            <el-icon><DocumentCopy /></el-icon>
          </el-button>
        </div>
        <div class="code-hint">
          {{ $t('deviceAgents.pairingCodeHint', { seconds: pairingViewExpiresIn }) }}
        </div>
      </div>

      <template #footer>
        <el-button @click="pairingViewDialogVisible = false">{{ $t('common.close') }}</el-button>
      </template>
    </el-dialog>

    <!-- 重新颁发配对码：选择目标 Agent 后复用既有配对码对话框 -->
    <el-dialog
      v-model="reissueDialogVisible"
      :title="$t('deviceAgents.reissuePairing')"
      width="520px"
    >
      <div class="form-hint">{{ $t('deviceAgents.reissueSelectHint') }}</div>
      <el-select
        v-model="reissueAgentId"
        :placeholder="$t('deviceAgents.reissueSelectAgent')"
        :loading="reissueLoading"
        filterable
        style="width: 100%; margin-top: 12px"
      >
        <el-option
          v-for="agent in reissueCandidates"
          :key="agent.agentId"
          :label="agent.agentId"
          :value="agent.agentId"
        />
      </el-select>
      <template #footer>
        <el-button @click="reissueDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button v-if="can('create')" type="primary" @click="confirmReissue">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>

    <!-- 自动发现的物理设备池：一台 Mac Agent 可同时承载多台 iOS 设备。 -->
    <el-dialog
      v-model="devicePoolDialogVisible"
      :title="$t('deviceAgents.devicePool.dialogTitle', { agentId: devicePoolAgentId })"
      width="min(1180px, 96vw)"
    >
      <el-alert v-if="devicePoolDetectionState === 'DETECTING'" type="info" :closable="false"
                show-icon :title="$t('deviceAgents.devicePool.detecting')" />
      <el-alert v-else-if="devicePoolDetectionState === 'UNKNOWN'" type="warning" :closable="false"
                show-icon :title="$t('deviceAgents.devicePool.detectUnknown')"
                :description="devicePoolDetectionError" />
      <el-alert v-else type="info" :closable="false" show-icon
                :title="$t('deviceAgents.devicePool.hint')" />
      <el-table v-loading="devicePoolLoading" :data="devicePoolDevices" style="margin-top: 16px">
        <el-table-column :label="$t('deviceAgents.devicePool.device')" min-width="180">
          <template #default="{ row }">{{ devicePoolLabel(row) }}</template>
        </el-table-column>
        <el-table-column :label="$t('deviceAgents.devicePool.system')" width="150">
          <template #default="{ row }">{{ [row.platform, row.osVersion].filter(Boolean).join(' ') || '-' }}</template>
        </el-table-column>
        <el-table-column :label="$t('deviceAgents.devicePool.connection')" width="110">
          <template #default="{ row }">
            <el-tag :type="deviceConnectionTagType(row)">
              {{ $t(`deviceAgents.devicePool.connectionStates.${deviceConnectionState(row).toLowerCase()}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('deviceAgents.devicePool.connectionType')" width="110">
          <template #default="{ row }">
            {{ $t(`deviceAgents.devicePool.connectionTypes.${deviceConnectionType(row).toLowerCase()}`) }}
          </template>
        </el-table-column>
        <el-table-column label="IDA" width="110">
          <template #default="{ row }"><el-tag :type="deviceReadinessType(row.wdaStatus)">{{ row.wdaStatus }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="$t('deviceAgents.devicePool.wdaControl')" width="120">
          <template #default="{ row }">
            <el-tag :type="row.wdaRunning ? 'success' : 'info'">
              {{ $t(`deviceAgents.devicePool.wdaControlStates.${row.wdaRunning ? 'running' : 'stopped'}`) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('deviceAgents.devicePool.wdaPort')" width="185">
          <template #default="{ row }">
            <div>{{ $t('deviceAgents.devicePool.wdaPortConfigured') }}: {{ row.wdaLocalPort || '-' }}</div>
            <div>{{ $t('deviceAgents.devicePool.wdaPortObserved') }}: {{ row.observedWdaLocalPort || '-' }}</div>
            <el-tag size="small" :type="deviceWdaPortStateType(row)">
              {{ deviceWdaPortStateLabel(row) }}
            </el-tag>
            <div v-if="row.wdaPortErrorCode" class="form-hint">
              {{ deviceWdaPortError(row) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="$t('deviceAgents.devicePool.lastDetected')" width="180">
          <template #default="{ row }">{{ row.lastSeenAt ? formatTime(row.lastSeenAt) : '-' }}</template>
        </el-table-column>
        <el-table-column :label="$t('common.actions')" width="285" fixed="right">
          <template #default="{ row }">
            <el-button v-if="can('update')" link type="primary" @click="openDeviceWdaPortDialog(row)">
              {{ $t('deviceAgents.devicePool.wdaPortEdit') }}
            </el-button>
            <el-button v-if="can('execute') && row.wdaStatus === 'READY'" link type="success"
                       :disabled="deviceConnectionState(row) !== 'ONLINE' || row.status === 'BUSY'
                         || Boolean(setupWdaDeviceId || startWdaDeviceId)"
                       :loading="startWdaDeviceId === row.deviceId" @click="startDeviceWda(row)">
              {{ $t('deviceAgents.devicePool.startWda') }}
            </el-button>
            <el-button v-if="can('execute')" link type="primary"
                       :disabled="deviceConnectionState(row) !== 'ONLINE' || row.status === 'BUSY'
                         || Boolean(setupWdaDeviceId || startWdaDeviceId)"
                       :loading="setupWdaDeviceId === row.deviceId" @click="setupDeviceWda(row)">
              {{ $t('deviceAgents.devicePool.setupWda') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!devicePoolLoading && !devicePoolDevices.length"
                :description="$t('deviceAgents.devicePool.empty')" />
      <template #footer>
        <el-button v-if="can('execute')" type="primary" :loading="devicePoolDetecting" @click="detectDevicePool">
          {{ $t('deviceAgents.devicePool.detectNow') }}
        </el-button>
        <el-button @click="devicePoolDialogVisible = false">{{ $t('common.close') }}</el-button>
      </template>
    </el-dialog>

    <!-- 每台物理设备独立保存 WDA 本地端口；实际值由 Agent 同步确认。 -->
    <el-dialog
      v-model="deviceWdaPortDialogVisible"
      :title="$t('deviceAgents.devicePool.wdaPortDialogTitle', { device: deviceWdaPortForm.deviceLabel })"
      width="520px"
    >
      <el-form :model="deviceWdaPortForm" label-width="150px">
        <el-form-item :label="$t('deviceAgents.devicePool.wdaPort')">
          <el-input-number
            v-model="deviceWdaPortForm.wdaLocalPort"
            :min="1024"
            :max="65535"
            :step="1"
            style="width: 100%"
          />
          <div class="form-hint">{{ $t('deviceAgents.devicePool.wdaPortHint') }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deviceWdaPortDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button v-if="can('update')" type="primary" :loading="deviceWdaPortSaving" @click="saveDeviceWdaPort">
          {{ $t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- WDA 主机级签名与 Appium 基础配置对话框 -->
    <el-dialog
      v-model="wdaConfigDialogVisible"
      :title="$t('deviceAgents.wdaConfig')"
      width="700px"
    >
      <el-form :model="wdaConfigForm" label-width="180px" v-loading="wdaConfigLoading">
        <!-- 离线时给出真实原因与可执行动作，而不是笼统的「请确认进程在运行」 -->
        <el-alert
          v-if="!wdaConfigAgentOnline"
          :type="isAuthRejected(wdaConfigAgent) ? 'error' : 'warning'"
          :closable="false"
          show-icon
        >
          <template #title>{{ $t('deviceAgents.detectAgentOffline') }}</template>
          <div>{{ offlineReason(wdaConfigAgent) }}</div>
          <div class="code-hint">{{ offlineAdvice(wdaConfigAgent) }}</div>
          <div class="table-actions">
            <el-button link type="primary" @click="handleReissueFromWdaDialog">
              {{ $t('deviceAgents.reissuePairing') }}
            </el-button>
            <el-button link type="primary" @click="copyDiagnoseCommand">
              {{ $t('deviceAgents.copyDiagnoseCommand') }}
            </el-button>
          </div>
        </el-alert>

        <el-divider content-position="left">{{ $t('deviceAgents.wdaSigningConfig') }}</el-divider>

        <el-form-item :label="$t('deviceAgents.detectSigning')">
          <el-button v-if="can('execute')" :loading="signingDetectLoading" @click="detectSigningIdentity">
            {{ $t('deviceAgents.detectSigningButton') }}
          </el-button>
          <div class="form-hint">{{ $t('deviceAgents.detectSigningHint') }}</div>
        </el-form-item>

        <el-form-item v-if="signingCandidates.length > 1" :label="$t('deviceAgents.signingCandidates')">
          <el-select v-model="selectedSigningCandidate" style="width: 100%" @change="applySigningCandidate">
            <el-option
              v-for="(candidate, index) in signingCandidates"
              :key="index"
              :value="index"
              :label="candidateLabel(candidate)"
            />
          </el-select>
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.xcodeOrgId')">
          <el-input v-model="wdaConfigForm.xcodeOrgId" placeholder="5JLK47WS57" />
          <div class="form-hint">{{ $t('deviceAgents.xcodeOrgIdHint') }}</div>
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.signingIdentity')">
          <el-input v-model="wdaConfigForm.xcodeSigningId" placeholder="Apple Development" />
          <div class="form-hint">{{ $t('deviceAgents.signingIdentityHint') }}</div>
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.allowDeviceRegistration')">
          <el-switch v-model="wdaConfigForm.allowProvisioningDeviceRegistration" />
          <div class="form-hint">{{ $t('deviceAgents.allowDeviceRegistrationHint') }}</div>
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.updatedBundleId')">
          <el-input v-model="wdaConfigForm.updatedWdaBundleId" placeholder="com.company.wda" />
        </el-form-item>

        <el-divider content-position="left">{{ $t('deviceAgents.appiumConfig') }}</el-divider>

        <el-form-item :label="$t('deviceAgents.appiumServerUrl')">
          <el-input v-model="wdaConfigForm.appiumServerUrl" placeholder="http://localhost:4723" />
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.appiumWdaLocalPort')">
          <el-input-number v-model="wdaConfigForm.baseWdaLocalPort" :min="1024" :max="65535" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="wdaConfigDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button v-if="can('update')" type="primary" @click="saveWdaConfig">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <!-- Agent 级固定速度档位对话框 -->
    <el-dialog
      v-model="operationSpeedDialogVisible"
      :title="$t('deviceAgents.operationSpeed.dialogTitle')"
      width="1160px"
    >
      <div v-loading="operationSpeedLoading">
        <el-form :model="operationSpeedForm" label-width="120px">
          <el-form-item :label="$t('deviceAgents.agentId')">
            <span>{{ operationSpeedForm.agentId }}</span>
          </el-form-item>
          <el-form-item :label="$t('deviceAgents.operationSpeed.profile')">
            <el-select v-model="operationSpeedForm.operationSpeed" style="width: 220px">
              <el-option
                v-for="rule in OPERATION_SPEED_RULES"
                :key="rule.value"
                :label="$t(`deviceAgents.operationSpeed.profiles.${rule.value}`)"
                :value="rule.value"
              />
            </el-select>
          </el-form-item>
        </el-form>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          :title="$t('deviceAgents.operationSpeed.scope')"
          :description="$t('deviceAgents.operationSpeed.ruleHint')"
        />
        <el-table :data="OPERATION_SPEED_RULES" size="small" style="margin-top: 16px">
          <el-table-column :label="$t('deviceAgents.operationSpeed.profile')" width="110">
            <template #default="{ row }">
              {{ $t(`deviceAgents.operationSpeed.profiles.${row.value}`) }}
            </template>
          </el-table-column>
          <el-table-column :label="$t('deviceAgents.operationSpeed.actions')" min-width="185">
            <template #default="{ row }">
              {{ $t('deviceAgents.operationSpeed.intervalRate', {
                seconds: row.actionSeconds, rate: row.actionRate
              }) }}
            </template>
          </el-table-column>
          <el-table-column :label="$t('deviceAgents.operationSpeed.swipes')" min-width="165">
            <template #default="{ row }">
              {{ $t('deviceAgents.operationSpeed.intervalRate', {
                seconds: row.swipeSeconds, rate: row.swipeRate
              }) }}
            </template>
          </el-table-column>
          <el-table-column :label="$t('deviceAgents.operationSpeed.typing')" min-width="130">
            <template #default="{ row }">
              {{ $t('deviceAgents.operationSpeed.typingRate', { rate: row.typingRate }) }}
            </template>
          </el-table-column>
          <el-table-column :label="$t('deviceAgents.operationSpeed.wirelessProfile')" min-width="205">
            <template #default="{ row }">
              {{ $t('deviceAgents.operationSpeed.wirelessRule', {
                seconds: row.wirelessInterval, attempts: row.pageAttempts
              }) }}
            </template>
          </el-table-column>
          <el-table-column :label="$t('deviceAgents.operationSpeed.usbProfile')" min-width="205">
            <template #default="{ row }">
              {{ $t('deviceAgents.operationSpeed.usbRule', {
                seconds: row.usbInterval, attempts: row.pageAttempts
              }) }}
            </template>
          </el-table-column>
        </el-table>
      </div>
      <template #footer>
        <el-button @click="operationSpeedDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button
          v-if="can('update')"
          type="primary"
          :loading="operationSpeedSaving"
          :disabled="operationSpeedLoading"
          @click="saveOperationSpeed"
        >
          {{ $t('common.save') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 功能配置对话框 -->
    <el-dialog
      v-model="featuresDialogVisible"
      :title="$t('deviceAgents.updateFeatures')"
      width="500px"
    >
      <el-form :model="featuresForm" label-width="150px">
        <el-form-item :label="$t('deviceAgents.diagnostics')">
          <el-switch v-model="featuresForm.diagnostics" />
        </el-form-item>

        <el-form-item label="IDA">
          <el-switch v-model="featuresForm.appiumWda" />
        </el-form-item>

        <el-form-item :label="$t('deviceAgents.autostart')">
          <el-switch v-model="featuresForm.autostart" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="featuresDialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button v-if="can('update')" type="primary" @click="updateFeatures">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, DocumentCopy } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { appConfig, resolvePlatformBaseUrl, withBasePath } from '../config'
import { useAuthStore } from '../stores/auth'
// 必须使用统一 http 客户端：自动附加 X-CSRF-Token 并解包统一响应信封，裸 axios 会被后端 CSRF 校验 403 拒绝
import http, { showHttpError } from '../api/http'
// 安装命令构建与接入向导共用同一实现，避免两处拼装出不一致的命令
import {
  buildInstallCommand, buildDiagnoseCommand, buildSetServerCommand,
  detectSelfSignedDeployment, isPrivateOrigin
} from '../utils/deviceAgentInstallCommand'
import { createRegistryStatusPoller } from '../utils/deviceAgentRegistryPoller'

const { t } = useI18n()
const auth = useAuthStore()
// 将页面操作映射到设备 Agent 的细粒度权限。
const can = (action) => auth.hasPermission(`automation:device-agent:${action}`)
// 页面卸载标记：所有命令轮询循环在每次等待后检查，避免离开页面仍在后台请求
let pageAlive = true
// Agent 默认地址来自平台公开配置；缺省时仍保留当前前端的子路径挂载前缀。
const platformBaseUrl = resolvePlatformBaseUrl(window.location.origin, appConfig)

// 数据状态
const agents = ref([])
const loading = ref(false)
const searchQuery = ref('')
const statusFilter = ref('')
const pagination = reactive({
  page: 1,
  size: 10,
  total: 0
})

// Tab 状态：Agent 配置管理与配对码管理各占一页，默认停留在 Agent 列表
const activeTab = ref('agents')

// —— Agent 代码版本与远程升级 ——
// 服务端最新代码版本取自静态分发的运行时清单，与安装包同一次构建产出，无需后端中转
const AGENT_MANIFEST_URL = withBasePath('/agent-dist/runtime/manifest.env')
// 升级命令要下载代码包、装依赖并重启进程，比探测类命令慢得多
const UPGRADE_POLL_INTERVAL_MS = 3000
const UPGRADE_POLL_TIMEOUT_MS = 300000
// 升级完成到新版本重新上报之间的等待态：agentId -> { version, deadline }
// Agent 是先回写命令 COMPLETED 再重启的，那一刻 lastAgentVersion 还是旧值，
// 不记这一笔的话列表刷新回旧版本，升级按钮立刻又变成可点，用户会对着同一版本反复升级
const restartingAgents = reactive({})
// 重启后要等下一次健康心跳（60 秒一次）才会上报新版本，这里短轮询兜底刷新，免得要求用户手工刷页面
const RESTART_POLL_INTERVAL_MS = 10000
// 等待态的兜底时限：重启失败或心跳受阻时不能让按钮永久置灰
const RESTART_WAIT_TIMEOUT_MS = 240000
let restartTimer = null
const latestVersion = ref('')
const upgradingAgentId = ref('')
const resendingAgentId = ref('')

/**
 * 取版本号中 "+" 之后的代码内容哈希。
 *
 * 版本形如 20260826.0944+a1b2c3d，前段时间戳每次构建都会变化，
 * 只有哈希能真实反映代码是否改动；用整串比对会让重复构建也显示可升级。
 */
const versionHash = (version) => {
  const text = String(version || '')
  const separator = text.indexOf('+')
  return separator >= 0 ? text.slice(separator + 1) : ''
}

/** 读取服务端最新代码版本；拉取失败时留空，升级按钮一律置灰而不是误判可升级。 */
const loadLatestVersion = async () => {
  try {
    const response = await fetch(AGENT_MANIFEST_URL, { cache: 'no-store' })
    if (!response.ok) return
    const line = (await response.text()).split('\n')
      .find((item) => item.startsWith('AGENT_CODE_VERSION='))
    latestVersion.value = line ? line.slice('AGENT_CODE_VERSION='.length).trim() : ''
  } catch (_error) {
    latestVersion.value = ''
  }
}

/** 记录一台 Agent 进入"代码已切换、等待重启后重新上报"的窗口，并给出兜底解除时限。 */
const markRestarting = (agentId, version) => {
  restartingAgents[agentId] = { version, deadline: Date.now() + RESTART_WAIT_TIMEOUT_MS }
}

/**
 * 判断某台 Agent 是否仍在等待重启后重新上报版本。
 *
 * 只读判断：状态清理放在轮询的 pruneRestarting 里，避免在渲染期改动响应式数据。
 */
const isRestarting = (row) => {
  const pending = restartingAgents[row.agentId]
  if (!pending || Date.now() >= pending.deadline) return false
  return versionHash(row.lastAgentVersion) !== versionHash(pending.version)
}

/** 清理已生效或已超时的等待态，两种收场各给一次明确提示，不让用户对着置灰按钮猜。 */
const pruneRestarting = () => {
  const now = Date.now()
  for (const [agentId, pending] of Object.entries(restartingAgents)) {
    const row = agents.value.find((item) => item.agentId === agentId)
    if (row && versionHash(row.lastAgentVersion) === versionHash(pending.version)) {
      delete restartingAgents[agentId]
      ElMessage.success(t('deviceAgents.upgradeVersionConfirmed', { version: pending.version }))
    } else if (now >= pending.deadline) {
      // 超时不再无限置灰：可能重启失败或心跳受阻，把判断权交还给用户
      delete restartingAgents[agentId]
      ElMessage.warning(t('deviceAgents.upgradeRestartTimeout', { agentId }))
    }
  }
}

/** 启动等待态轮询；没有待确认的 Agent 时自动停表，避免常驻后台请求。 */
const startRestartWatch = () => {
  if (restartTimer) return
  // 上一次刷新未返回时跳过本轮：异步 setInterval 重叠会放大成请求风暴
  let watchInFlight = false
  restartTimer = setInterval(async () => {
    if (watchInFlight) return
    watchInFlight = true
    try {
      // 静默刷新：每 10 秒闪一次表格加载态会明显干扰阅读
      await loadAgents({ silent: true })
      pruneRestarting()
      if (!Object.keys(restartingAgents).length) stopRestartWatch()
    } finally {
      watchInFlight = false
    }
  }, RESTART_POLL_INTERVAL_MS)
}

/** 停止等待态轮询。 */
const stopRestartWatch = () => {
  if (!restartTimer) return
  clearInterval(restartTimer)
  restartTimer = null
}

/**
 * 判定某台 Agent 的升级按钮状态，返回 { disabled, tip }。
 *
 * 版本未知（旧版 Agent 上报固定值或根本没上报）时不置灰：它们确实需要升级，
 * 只是无法远程完成，点击后由命令结果给出"需在该 Mac 手工重装"的明确提示。
 */
const upgradeState = (row) => {
  if (!latestVersion.value) return { disabled: true, tip: t('deviceAgents.upgradeLatestUnknown') }
  if (row.pairingStatus !== 'PAIRED' || row.revokedAt) {
    return { disabled: true, tip: t('deviceAgents.upgradeNotPaired') }
  }
  // 等待重启的判断必须排在离线之前：重启期间 Agent 本来就会短暂离线，
  // 这时提示"请先恢复连接"是误导，用户真正需要知道的是升级已完成、正在等新版本上报
  if (isRestarting(row)) return { disabled: true, tip: t('deviceAgents.upgradeRestarting') }
  if (!isAgentOnline(row)) return { disabled: true, tip: t('deviceAgents.upgradeOffline') }
  if (upgradingAgentId.value) return { disabled: true, tip: t('deviceAgents.upgradeInProgress') }
  const current = versionHash(row.lastAgentVersion)
  if (current && current === versionHash(latestVersion.value)) {
    return { disabled: true, tip: t('deviceAgents.upgradeAlreadyLatest') }
  }
  return { disabled: false, tip: t('deviceAgents.upgradeAvailableTip', { version: latestVersion.value }) }
}

/** 当前版本是否已是服务端最新，用于版本列的标记展示。 */
const isLatestVersion = (row) => {
  const current = versionHash(row.lastAgentVersion)
  return Boolean(current && latestVersion.value && current === versionHash(latestVersion.value))
}

/** 可回退版本：排除当前正在运行的版本，只留真正能切过去的目标。 */
const rollbackTargets = (row) => (row.availableVersions || [])
  .filter((item) => item && item !== row.lastAgentVersion)

/**
 * 解析 Agent 回传的升级结果摘要。
 *
 * 新版 Agent 回 JSON {status, version, summary}；旧版或异常路径回的是纯文本，
 * 解析不出来时返回 null，按"已切换"处理——宁可多等一轮重启，也不要误报升级成功。
 */
const parseUpgradeResult = (summary) => {
  try {
    const parsed = JSON.parse(summary || '')
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch (_error) {
    return null
  }
}

/** 下发升级命令并轮询结果；targetVersion 为空表示升级到服务端最新版本。 */
const dispatchUpgrade = async (row, targetVersion) => {
  upgradingAgentId.value = row.agentId
  try {
    const created = await http.post('/automation/device-agents/commands', {
      agentId: row.agentId,
      commandType: 'UPGRADE',
      commandParams: targetVersion ? { targetVersion } : {}
    })
    const commandId = created.data.id
    const deadline = Date.now() + UPGRADE_POLL_TIMEOUT_MS
    while (Date.now() < deadline) {
      await sleep(UPGRADE_POLL_INTERVAL_MS)
      // 页面已卸载时立即退出：升级命令本身会继续执行，只是不再轮询结果
      if (!pageAlive) return
      const response = await http.get(`/automation/device-agents/commands/${commandId}`)
      const command = response.data
      if (command.status === 'COMPLETED') {
        const result = parseUpgradeResult(command.resultSummary)
        if (result && result.status === 'UNCHANGED') {
          // Agent 本来就在跑这个版本，进程不会重启：再报"升级成功"会让用户以为版本已经推进
          ElMessage.info(t('deviceAgents.upgradeUnchanged', { version: result.version || '' }))
          await loadAgents()
          return
        }
        ElMessage.success(t('deviceAgents.upgradeDispatched'))
        // 代码已切换但当前进程仍是旧版本，Agent 随后才重启，
        // 新版本号要等下一次心跳才会上报；这段时间按钮保持置灰，由轮询确认后自动解除
        markRestarting(row.agentId, (result && result.version) || targetVersion || latestVersion.value)
        await loadAgents()
        startRestartWatch()
        return
      }
      if (command.status === 'FAILED' || command.status === 'EXPIRED' || command.status === 'CANCELLED') {
        if (/UNSUPPORTED_COMMAND|未知命令类型/.test(command.resultSummary || '')) {
          ElMessage.error(t('deviceAgents.upgradeAgentOutdated'))
          return
        }
        ElMessage.error(t('deviceAgents.upgradeFailed',
          { reason: command.resultSummary || command.errorCode || command.status }))
        return
      }
    }
    ElMessage.warning(t('deviceAgents.upgradeTimeout'))
  } catch (error) {
    showHttpError(error, 'deviceAgents.upgradeError')
  } finally {
    upgradingAgentId.value = ''
  }
}

/** 升级到最新版本，执行前二次确认。 */
const handleUpgrade = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('deviceAgents.upgradeConfirm', {
        agentId: row.agentId,
        from: row.lastAgentVersion || t('deviceAgents.versionUnknown'),
        to: latestVersion.value
      }),
      t('deviceAgents.upgradeTitle'),
      { type: 'warning' })
  } catch (_error) {
    return
  }
  await dispatchUpgrade(row, '')
}

/** 回退到指定的历史版本，执行前二次确认。 */
const handleRollback = async (row, targetVersion) => {
  try {
    await ElMessageBox.confirm(
      t('deviceAgents.rollbackConfirm', { agentId: row.agentId, version: targetVersion }),
      t('deviceAgents.rollbackTitle'),
      { type: 'warning' })
  } catch (_error) {
    return
  }
  await dispatchUpgrade(row, targetVersion)
}

/** 切到配对码 Tab 时才拉取列表：只看 Agent 的用户不必白发一次请求，切入时拿到的也总是最新数据。 */
const handleTabChange = (name) => {
  if (name === 'pairingCodes') loadPairingCodes()
}

// 配对码功能名必须与后端 DeviceAgentModels.VALID_FEATURES 完全一致，否则创建配对码会被后端拒绝
const PAIRING_FEATURES = {
  diagnostics: 'READ_ONLY_DIAGNOSTICS',
  appiumWda: 'APPIUM_WDA_AUTOMATION',
  autostart: 'AUTOSTART'
}
// 新建配对码时默认申请只读诊断和 WDA 自动化两项基础功能
const DEFAULT_PAIRING_FEATURES = [PAIRING_FEATURES.diagnostics, PAIRING_FEATURES.appiumWda]

// 配对码对话框
const pairingDialogVisible = ref(false)
// 新建时 Agent ID 同样非空，必须用独立模式区分新建和重新颁发，不能再拿字段是否为空判断
const pairingMode = ref('create')
const pairingForm = reactive({
  // 新建时允许手工填写或由服务端生成；重新颁发时沿用已有值且不展示编辑框
  agentId: '',
  // 设备名称只用于人工识别且不进入安装命令；点击生成身份时与 Agent ID 同步回填
  deviceName: '',
  features: [...DEFAULT_PAIRING_FEATURES],
  // 该 Agent 回连后端的公网地址，留空表示沿用平台全局 base-url
  backendUrl: '',
  insecure: false,
  // 高级选项只保留 npm 镜像；iOS 设备由 Agent 持续自动发现。
  npmRegistry: ''
})
// 重新颁发时打开对话框那一刻该 Agent 的绑定地址，用户改过才发更新请求
const pairingFormOriginalBackendUrl = ref('')
const pairingCode = ref(null)
const pairingExpiresIn = ref(600)
const identitySuggestionLoading = ref(false)
let pairingTimer = null

// 自签名探测结果：平台确实暴露内部根证书时才自动开启开关，避免生成指向 404 的 --ca-file
const selfSignedDetected = ref(false)
// 用户手动拨动过开关后不再被探测结果覆盖
const insecureTouched = ref(false)
let selfSignedDetection = null

/**
 * 判断该 Agent 是否走了独立于控制台 origin 的回连地址。
 *
 * 走独立地址时，控制台自身的证书结论对目标地址不成立：跨域探测必然失败，
 * 据此自动开启开关只会生成指向 404 的 --ca-file，证书情况一律交回人工判断。
 */
const usesCustomServer = (backendUrl) => {
  const bound = (backendUrl || '').trim().replace(/\/+$/, '')
  return Boolean(bound) && bound !== platformBaseUrl
}

// 探测每个页面实例只发一次请求，后续打开对话框直接复用结果
const ensureSelfSignedDetection = async () => {
  if (!selfSignedDetection) {
    selfSignedDetection = detectSelfSignedDeployment(platformBaseUrl).catch(() => false)
  }
  selfSignedDetected.value = await selfSignedDetection
  if (selfSignedDetected.value && !insecureTouched.value && !usesCustomServer(pairingForm.backendUrl)) {
    pairingForm.insecure = true
  }
}

// 开关提示四态：绑定了独立地址时说明探测不适用；已自动开启说明来源；
// 私网但未探测到根证书时提示手动开启；其余保持通用说明
const insecureHintKeyFor = (backendUrl) => {
  if (usesCustomServer(backendUrl)) return 'deviceAgents.insecureEnvCustomServer'
  if (selfSignedDetected.value) return 'deviceAgents.insecureEnvAutoDetected'
  if (isPrivateOrigin(platformBaseUrl)) return 'deviceAgents.insecureEnvSuggest'
  return 'deviceAgents.insecureEnvHint'
}

// 一键安装命令只负责通用设备 Agent 配对和本机自动化运行时安装。
const installCommand = computed(() => buildInstallCommand({
  origin: platformBaseUrl,
  backendUrl: pairingForm.backendUrl,
  pairingCode: pairingCode.value,
  selfSigned: pairingForm.insecure,
  npmRegistry: pairingForm.npmRegistry
}))

const copyInstallCommand = () => {
  navigator.clipboard.writeText(installCommand.value)
  ElMessage.success(t('common.copied'))
}

// 自动发现的设备池对话框；后端只返回匿名设备 ID，不向浏览器暴露原始 UDID。
const devicePoolDialogVisible = ref(false)
const devicePoolLoading = ref(false)
const devicePoolAgentId = ref('')
const devicePoolDevices = ref([])
const devicePoolDetecting = ref(false)
const devicePoolDetectionState = ref('IDLE')
const devicePoolDetectionError = ref('')
const setupWdaDeviceId = ref('')
const startWdaDeviceId = ref('')
const deviceWdaPortDialogVisible = ref(false)
const deviceWdaPortSaving = ref(false)
const deviceWdaPortForm = reactive({
  deviceId: '',
  deviceLabel: '',
  wdaLocalPort: 8100
})
const DEVICE_POOL_DETECT_POLL_INTERVAL_MS = 2000
const DEVICE_POOL_DETECT_TIMEOUT_MS = 120000
const SETUP_WDA_POLL_INTERVAL_MS = 5000
const SETUP_WDA_POLL_TIMEOUT_MS = 1200000
const START_WDA_POLL_INTERVAL_MS = 2000
const START_WDA_POLL_TIMEOUT_MS = 180000
let devicePoolDetectionGeneration = 0

/** 展示设备名称与型号；没有名称时仍能靠匿名 ID 前缀区分。 */
const devicePoolLabel = (device) => {
  const name = device.deviceName || device.model || t('deviceAgents.devicePool.unnamed')
  const model = device.model && device.model !== name ? ` · ${device.model}` : ''
  return `${name}${model} · ${String(device.deviceId || '').slice(0, 8)}`
}

/** 把设备应用就绪枚举映射为标签颜色。 */
const deviceReadinessType = (status) => status === 'READY' ? 'success'
  : status === 'MISSING' || status === 'ERROR' ? 'danger' : 'info'

/** 根据配置值、Agent 实际值和错误码区分已生效、待生效与失败。 */
const deviceWdaPortState = (device) => {
  if (device.wdaPortErrorCode) return 'failed'
  if (!Number.isInteger(device.wdaLocalPort) || !Number.isInteger(device.observedWdaLocalPort)) {
    return 'pending'
  }
  return device.wdaLocalPort === device.observedWdaLocalPort ? 'applied' : 'pending'
}

/** 为设备端口应用状态选择稳定颜色。 */
const deviceWdaPortStateType = (device) => {
  const state = deviceWdaPortState(device)
  return state === 'applied' ? 'success' : state === 'failed' ? 'danger' : 'warning'
}

/** 返回设备端口状态的本地化文案。 */
const deviceWdaPortStateLabel = (device) => {
  const keys = {
    applied: 'deviceAgents.devicePool.wdaPortApplied',
    pending: 'deviceAgents.devicePool.wdaPortPending',
    failed: 'deviceAgents.devicePool.wdaPortFailed'
  }
  return t(keys[deviceWdaPortState(device)])
}

/** 把 Agent 上报的稳定错误码翻译成页面可执行的排障提示。 */
const deviceWdaPortError = (device) => {
  const keys = {
    WDA_PORT_CONFLICT: 'deviceAgents.devicePool.wdaPortConflict',
    WDA_PORT_IN_USE: 'deviceAgents.devicePool.wdaPortInUse',
    WDA_PORT_CONFIG_INVALID: 'deviceAgents.devicePool.wdaPortConfigInvalid'
  }
  return keys[device.wdaPortErrorCode] ? t(keys[device.wdaPortErrorCode]) : device.wdaPortErrorCode
}

/** 打开设备端口编辑框；旧 Agent 未上报时沿用主机级 WDA 基准端口。 */
const openDeviceWdaPortDialog = (device) => {
  deviceWdaPortForm.deviceId = device.deviceId
  deviceWdaPortForm.deviceLabel = devicePoolLabel(device)
  deviceWdaPortForm.wdaLocalPort = device.wdaLocalPort || device.observedWdaLocalPort || 8100
  deviceWdaPortDialogVisible.value = true
}

/** 保存设备级端口并刷新配置值，实际值等待 Agent 下一轮安全应用后更新。 */
const saveDeviceWdaPort = async () => {
  const port = Number(deviceWdaPortForm.wdaLocalPort)
  if (!Number.isInteger(port) || port < 1024 || port > 65535) {
    ElMessage.warning(t('deviceAgents.devicePool.wdaPortInvalid'))
    return
  }
  deviceWdaPortSaving.value = true
  try {
    await http.put(`/automation/device-agents/${devicePoolAgentId.value}/devices/${deviceWdaPortForm.deviceId}/wda-port`, {
      wdaLocalPort: port
    })
    deviceWdaPortDialogVisible.value = false
    ElMessage.success(t('deviceAgents.devicePool.wdaPortSaved'))
    await loadDevicePool()
  } catch (error) {
    showHttpError(error, 'deviceAgents.devicePool.wdaPortSaveError')
  } finally {
    deviceWdaPortSaving.value = false
  }
}

/** 实时检测未完成或失败时，历史 connected 值不得继续充当当前状态。 */
const deviceConnectionState = (device) => {
  if (devicePoolDetectionState.value === 'DETECTING') return 'DETECTING'
  if (devicePoolDetectionState.value === 'UNKNOWN') return 'UNKNOWN'
  return device.connected ? 'ONLINE' : 'OFFLINE'
}

/** 根据当前连接结论选择标签颜色。 */
const deviceConnectionTagType = (device) => {
  const state = deviceConnectionState(device)
  return state === 'ONLINE' ? 'success' : state === 'OFFLINE' ? 'info' : 'warning'
}

/** 检测不确定时不展示历史接入方式，成功后只接受后端稳定枚举。 */
const deviceConnectionType = (device) => {
  if (['DETECTING', 'UNKNOWN'].includes(devicePoolDetectionState.value)) return 'UNKNOWN'
  return ['USB', 'WIRELESS'].includes(device.connectionType) ? device.connectionType : 'UNKNOWN'
}

/** 拉取指定 Mac Agent 最近一次上报的完整设备清单。 */
const loadDevicePool = async () => {
  if (!devicePoolAgentId.value) return false
  const agentId = devicePoolAgentId.value
  devicePoolLoading.value = true
  try {
    const response = await http.get(`/automation/device-agents/${agentId}/devices`)
    if (devicePoolAgentId.value === agentId) devicePoolDevices.value = response.data || []
    return true
  } catch (error) {
    showHttpError(error, 'deviceAgents.devicePool.loadError')
    return false
  } finally {
    if (devicePoolAgentId.value === agentId) devicePoolLoading.value = false
  }
}

/** 下发现场探测并等待 Agent 同步本轮结果；失败时显式废弃历史在线结论。 */
const detectDevicePool = async () => {
  if (!devicePoolAgentId.value || devicePoolDetecting.value) return
  const agentId = devicePoolAgentId.value
  const generation = ++devicePoolDetectionGeneration
  devicePoolDetecting.value = true
  devicePoolDetectionState.value = 'DETECTING'
  devicePoolDetectionError.value = ''
  try {
    const created = await http.post(`/automation/device-agents/${devicePoolAgentId.value}/devices/detect`)
    const deadline = Date.now() + DEVICE_POOL_DETECT_TIMEOUT_MS
    while (Date.now() < deadline && devicePoolDialogVisible.value
      && devicePoolAgentId.value === agentId && generation === devicePoolDetectionGeneration) {
      await sleep(DEVICE_POOL_DETECT_POLL_INTERVAL_MS)
      if (!pageAlive) return
      const response = await http.get(`/automation/device-agents/commands/${created.data.id}`)
      const command = response.data
      if (command.status === 'COMPLETED') {
        let result = null
        try {
          result = JSON.parse(command.resultSummary || '')
        } catch (_error) {
          result = null
        }
        // 旧 Agent 只回候选列表，不会把本轮探测同步到设备池；此时刷新列表仍是旧快照。
        if (result?.synchronized !== true) {
          devicePoolDetectionState.value = 'UNKNOWN'
          devicePoolDetectionError.value = t('deviceAgents.devicePool.detectAgentOutdated')
          ElMessage.warning(devicePoolDetectionError.value)
          return
        }
        const loaded = await loadDevicePool()
        if (loaded && generation === devicePoolDetectionGeneration) {
          devicePoolDetectionState.value = 'FRESH'
        } else if (generation === devicePoolDetectionGeneration) {
          devicePoolDetectionState.value = 'UNKNOWN'
          devicePoolDetectionError.value = t('deviceAgents.devicePool.loadError')
        }
        return
      }
      if (['FAILED', 'EXPIRED', 'CANCELLED'].includes(command.status)) {
        devicePoolDetectionState.value = 'UNKNOWN'
        devicePoolDetectionError.value = command.resultSummary || command.errorCode || command.status
        ElMessage.error(t('deviceAgents.devicePool.detectFailed', {
          reason: devicePoolDetectionError.value
        }))
        return
      }
    }
    if (devicePoolDialogVisible.value && generation === devicePoolDetectionGeneration) {
      devicePoolDetectionState.value = 'UNKNOWN'
      devicePoolDetectionError.value = t('deviceAgents.devicePool.detectTimeout')
      ElMessage.warning(devicePoolDetectionError.value)
    }
  } catch (error) {
    if (generation === devicePoolDetectionGeneration) {
      devicePoolDetectionState.value = 'UNKNOWN'
      devicePoolDetectionError.value = t('deviceAgents.devicePool.detectFailed', {
        reason: error.response?.data?.message || error.message || '-'
      })
      ElMessage.error(devicePoolDetectionError.value)
    }
  } finally {
    if (generation === devicePoolDetectionGeneration) devicePoolDetecting.value = false
  }
}

/** 打开一台 Mac Agent 的设备池并刷新其物理设备状态。 */
const handleDevicePool = async (agent) => {
  devicePoolDetectionGeneration += 1
  devicePoolDetecting.value = false
  devicePoolAgentId.value = agent.agentId
  devicePoolDevices.value = []
  devicePoolDetectionState.value = 'DETECTING'
  devicePoolDetectionError.value = ''
  devicePoolDialogVisible.value = true
  await loadDevicePool()
  if (devicePoolDialogVisible.value && devicePoolAgentId.value === agent.agentId) {
    devicePoolDetectionState.value = 'IDLE'
    await detectDevicePool()
  }
}

/** 向选定物理设备下发 WDA 构建，不允许多设备场景由 Agent 猜目标。 */
const setupDeviceWda = async (device) => {
  setupWdaDeviceId.value = device.deviceId
  try {
    const created = await http.post('/automation/device-agents/commands', {
      agentId: devicePoolAgentId.value,
      targetDeviceId: device.deviceId,
      commandType: 'SETUP_WDA'
    })
    const deadline = Date.now() + SETUP_WDA_POLL_TIMEOUT_MS
    while (Date.now() < deadline && devicePoolDialogVisible.value) {
      await sleep(SETUP_WDA_POLL_INTERVAL_MS)
      if (!pageAlive) return
      const response = await http.get(`/automation/device-agents/commands/${created.data.id}`)
      const command = response.data
      if (command.status === 'COMPLETED') {
        ElMessage.success(t('deviceAgents.devicePool.setupCompleted', { device: devicePoolLabel(device) }))
        await loadDevicePool()
        return
      }
      if (command.status === 'FAILED' || command.status === 'EXPIRED' || command.status === 'CANCELLED') {
        ElMessage.error(t('deviceAgents.devicePool.setupFailed', {
          reason: command.resultSummary || command.errorCode || command.status
        }))
        return
      }
    }
    if (devicePoolDialogVisible.value) ElMessage.warning(t('deviceAgents.devicePool.setupTimeout'))
  } catch (error) {
    showHttpError(error, 'deviceAgents.devicePool.setupError')
  } finally {
    setupWdaDeviceId.value = ''
  }
}

/** 向已安装 WDA 的目标设备下发纯启动命令，不复用可能触发 xcodebuild 的构建入口。 */
const startDeviceWda = async (device) => {
  startWdaDeviceId.value = device.deviceId
  try {
    const created = await http.post('/automation/device-agents/commands', {
      agentId: devicePoolAgentId.value,
      targetDeviceId: device.deviceId,
      commandType: 'START_WDA'
    })
    const deadline = Date.now() + START_WDA_POLL_TIMEOUT_MS
    while (Date.now() < deadline && devicePoolDialogVisible.value) {
      await sleep(START_WDA_POLL_INTERVAL_MS)
      if (!pageAlive) return
      const response = await http.get(`/automation/device-agents/commands/${created.data.id}`)
      const command = response.data
      if (command.status === 'COMPLETED') {
        ElMessage.success(t('deviceAgents.devicePool.startCompleted', {
          device: devicePoolLabel(device)
        }))
        await loadDevicePool()
        return
      }
      if (['FAILED', 'EXPIRED', 'CANCELLED'].includes(command.status)) {
        ElMessage.error(t('deviceAgents.devicePool.startFailed', {
          reason: command.resultSummary || command.errorCode || command.status
        }))
        return
      }
    }
    if (devicePoolDialogVisible.value) ElMessage.warning(t('deviceAgents.devicePool.startTimeout'))
  } catch (error) {
    showHttpError(error, 'deviceAgents.devicePool.startError')
  } finally {
    startWdaDeviceId.value = ''
  }
}

// WDA 主机级配置对话框
const wdaConfigDialogVisible = ref(false)
const wdaConfigLoading = ref(false)
const wdaConfigForm = reactive({
  agentId: '',
  xcodeOrgId: '',
  xcodeSigningId: '',
  allowProvisioningDeviceRegistration: false,
  updatedWdaBundleId: '',
  appiumServerUrl: 'http://localhost:4723',
  baseWdaLocalPort: 8100,
  // PUT 是整体替换：必须带上已保存的启动模式和 WDA 地址，
  // 否则每次保存签名都会把 URL/PREINSTALLED 模式静默改回 XCODEBUILD
  launchMode: 'XCODEBUILD',
  wdaUrl: ''
})

// 重置 WDA 配置表单，避免上一个 Agent 的取值残留到当前对话框
const resetWdaConfigForm = () => {
  wdaConfigForm.xcodeOrgId = ''
  wdaConfigForm.xcodeSigningId = ''
  wdaConfigForm.allowProvisioningDeviceRegistration = false
  wdaConfigForm.updatedWdaBundleId = ''
  wdaConfigForm.appiumServerUrl = 'http://localhost:4723'
  wdaConfigForm.baseWdaLocalPort = 8100
  wdaConfigForm.launchMode = 'XCODEBUILD'
  wdaConfigForm.wdaUrl = ''
  signingCandidates.value = []
  selectedSigningCandidate.value = null
}

// 一键检测签名身份：下发 DETECT_SIGNING 命令并轮询 Agent 上报的候选结果
const signingDetectLoading = ref(false)
const signingCandidates = ref([])
const selectedSigningCandidate = ref(null)
// Agent 命令通道为秒级轮询，2 秒查一次结果；60 秒未响应视为 Agent 离线
const DETECT_POLL_INTERVAL_MS = 2000
const DETECT_POLL_TIMEOUT_MS = 60000

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

// 当前配置对话框内的 Agent 是否在线，离线时检测按钮不下发命令
const wdaConfigAgentOnline = ref(false)
// 保留整条 Agent 记录：离线提示要展示真实原因，只有布尔在线标记不够
const wdaConfigAgent = ref(null)

// 下发探测命令并轮询结果；命令成功返回结果摘要，失败或超时提示后返回 null
const dispatchDetectCommand = async (commandType, failedKey, timeoutKey) => {
  // Agent 离线时立即失败：命令会一直 PENDING，等满 60 秒超时对用户毫无意义
  if (!wdaConfigAgentOnline.value) {
    ElMessage.warning(t('deviceAgents.detectAgentOffline'))
    return null
  }
  const created = await http.post('/automation/device-agents/commands', {
    agentId: wdaConfigForm.agentId,
    commandType
  })
  const commandId = created.data.id
  const deadline = Date.now() + DETECT_POLL_TIMEOUT_MS
  while (Date.now() < deadline) {
    await sleep(DETECT_POLL_INTERVAL_MS)
    // 对话框被关闭或页面已卸载时终止轮询，避免后台继续请求
    if (!pageAlive || !wdaConfigDialogVisible.value) return null
    const response = await http.get(`/automation/device-agents/commands/${commandId}`)
    const command = response.data
    if (command.status === 'COMPLETED') return command.resultSummary
    if (command.status === 'FAILED' || command.status === 'EXPIRED' || command.status === 'CANCELLED') {
      // 旧版 Agent 不认识新命令类型：新版回稳定标记，旧版回中文文案，两者都提示重装
      if (/UNSUPPORTED_COMMAND|未知命令类型/.test(command.resultSummary || '')) {
        ElMessage.error(t('deviceAgents.detectAgentOutdated'))
        return null
      }
      ElMessage.error(t(failedKey,
        { reason: command.resultSummary || command.errorCode || command.status }))
      return null
    }
  }
  ElMessage.warning(t(timeoutKey))
  return null
}

// 候选下拉展示：证书通用名 + 团队 ID + 到期日
const candidateLabel = (candidate) => {
  const expiry = candidate.expiresAt ? `, ${candidate.expiresAt}` : ''
  return `${candidate.label} (${candidate.teamId}${expiry})`
}

// 把选中的候选写入表单签名字段，用户确认后经现有保存链路下发
const applySigningCandidate = (index) => {
  const candidate = signingCandidates.value[index]
  if (!candidate) return
  wdaConfigForm.xcodeOrgId = candidate.teamId
  wdaConfigForm.xcodeSigningId = candidate.signingIdentity
}

const detectSigningIdentity = async () => {
  signingDetectLoading.value = true
  signingCandidates.value = []
  selectedSigningCandidate.value = null
  try {
    const resultSummary = await dispatchDetectCommand(
      'DETECT_SIGNING', 'deviceAgents.detectSigningFailed', 'deviceAgents.detectSigningTimeout')
    if (resultSummary !== null) await handleDetectResult(resultSummary)
  } catch (error) {
    showHttpError(error, 'deviceAgents.detectSigningError')
  } finally {
    signingDetectLoading.value = false
  }
}

// 解析候选身份：唯一候选自动回填并保存，多候选交给下拉选择，空候选给出引导提示
const handleDetectResult = async (resultSummary) => {
  let identities
  try {
    identities = JSON.parse(resultSummary || '{}').identities || []
  } catch (_error) {
    ElMessage.error(t('deviceAgents.detectSigningFailed', { reason: resultSummary || '' }))
    return
  }
  if (identities.length === 0) {
    ElMessage.warning(t('deviceAgents.detectSigningEmpty'))
    return
  }
  signingCandidates.value = identities
  if (identities.length === 1) {
    selectedSigningCandidate.value = 0
    applySigningCandidate(0)
    await saveWdaConfig()
    return
  }
  ElMessage.info(t('deviceAgents.detectSigningSelect'))
}

// 功能配置对话框
const featuresDialogVisible = ref(false)
const featuresForm = reactive({
  agentId: '',
  diagnostics: false,
  appiumWda: false,
  autostart: false
})

// 固定档位同时驱动选择器和规范表，页面展示值必须与 Agent 内置频次保持一致。
const OPERATION_SPEED_RULES = [
  { value: 'SLOW', actionSeconds: 2, actionRate: 30, swipeSeconds: 3, swipeRate: 20, typingRate: 60, wirelessInterval: 15, usbInterval: 3.75, pageAttempts: 8 },
  { value: 'STANDARD', actionSeconds: 1, actionRate: 60, swipeSeconds: 2, swipeRate: 30, typingRate: 120, wirelessInterval: 10, usbInterval: 2.5, pageAttempts: 12 },
  { value: 'FAST', actionSeconds: 0.5, actionRate: 120, swipeSeconds: 1, swipeRate: 60, typingRate: 240, wirelessInterval: 5, usbInterval: 1.25, pageAttempts: 24 }
]
const operationSpeedDialogVisible = ref(false)
const operationSpeedLoading = ref(false)
const operationSpeedSaving = ref(false)
const operationSpeedForm = reactive({ agentId: '', operationSpeed: 'STANDARD' })

// Agent 健康上报间隔为 60 秒，超过 3 倍间隔未上报即视为离线
const ONLINE_THRESHOLD_MS = 180000

/** 判断 Agent 是否在线：只有最近一次上报足够新才算在线。 */
const isAgentOnline = (agent) => {
  if (!agent || !agent.lastOnlineAt) return false
  const lastOnline = new Date(agent.lastOnlineAt).getTime()
  return Number.isFinite(lastOnline) && Date.now() - lastOnline < ONLINE_THRESHOLD_MS
}

// 后端 DeviceAgentAuthenticationFilter 的稳定原因码到文案键的映射，未知取值原样展示
const AUTH_FAILURE_TEXT_KEYS = {
  UNKNOWN_AGENT: 'unknownAgent',
  MISSING_AGENT_ID: 'missingAgentId',
  SECRET_UNREADABLE: 'secretUnreadable',
  EXPIRED_TIMESTAMP: 'expiredTimestamp',
  BAD_CONTENT_HASH: 'badSignature',
  BAD_SIGNATURE: 'badSignature',
  REPLAYED_NONCE: 'replayedNonce',
  BODY_TOO_LARGE: 'bodyTooLarge',
  AUTH_UNAVAILABLE: 'authUnavailable'
}

/** 把认证失败原因码翻译为可读文案；后端新增原因码时降级为原样展示而非留空。 */
const authFailureText = (reason) => {
  const key = AUTH_FAILURE_TEXT_KEYS[reason]
  return key ? t(`deviceAgents.authFailure.${key}`) : (reason || t('deviceAgents.authFailure.unknown'))
}

/** 判断 Agent 是否处于「进程在跑但被后端拒绝」的状态。
 *
 * 判据是最近一次认证失败晚于最近一次成功上报：只要 Agent 还在发请求，
 * 认证失败时间就会持续刷新，而 lastOnlineAt 停在被拒绝之前。
 */
const isAuthRejected = (agent) => {
  if (!agent || !agent.lastAuthFailureAt) return false
  if (!agent.lastOnlineAt) return true
  return new Date(agent.lastAuthFailureAt).getTime() > new Date(agent.lastOnlineAt).getTime()
}

/** 离线原因：区分「凭据被拒绝」「从未上线」「心跳超时」三种，指向完全不同的处置。 */
const offlineReason = (agent) => {
  if (!agent) return ''
  if (isAuthRejected(agent)) {
    return t('deviceAgents.offlineReason.authRejected', {
      reason: authFailureText(agent.lastAuthFailureReason),
      time: formatTime(agent.lastAuthFailureAt)
    })
  }
  if (!agent.lastOnlineAt) return t('deviceAgents.offlineReason.neverOnline')
  return t('deviceAgents.offlineReason.heartbeatTimeout', { time: formatTime(agent.lastOnlineAt) })
}

/** 离线时的下一步动作建议，与原因一一对应。 */
const offlineAdvice = (agent) => {
  if (!agent) return ''
  if (isAuthRejected(agent)) return t('deviceAgents.offlineReason.authRejectedAdvice')
  if (!agent.lastOnlineAt) return t('deviceAgents.offlineReason.neverOnlineAdvice')
  return t('deviceAgents.offlineReason.heartbeatTimeoutAdvice')
}

// 配置降级错误码到展示样式的映射：配置被拒是真错误，
// 用着缓存或只剩环境变量属于可运行的降级，但都意味着页面显示的配置未必生效
const CONFIG_STATUS_TYPES = {
  WDA_CONFIG_REJECTED: 'danger',
  WDA_CONFIG_NOT_DELIVERED: 'danger',
  WDA_CONFIG_FROM_CACHE: 'warning',
}

/** 配置状态标签：只识别已知的配置类错误码，其余错误码不占用这一列。 */
const configStatusLabel = (agent) => {
  const code = agent?.lastErrorCode
  if (!code) return t('deviceAgents.configStatus.delivered')
  return CONFIG_STATUS_TYPES[code]
    ? t(`deviceAgents.configStatus.${code}`)
    : t('deviceAgents.configStatus.otherError')
}

/** 配置状态标签配色，未知错误码按需注意处理而不是直接判红。 */
const configStatusType = (agent) => CONFIG_STATUS_TYPES[agent?.lastErrorCode] || 'warning'

/** 配置状态详情，说明该状态下页面配置与 Agent 实际取值的关系。 */
const configStatusDetail = (agent) => {
  const code = agent?.lastErrorCode
  if (!code) return ''
  return CONFIG_STATUS_TYPES[code]
    ? t(`deviceAgents.configStatus.${code}Detail`)
    : t('deviceAgents.configStatus.otherErrorDetail', { code })
}

/** 下发 UPDATE_CONFIG 让 Agent 重新从后台拉取配置并刷新配置状态。 */
const handleResendConfig = async (row) => {
  resendingAgentId.value = row.agentId
  try {
    await http.post('/automation/device-agents/commands', {
      agentId: row.agentId,
      commandType: 'UPDATE_CONFIG',
      commandParams: {}
    })
    // Agent 要先领到命令才会重新拉取，状态由随后的心跳刷新，这里不即时断言成功
    ElMessage.success(t('deviceAgents.configStatus.resendDispatched'))
  } catch (error) {
    showHttpError(error)
  } finally {
    resendingAgentId.value = ''
  }
}

/** 复制本机排查命令：Agent 认证失败时控制台看不到本机报错，只能让用户在 Mac 上执行。 */
const copyDiagnoseCommand = () => {
  navigator.clipboard.writeText(buildDiagnoseCommand())
  ElMessage.success(t('common.copied'))
}

// 加载 Agent 列表
// 分页与搜索事件会直接把数字、字符串传进来，只有显式传 { silent: true } 的调用才跳过加载态，
// 否则等待重启的后台轮询每 10 秒就会闪一次表格遮罩
const loadAgents = async (options) => {
  const silent = Boolean(options && options.silent === true)
  if (!silent) loading.value = true
  try {
    const params = {
      page: pagination.page,
      size: pagination.size
    }
    if (searchQuery.value) params.search = searchQuery.value
    if (statusFilter.value) params.status = statusFilter.value

    const response = await http.get('/automation/device-agents', { params })
    agents.value = response.data.items
    pagination.total = response.data.total
  } catch (error) {
    showHttpError(error, 'deviceAgents.loadError')
  } finally {
    if (!silent) loading.value = false
  }
}

// 设备名称是唯一允许创建后维护的设备元数据，Agent ID 保持不变
const deviceNameDialogVisible = ref(false)
const deviceNameSaving = ref(false)
const deviceNameForm = reactive({ agentId: '', deviceName: '' })

/** 打开名称编辑框并带入唯一Agent ID和名称快照。 */
const handleEditDeviceName = (agent) => {
  deviceNameForm.agentId = agent.agentId
  deviceNameForm.deviceName = agent.deviceName || ''
  deviceNameDialogVisible.value = true
}

/** 保存设备名称；空白表示清空，Agent ID 只从路径传递且不可修改。 */
const saveDeviceName = async () => {
  deviceNameSaving.value = true
  try {
    await http.put(`/automation/device-agents/${deviceNameForm.agentId}/device-name`, {
      deviceName: deviceNameForm.deviceName.trim()
    })
    deviceNameDialogVisible.value = false
    ElMessage.success(t('deviceAgents.deviceNameSaved'))
    loadAgents()
  } catch (error) {
    showHttpError(error, 'deviceAgents.deviceNameSaveError')
  } finally {
    deviceNameSaving.value = false
  }
}

// Remote XPC Registry 配置和实际状态由独立接口按 Agent 查询，不扩大列表响应。
const registryDialogVisible = ref(false)
const registryLoading = ref(false)
const registrySaving = ref(false)
const registryActionLoading = ref('')
let registryLoadPromise = null
const registryForm = reactive({
  agentId: '',
  defaultPort: 42314,
  useGlobalPort: true,
  portOverride: 42314,
  effectivePort: 42314,
  desiredState: 'OFFLINE',
  observedState: 'NOT_INSTALLED',
  tunnelCount: 0,
  helperVersion: '',
  lastErrorCode: '',
  lastReportedAt: null
})

/** 把后端 Registry 视图完整写入表单，null 覆盖值显示全局默认端口。 */
const applyRegistryView = (view) => {
  registryForm.defaultPort = Number(view?.defaultPort || 42314)
  registryForm.useGlobalPort = view?.portOverride == null
  registryForm.portOverride = Number(view?.portOverride ?? registryForm.defaultPort)
  registryForm.effectivePort = Number(view?.effectivePort || registryForm.defaultPort)
  registryForm.desiredState = view?.desiredState || 'OFFLINE'
  registryForm.observedState = view?.observedState || 'NOT_INSTALLED'
  registryForm.tunnelCount = Number(view?.tunnelCount || 0)
  registryForm.helperVersion = view?.helperVersion || ''
  registryForm.lastErrorCode = view?.lastErrorCode || ''
  registryForm.lastReportedAt = view?.lastReportedAt || null
}

/** 查询当前对话框 Agent 的 Registry 配置与最近状态。 */
const loadRegistry = async ({ silent = false } = {}) => {
  if (!registryForm.agentId) return
  if (registryLoadPromise) return registryLoadPromise
  if (!silent) registryLoading.value = true
  registryLoadPromise = (async () => {
    try {
      const response = await http.get(`/automation/device-agents/${registryForm.agentId}/registry`)
      applyRegistryView(response.data)
    } catch (error) {
      if (!silent) showHttpError(error, 'deviceAgents.registry.loadError')
    } finally {
      if (!silent) registryLoading.value = false
      registryLoadPromise = null
    }
  })()
  return registryLoadPromise
}

const registryStatusPoller = createRegistryStatusPoller({
  refresh: () => loadRegistry({ silent: true })
})

/** 停止 Registry 弹窗的后台状态轮询。 */
const stopRegistryPolling = () => registryStatusPoller.stop()

/** 打开 Registry 管理对话框并读取服务端状态。 */
const handleRegistry = async (agent) => {
  registryForm.agentId = agent.agentId
  registryDialogVisible.value = true
  await loadRegistry()
  if (registryDialogVisible.value) registryStatusPoller.start()
}

/** 保存单机端口覆盖；选择全局端口时显式提交 null。 */
const saveRegistryPort = async () => {
  registrySaving.value = true
  try {
    const response = await http.put(`/automation/device-agents/${registryForm.agentId}/registry`, {
      portOverride: registryForm.useGlobalPort ? null : Number(registryForm.portOverride)
    })
    applyRegistryView(response.data)
    ElMessage.success(t('deviceAgents.registry.portSaved'))
  } catch (error) {
    showHttpError(error, 'deviceAgents.registry.saveError')
  } finally {
    registrySaving.value = false
  }
}

/** 下发固定生命周期动作；下线和重建先确认，防止中断正在使用的隧道。 */
const dispatchRegistryAction = async (action) => {
  if (action === 'OFFLINE' || action === 'RECREATE') {
    try {
      await ElMessageBox.confirm(
        t(`deviceAgents.registry.${action.toLowerCase()}Confirm`, { agentId: registryForm.agentId }),
        t('deviceAgents.registry.dialogTitle'),
        { type: action === 'OFFLINE' ? 'warning' : 'info' }
      )
    } catch {
      return
    }
  }
  registryActionLoading.value = action
  try {
    await http.post(`/automation/device-agents/${registryForm.agentId}/registry/actions`, { action })
    await loadRegistry()
    ElMessage.success(t('deviceAgents.registry.actionDispatched', {
      action: t(`deviceAgents.registry.${action.toLowerCase()}`)
    }))
  } catch (error) {
    showHttpError(error, 'deviceAgents.registry.actionError')
  } finally {
    registryActionLoading.value = ''
  }
}

/** Registry 状态标签使用稳定枚举；新增未知状态时降级原样显示。 */
const registryStateLabel = (state) => {
  const normalized = String(state || 'NOT_INSTALLED').toLowerCase()
  const known = ['not_installed', 'offline', 'starting', 'online', 'restarting', 'error', 'stale']
  return known.includes(normalized) ? t(`deviceAgents.registry.states.${normalized}`) : String(state || '-')
}

/** Registry 状态到 Element Plus 标签色的映射。 */
const registryStateType = (state) => ({
  ONLINE: 'success', STARTING: 'warning', RESTARTING: 'warning', ERROR: 'danger',
  STALE: 'danger', NOT_INSTALLED: 'info', OFFLINE: 'info'
}[state] || 'info')

// 回连地址编辑对话框
const backendUrlDialogVisible = ref(false)
const backendUrlSaving = ref(false)
const backendUrlForm = reactive({ agentId: '', backendUrl: '', selfSigned: false })

// 本机改址命令：地址已失效时后端下发不到，用户必须能在这里拿到可粘贴的命令
const setServerCommand = computed(() => buildSetServerCommand({
  backendUrl: backendUrlForm.backendUrl,
  selfSigned: backendUrlForm.selfSigned
}))

const copySetServerCommand = () => {
  navigator.clipboard.writeText(setServerCommand.value)
  ElMessage.success(t('common.copied'))
}

/** 打开回连地址编辑：直接用列表行上的取值，无需再查一次详情。 */
const handleEditBackendUrl = (agent) => {
  backendUrlForm.agentId = agent.agentId
  backendUrlForm.backendUrl = agent.backendUrl || ''
  // 自签名结论只对控制台 origin 成立；改址目标是另一个地址时不代拨，由管理员判断
  backendUrlForm.selfSigned = selfSignedDetected.value && !usesCustomServer(agent.backendUrl)
  backendUrlDialogVisible.value = true
  ensureSelfSignedDetection()
}

/** 保存回连地址。留空即解除绑定，该 Agent 回退到平台全局 base-url。
 *
 * 已配对 Agent 会同步收到改址命令；后端返回 dispatched 为假时必须提示改用本机命令，
 * 否则用户会以为保存即生效，而那台 Agent 其实永远不会改道。
 */
const saveBackendUrl = async () => {
  backendUrlSaving.value = true
  try {
    const response = await http.put(`/automation/device-agents/${backendUrlForm.agentId}/backend-url`, {
      backendUrl: backendUrlForm.backendUrl.trim()
    })
    if (response.data?.dispatched) {
      ElMessage.success(t('deviceAgents.backendUrlDispatched'))
    } else {
      // 未下发不是失败，但用户必须知道还差一步，因此用告警而非成功提示
      ElMessage.warning(t('deviceAgents.backendUrlNotDispatched'))
    }
    backendUrlDialogVisible.value = false
    loadAgents()
  } catch (error) {
    showHttpError(error, 'deviceAgents.backendUrlSaveError')
  } finally {
    backendUrlSaving.value = false
  }
}

/** 读取指定 Agent 已绑定的回连地址；查不到一律按未绑定处理，命令回退控制台 origin。 */
const fetchBoundBackendUrl = async (agentId) => {
  if (!agentId) return ''
  try {
    const response = await http.get(`/automation/device-agents/${agentId}`, { silentError: true })
    return response.data?.backendUrl || ''
  } catch {
    return ''
  }
}

// 创建配对码
const handleCreatePairing = () => {
  pairingMode.value = 'create'
  pairingCode.value = null
  pairingExpiresIn.value = 600
  pairingForm.agentId = ''
  pairingForm.deviceName = ''
  pairingForm.features = [...DEFAULT_PAIRING_FEATURES]
  pairingForm.backendUrl = ''
  pairingFormOriginalBackendUrl.value = ''
  // 重置回探测结论而非固定 false，自签名部署下开关保持默认开启
  pairingForm.insecure = selfSignedDetected.value
  insecureTouched.value = false
  pairingForm.npmRegistry = ''
  pairingDialogVisible.value = true
  ensureSelfSignedDetection()
}

// 为已注册 Agent 重新颁发配对码：配对码一旦被领取、作废或过期，明文即被清除，只能重发
const handleReissuePairing = async (agent) => {
  handleCreatePairing()
  pairingMode.value = 'reissue'
  pairingForm.agentId = agent.agentId
  // 重新颁发沿用该 Agent 已绑定的地址：命令里的地址必须和注册记录一致，
  // 否则装出来的 Agent 连的入口与后端下发的回连地址对不上
  const bound = agent.backendUrl !== undefined
    ? (agent.backendUrl || '')
    : await fetchBoundBackendUrl(agent.agentId)
  pairingForm.backendUrl = bound
  pairingFormOriginalBackendUrl.value = bound
  if (usesCustomServer(bound) && !insecureTouched.value) pairingForm.insecure = false
}

// 重新颁发选择器：配对码 Tab 不持有 Agent 列表，打开时按需拉一页候选
const REISSUE_CANDIDATE_SIZE = 100
const reissueDialogVisible = ref(false)
const reissueLoading = ref(false)
const reissueCandidates = ref([])
const reissueAgentId = ref('')

/** 打开重新颁发选择器：已撤销的 Agent 不能再配对，必须从候选中排除。 */
const openReissueDialog = async () => {
  reissueAgentId.value = ''
  reissueCandidates.value = []
  reissueDialogVisible.value = true
  reissueLoading.value = true
  try {
    const response = await http.get('/automation/device-agents', {
      params: { page: 1, size: REISSUE_CANDIDATE_SIZE }
    })
    reissueCandidates.value = (response.data.items || []).filter((item) => !item.revokedAt)
  } catch (error) {
    showHttpError(error, 'deviceAgents.loadError')
  } finally {
    reissueLoading.value = false
  }
}

/** 选定 Agent 后走既有重新颁发流程，复用同一个配对码对话框。 */
const confirmReissue = () => {
  if (!reissueAgentId.value) {
    ElMessage.warning(t('deviceAgents.reissueSelectAgent'))
    return
  }
  reissueDialogVisible.value = false
  // 候选里已带回连地址，直接复用，省掉一次详情请求
  const candidate = reissueCandidates.value.find((item) => item.agentId === reissueAgentId.value)
  handleReissuePairing(candidate || { agentId: reissueAgentId.value })
}

/** WDA 配置对话框里的直达入口：离线且凭据被拒绝时，重新颁发是唯一的修复动作。 */
const handleReissueFromWdaDialog = () => {
  const agentId = wdaConfigForm.agentId
  wdaConfigDialogVisible.value = false
  handleReissuePairing({ agentId })
}

/** 请求服务端生成同源的 Agent ID 与短码名称，并允许管理员继续编辑。 */
const generatePairingIdentity = async () => {
  identitySuggestionLoading.value = true
  try {
    const response = await http.get('/automation/device-agents/pairing/identity-suggestion')
    pairingForm.agentId = response.data.agentId
    pairingForm.deviceName = response.data.deviceName
    ElMessage.success(t('deviceAgents.agentIdentityGenerated'))
  } catch (error) {
    showHttpError(error, 'deviceAgents.agentIdentityGenerateError')
  } finally {
    identitySuggestionLoading.value = false
  }
}

/** 创建前在页面侧拦截空身份；后端仍执行同一校验，防止绕过页面直接调用。 */
const createPairingCode = async () => {
  if (!pairingForm.agentId.trim()) {
    ElMessage.warning(t('deviceAgents.agentIdRequired'))
    return
  }
  try {
    const backendUrl = pairingForm.backendUrl.trim()
    // 重新颁发沿用原 Agent ID 与其上一次申请的功能，不新建注册记录；
    // 此时回连地址已绑在注册记录上，改动必须单独提交，颁发接口不接受该字段
    if (pairingMode.value === 'reissue' && backendUrl !== pairingFormOriginalBackendUrl.value.trim()) {
      await http.put(`/automation/device-agents/${pairingForm.agentId}/backend-url`, { backendUrl })
      pairingFormOriginalBackendUrl.value = backendUrl
    }
    const path = pairingMode.value === 'reissue'
      ? `/automation/device-agents/${pairingForm.agentId}/pairing`
      : '/automation/device-agents/pairing'
    const response = await http.post(path, {
      agentId: pairingForm.agentId.trim(),
      requestedFeatures: pairingForm.features,
      backendUrl,
      deviceName: pairingForm.deviceName.trim()
    })
    pairingCode.value = response.data.pairingCode
    // 后端 CreatePairingResponse 的 TTL 字段名为 ttlSeconds
    pairingExpiresIn.value = response.data.ttlSeconds

    // 启动倒计时
    if (pairingTimer) clearInterval(pairingTimer)
    pairingTimer = setInterval(() => {
      pairingExpiresIn.value--
      if (pairingExpiresIn.value <= 0) {
        clearInterval(pairingTimer)
        pairingCode.value = null
      }
    }, 1000)

    ElMessage.success(t('deviceAgents.pairingCodeCreated'))
    // 新配对码立即出现在管理列表里，便于确认有效期与随后作废
    loadPairingCodes()
  } catch (error) {
    // 统一分类器优先展示后端返回的失败原因（功能名非法、频率限制等），无后端消息时回退页面兜底文案
    showHttpError(error, 'deviceAgents.pairingCodeError')
  }
}

const copyPairingCode = () => {
  navigator.clipboard.writeText(pairingCode.value)
  ElMessage.success(t('common.copied'))
}

// 配置 WDA
const handleConfigWda = async (agent) => {
  wdaConfigForm.agentId = agent.agentId
  // 清除上一个 Agent 的签名探测候选，避免误选到其他机器的身份。
  signingCandidates.value = []
  selectedSigningCandidate.value = null
  wdaConfigAgent.value = agent
  wdaConfigAgentOnline.value = isAgentOnline(agent)
  wdaConfigLoading.value = true
  wdaConfigDialogVisible.value = true

  try {
    // 404 表示尚未配置属于正常情况，标记 silentError 避免全局拦截器先弹错误提示
    const response = await http.get(`/automation/device-agents/${agent.agentId}/wda-config`, { silentError: true })
    const config = response.data

    if (config.signingConfig) {
      wdaConfigForm.xcodeOrgId = config.signingConfig.xcodeOrgId || ''
      wdaConfigForm.xcodeSigningId = config.signingConfig.xcodeSigningId || ''
      wdaConfigForm.allowProvisioningDeviceRegistration =
        config.signingConfig.allowProvisioningDeviceRegistration === true
      wdaConfigForm.updatedWdaBundleId = config.signingConfig.updatedWdaBundleId || ''
    } else {
      // 已有设备配置但未配置签名参数时，签名字段必须回到默认值
      wdaConfigForm.xcodeOrgId = ''
      wdaConfigForm.xcodeSigningId = ''
      wdaConfigForm.allowProvisioningDeviceRegistration = false
      wdaConfigForm.updatedWdaBundleId = ''
    }
    wdaConfigForm.appiumServerUrl = config.appiumServerUrl || 'http://localhost:4723'
    wdaConfigForm.baseWdaLocalPort = config.baseWdaLocalPort || 8100
    wdaConfigForm.launchMode = config.launchMode || 'XCODEBUILD'
    wdaConfigForm.wdaUrl = config.wdaUrl || ''
  } catch (error) {
    if (error.response && error.response.status === 404) {
      // 配置不存在，使用默认值
      resetWdaConfigForm()
    } else {
      showHttpError(error, 'deviceAgents.loadConfigError')
    }
  } finally {
    wdaConfigLoading.value = false
  }

}

const saveWdaConfig = async () => {
  try {
    const payload = {
      signingConfig: {
        xcodeOrgId: wdaConfigForm.xcodeOrgId || null,
        xcodeSigningId: wdaConfigForm.xcodeSigningId || null,
        allowProvisioningDeviceRegistration:
          wdaConfigForm.allowProvisioningDeviceRegistration,
        updatedWdaBundleId: wdaConfigForm.updatedWdaBundleId || null
      },
      launchMode: wdaConfigForm.launchMode || 'XCODEBUILD',
      wdaUrl: wdaConfigForm.wdaUrl || null,
      appiumServerUrl: wdaConfigForm.appiumServerUrl || null,
      baseWdaLocalPort: wdaConfigForm.baseWdaLocalPort || null
    }

    await http.put(`/automation/device-agents/${wdaConfigForm.agentId}/wda-config`, payload)
    ElMessage.success(t('deviceAgents.configSaved'))
    wdaConfigDialogVisible.value = false
  } catch (error) {
    showHttpError(error, 'deviceAgents.configSaveError')
  }
}

// 更新功能
// 字段名必须与后端 AgentRegistrationView 的 @JsonProperty 一致（无 Status 后缀）：
// 读错字段只会静默得到 undefined，四个开关恒为关闭，用户仅勾选想开的一项保存时，
// 其余三项会被一起写成 DISABLED，静默覆盖已生效的配置
const handleUpdateFeatures = (agent) => {
  featuresForm.agentId = agent.agentId
  featuresForm.diagnostics = agent.featureDiagnostics === 'ENABLED'
  featuresForm.appiumWda = agent.featureAutomation === 'ENABLED'
  featuresForm.autostart = agent.featureAutostart === 'ENABLED'
  featuresDialogVisible.value = true
}

/** 打开速度档位对话框并读取 Agent 配置；查询失败时关闭弹窗，避免误保存默认档。 */
const handleOperationSpeed = async (agent) => {
  operationSpeedForm.agentId = agent.agentId
  operationSpeedForm.operationSpeed = 'STANDARD'
  operationSpeedDialogVisible.value = true
  operationSpeedLoading.value = true
  try {
    const response = await http.get(
      `/automation/device-agents/${agent.agentId}/operation-speed`)
    operationSpeedForm.operationSpeed = response.data.operationSpeed || 'STANDARD'
  } catch (error) {
    operationSpeedDialogVisible.value = false
    showHttpError(error, 'deviceAgents.operationSpeed.loadError')
  } finally {
    operationSpeedLoading.value = false
  }
}

/** 保存固定速度档位；后端派生无线频次、持久化、审计并通知 Agent 热重载。 */
const saveOperationSpeed = async () => {
  operationSpeedSaving.value = true
  try {
    await http.put(
      `/automation/device-agents/${operationSpeedForm.agentId}/operation-speed`,
      { operationSpeed: operationSpeedForm.operationSpeed })
    ElMessage.success(t('deviceAgents.operationSpeed.saved'))
    operationSpeedDialogVisible.value = false
  } catch (error) {
    showHttpError(error, 'deviceAgents.operationSpeed.saveError')
  } finally {
    operationSpeedSaving.value = false
  }
}

// 后端功能状态使用 ENABLED / DISABLED 字符串，开关的布尔值必须转换后提交
const toFeatureStatus = (enabled) => (enabled ? 'ENABLED' : 'DISABLED')

const updateFeatures = async () => {
  try {
    await http.put(`/automation/device-agents/${featuresForm.agentId}/features`, {
      featureDiagnostics: toFeatureStatus(featuresForm.diagnostics),
      featureAutomation: toFeatureStatus(featuresForm.appiumWda),
      featureAutostart: toFeatureStatus(featuresForm.autostart)
    })
    ElMessage.success(t('deviceAgents.featuresUpdated'))
    featuresDialogVisible.value = false
    loadAgents()
  } catch (error) {
    // 用户主动取消确认时不提示失败
    if (error === 'cancel') return
    showHttpError(error, 'deviceAgents.featuresUpdateError')
  }
}

// 撤销 Agent
// 设为默认 Agent：控制台环境检测展示、诊断命令下发和任务执行阻断判断都会切到这台，
// 影响面超出单个 Agent，因此先确认再提交
const handleSetDefault = async (agent) => {
  try {
    await ElMessageBox.confirm(
      t('deviceAgents.setDefaultConfirm', { agentId: agent.agentId }),
      t('common.warning'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )

    await http.post(`/automation/device-agents/${agent.agentId}/default`)
    ElMessage.success(t('deviceAgents.setDefaultSuccess'))
    loadAgents()
  } catch (error) {
    if (error !== 'cancel') {
      showHttpError(error, 'deviceAgents.setDefaultError')
    }
  }
}

const handleRevoke = async (agent) => {
  try {
    await ElMessageBox.confirm(
      t('deviceAgents.revokeConfirm', { agentId: agent.agentId }),
      t('common.warning'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )

    await http.post(`/automation/device-agents/${agent.agentId}/revoke`, {
      reason: 'Revoked by admin'
    })
    ElMessage.success(t('deviceAgents.revoked'))
    loadAgents()
  } catch (error) {
    if (error !== 'cancel') {
      showHttpError(error, 'deviceAgents.revokeError')
    }
  }
}

// 删除 Agent 记录：物理删除不可恢复，未撤销的 Agent 还会当场失去平台访问权限，
// 因此两种情况使用不同的确认文案，后者额外提示在跑的 Agent 会持续认证失败
const handleDelete = async (agent) => {
  const confirmKey = agent.revokedAt ? 'deviceAgents.deleteConfirm' : 'deviceAgents.deleteActiveConfirm'
  try {
    await ElMessageBox.confirm(
      t(confirmKey, { agentId: agent.agentId }),
      t('common.warning'),
      {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
  } catch {
    return
  }
  try {
    await http.delete(`/automation/device-agents/${agent.agentId}`)
    ElMessage.success(t('deviceAgents.deleted'))
    loadAgents()
  } catch (error) {
    showHttpError(error, 'deviceAgents.deleteError')
  }
}

// 工具函数
const getStatusType = (status) => {
  if (status === 'PAIRED') return 'success'
  if (status === 'REVOKED') return 'danger'
  return 'warning'
}

const formatTime = (timestamp) => {
  return new Date(timestamp).toLocaleString()
}

// 配对码管理：列表只展示元数据，明文与哈希都不会离开服务端
const pairingCodes = ref([])
const pairingCodesLoading = ref(false)
const pairingIncludeInactive = ref(false)
const pairingPagination = reactive({ page: 1, size: 10, total: 0 })

const loadPairingCodes = async () => {
  pairingCodesLoading.value = true
  try {
    const response = await http.get('/automation/device-agents/pairing', {
      params: {
        includeInactive: pairingIncludeInactive.value,
        page: pairingPagination.page,
        size: pairingPagination.size
      }
    })
    pairingCodes.value = response.data.items
    pairingPagination.total = response.data.total
  } catch (error) {
    showHttpError(error, 'deviceAgents.pairingCodesLoadError')
  } finally {
    pairingCodesLoading.value = false
  }
}

/** 配对码状态到标签颜色的稳定映射。 */
const pairingStatusType = (status) => {
  if (status === 'ACTIVE') return 'success'
  if (status === 'USED') return 'info'
  return 'warning'
}

/** 展示配对码剩余有效期，已过期显示 0 秒而不是负数。 */
const formatRemaining = (expiresAt) => {
  const seconds = Math.max(0, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000))
  return t('deviceAgents.pairingRemainingValue', { seconds })
}

// 查看配对码：明文只在可领取期间可取回，安装命令所需参数未随配对码存储，需用户重填
const pairingViewDialogVisible = ref(false)
const pairingViewForm = reactive({
  agentId: '',
  pairingCode: '',
  // 该 Agent 的绑定回连地址，随弹窗按 agentId 拉取，只读展示并参与命令拼装
  backendUrl: '',
  insecure: false,
  npmRegistry: ''
})
const pairingViewExpiresIn = ref(0)
let pairingViewTimer = null

/** 停止查看弹窗倒计时，弹窗关闭后不得继续占用定时器。 */
const stopPairingViewCountdown = () => {
  if (pairingViewTimer) {
    clearInterval(pairingViewTimer)
    pairingViewTimer = null
  }
}

// 与创建对话框共用构建器，保证两处生成的安装命令完全一致
const pairingViewInstallCommand = computed(() => buildInstallCommand({
  origin: platformBaseUrl,
  backendUrl: pairingViewForm.backendUrl,
  pairingCode: pairingViewForm.pairingCode,
  selfSigned: pairingViewForm.insecure,
  npmRegistry: pairingViewForm.npmRegistry
}))

const viewPairingCode = async (row) => {
  try {
    const response = await http.get(`/automation/device-agents/pairing/${row.id}/code`)
    pairingViewForm.agentId = response.data.agentId
    pairingViewForm.pairingCode = response.data.pairingCode
    pairingViewForm.npmRegistry = ''
    // 配对码记录不含回连地址，按 agentId 取注册记录上的绑定值；取不到时留空回退控制台 origin
    pairingViewForm.backendUrl = await fetchBoundBackendUrl(response.data.agentId)
    pairingViewForm.insecure = selfSignedDetected.value && !usesCustomServer(pairingViewForm.backendUrl)
    insecureTouched.value = false
    pairingViewExpiresIn.value = response.data.expiresInSeconds
    pairingViewDialogVisible.value = true
    ensureSelfSignedDetection().then(() => {
      if (!insecureTouched.value && !usesCustomServer(pairingViewForm.backendUrl)) {
        pairingViewForm.insecure = selfSignedDetected.value
      }
    })

    // 倒计时归零即代表配对码失效，立即关闭弹窗并刷新列表状态
    stopPairingViewCountdown()
    pairingViewTimer = setInterval(() => {
      pairingViewExpiresIn.value--
      if (pairingViewExpiresIn.value <= 0) {
        stopPairingViewCountdown()
        pairingViewDialogVisible.value = false
        loadPairingCodes()
      }
    }, 1000)
  } catch (error) {
    showHttpError(error, 'deviceAgents.pairingViewError')
  }
}

const copyPairingViewCode = () => {
  navigator.clipboard.writeText(pairingViewForm.pairingCode)
  ElMessage.success(t('common.copied'))
}

const copyPairingViewInstallCommand = () => {
  navigator.clipboard.writeText(pairingViewInstallCommand.value)
  ElMessage.success(t('common.copied'))
}

const revokePairingCode = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('deviceAgents.pairingRevokeConfirm', { agentId: row.agentId }),
      t('deviceAgents.pairingRevoke'),
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await http.delete(`/automation/device-agents/pairing/${row.id}`)
    ElMessage.success(t('deviceAgents.pairingRevoked'))
    loadPairingCodes()
  } catch (error) {
    showHttpError(error, 'deviceAgents.pairingRevokeError')
  }
}

// 删除已失效的配对码记录，仅用于清理列表，不影响任何仍可领取的配对码
const deletePairingCodeRecord = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('deviceAgents.pairingDeleteConfirm', { agentId: row.agentId }),
      t('common.warning'),
      { type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await http.delete(`/automation/device-agents/pairing/${row.id}/record`)
    ElMessage.success(t('deviceAgents.pairingDeleted'))
    loadPairingCodes()
  } catch (error) {
    showHttpError(error, 'deviceAgents.pairingDeleteError')
  }
}

// 进入页面只加载默认 Tab 的 Agent 列表，配对码列表交给 handleTabChange 按需拉取
onMounted(() => {
  loadAgents()
  // 与列表并行拉取：清单来自静态分发，失败也只是让升级按钮置灰，不影响列表本身
  loadLatestVersion()
})

// 离开页面必须停掉所有计时器：等待重启的列表轮询和配对码倒计时
// 否则组件已销毁仍在后台反复请求或递减响应式状态
onUnmounted(() => {
  pageAlive = false
  stopRestartWatch()
  stopRegistryPolling()
  if (pairingTimer) clearInterval(pairingTimer)
})
</script>

<style scoped>
.device-agents-view {
  padding: 20px;
}

.version-text {
  margin-right: 6px;
}

.version-unknown {
  color: var(--el-text-color-secondary);
}

/* 回连地址可能很长，按内容断行而非撑破列宽 */
.backend-url-text {
  word-break: break-all;
}

/* 禁用态的 el-button 不派发鼠标事件，tooltip 需要外层元素承接 hover 才能显示置灰原因 */
.upgrade-button-wrap {
  display: inline-flex;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filters {
  display: flex;
  align-items: center;
}

/* 配对码 Tab 的工具条：原先挂在卡片头部，移入 Tab 后需要自己撑起间距 */
.pairing-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.pairing-code-display {
  text-align: center;
  padding: 20px;
  background-color: #f5f7fa;
  border-radius: 4px;
}

.code-label {
  font-size: 14px;
  color: #606266;
  margin-bottom: 10px;
}

.code-value {
  font-size: 24px;
  font-weight: bold;
  font-family: monospace;
  color: #409eff;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.code-hint {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}

.install-command {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  gap: 8px;
}

.install-command code {
  max-width: 520px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f4f6fa;
  color: #31558f;
  font-size: 12px;
  line-height: 1.6;
  text-align: left;
  word-break: break-all;
}

.form-hint {
  font-size: 12px;
  color: #909399;
  margin-top: 5px;
}

.form-hint.warning {
  color: #e6a23c;
}

.registry-inline-hint {
  margin: 0 0 0 10px;
}

/* 离线标签带原因浮层，用手型光标提示可交互 */
.offline-tag {
  cursor: help;
}

.offline-detail {
  font-size: 13px;
  line-height: 1.6;
}
</style>
