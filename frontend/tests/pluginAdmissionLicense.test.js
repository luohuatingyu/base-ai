import test from 'node:test'
import assert from 'node:assert/strict'

import {
  CUSTOM_PLUGIN_LICENSE,
  PLUGIN_LICENSE_OPTIONS,
  applyPluginLicenseSelection,
  pluginAdmissionLicenseValid,
  pluginLicenseSelection
} from '../src/utils/pluginAdmissionLicense.js'

const expectedLicenses = [
  ['MIT', 'https://spdx.org/licenses/MIT.html'],
  ['Apache-2.0', 'https://spdx.org/licenses/Apache-2.0.html'],
  ['GPL-3.0', 'https://spdx.org/licenses/GPL-3.0-only.html'],
  ['BSD-3-Clause', 'https://spdx.org/licenses/BSD-3-Clause.html'],
  ['LGPL-3.0', 'https://spdx.org/licenses/LGPL-3.0-only.html'],
  ['AGPL-3.0', 'https://spdx.org/licenses/AGPL-3.0-only.html'],
  ['MPL-2.0', 'https://spdx.org/licenses/MPL-2.0.html']
]

test('插件准入提供七个预置许可证及固定 SPDX 地址', () => {
  assert.deepEqual(PLUGIN_LICENSE_OPTIONS.map(({ name, url }) => [name, url]), expectedLicenses)
})

test('选择任一预置许可证时同步规范名称和固定地址', () => {
  for (const [name, url] of expectedLicenses) {
    const form = { licenseName: 'legacy', licenseUrl: 'https://example.com/license' }

    applyPluginLicenseSelection(form, name)

    assert.deepEqual(form, { licenseName: name, licenseUrl: url })
    assert.equal(pluginLicenseSelection(form), name)
  }
})

test('选择自定义许可证时清空预置值并开放自定义录入', () => {
  const form = { licenseName: 'MIT', licenseUrl: 'https://spdx.org/licenses/MIT.html' }

  applyPluginLicenseSelection(form, CUSTOM_PLUGIN_LICENSE)

  assert.deepEqual(form, { licenseName: '', licenseUrl: '' })
  form.licenseName = 'Company-Proprietary'
  form.licenseUrl = 'https://licenses.example.com/company'
  assert.equal(pluginLicenseSelection(form), CUSTOM_PLUGIN_LICENSE)
})

test('历史未知许可证自动进入自定义模式且原值保持不变', () => {
  const form = { licenseName: 'Legacy-License', licenseUrl: 'https://licenses.example.com/legacy' }

  assert.equal(pluginLicenseSelection(form), CUSTOM_PLUGIN_LICENSE)
  assert.deepEqual(form, { licenseName: 'Legacy-License', licenseUrl: 'https://licenses.example.com/legacy' })
})

test('空许可证保持未选择状态', () => {
  assert.equal(pluginLicenseSelection({ licenseName: '', licenseUrl: '' }), '')
})

test('未选择许可证或自定义名称为空时拒绝提交', () => {
  assert.equal(pluginAdmissionLicenseValid({ licenseName: '', licenseUrl: '' }), false)
  assert.equal(pluginAdmissionLicenseValid({ licenseName: '   ', licenseUrl: 'https://example.com' }), false)
  assert.equal(pluginAdmissionLicenseValid({ licenseName: 'MIT', licenseUrl: 'https://spdx.org/licenses/MIT.html' }), true)
  assert.equal(pluginAdmissionLicenseValid({ licenseName: 'Company-Proprietary', licenseUrl: '' }), true)
})
