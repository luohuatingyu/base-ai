export const CUSTOM_PLUGIN_LICENSE = '__CUSTOM__'

export const PLUGIN_LICENSE_OPTIONS = Object.freeze([
  Object.freeze({ name: 'MIT', url: 'https://spdx.org/licenses/MIT.html' }),
  Object.freeze({ name: 'Apache-2.0', url: 'https://spdx.org/licenses/Apache-2.0.html' }),
  Object.freeze({ name: 'GPL-3.0', url: 'https://spdx.org/licenses/GPL-3.0-only.html' }),
  Object.freeze({ name: 'BSD-3-Clause', url: 'https://spdx.org/licenses/BSD-3-Clause.html' }),
  Object.freeze({ name: 'LGPL-3.0', url: 'https://spdx.org/licenses/LGPL-3.0-only.html' }),
  Object.freeze({ name: 'AGPL-3.0', url: 'https://spdx.org/licenses/AGPL-3.0-only.html' }),
  Object.freeze({ name: 'MPL-2.0', url: 'https://spdx.org/licenses/MPL-2.0.html' })
])

/** 根据表单中的许可证名称返回预置项、自定义项或未选择状态。 */
export function pluginLicenseSelection(form) {
  const name = String(form?.licenseName || '').trim()
  if (!name) return ''
  return PLUGIN_LICENSE_OPTIONS.some(option => option.name === name) ? name : CUSTOM_PLUGIN_LICENSE
}

/** 把许可证选择同步为后端现有接口需要的名称和固定地址。 */
export function applyPluginLicenseSelection(form, selection) {
  if (!form || typeof form !== 'object') return
  const option = PLUGIN_LICENSE_OPTIONS.find(item => item.name === selection)
  if (option) {
    form.licenseName = option.name
    form.licenseUrl = option.url
    return
  }
  form.licenseName = ''
  form.licenseUrl = ''
}

/** 判断准入表单是否包含可提交的许可证名称。 */
export function pluginAdmissionLicenseValid(form) {
  return Boolean(String(form?.licenseName || '').trim())
}
