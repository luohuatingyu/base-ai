import assert from 'node:assert/strict'
import test from 'node:test'

import { canRemoveModelProvider, healthStatusClass } from '../src/utils/modelRouteHealth.js'

test('maps route health statuses to required colors', () => {
  assert.equal(healthStatusClass('HEALTHY'), 'is-healthy')
  assert.equal(healthStatusClass('WARNING'), 'is-warning')
  assert.equal(healthStatusClass('SLOW'), 'is-slow')
  assert.equal(healthStatusClass('FAILED'), 'is-failed')
})

test('only healthy results hide provider removal', () => {
  assert.equal(canRemoveModelProvider('HEALTHY'), false)
  assert.equal(canRemoveModelProvider('WARNING'), true)
  assert.equal(canRemoveModelProvider('SLOW'), true)
  assert.equal(canRemoveModelProvider('FAILED'), true)
})
