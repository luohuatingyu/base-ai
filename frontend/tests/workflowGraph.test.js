import assert from 'node:assert/strict'
import test from 'node:test'
import { createWorkflowGraph, validateWorkflowGraph } from '../src/utils/workflowGraph.js'

test('工作流画布初始化唯一开始和结束节点', () => {
  const graph = createWorkflowGraph()
  assert.deepEqual(graph.nodes.map(node => node.type), ['START', 'END'])
  assert.equal(graph.edges.length, 1)
})

test('工作流画布拒绝悬空连线和普通循环', () => {
  const dangling = {
    nodes: [{ id: 'start', type: 'START' }, { id: 'end', type: 'END' }],
    edges: [{ id: 'edge', source: 'start', target: 'missing' }]
  }
  assert.match(validateWorkflowGraph(dangling), /target/)

  const cyclic = {
    nodes: [{ id: 'start', type: 'START' }, { id: 'llm', type: 'LLM' }, { id: 'end', type: 'END' }],
    edges: [
      { id: 'a', source: 'start', target: 'llm' },
      { id: 'b', source: 'llm', target: 'start' },
      { id: 'c', source: 'llm', target: 'end' }
    ]
  }
  assert.match(validateWorkflowGraph(cyclic), /cycle/)
})
