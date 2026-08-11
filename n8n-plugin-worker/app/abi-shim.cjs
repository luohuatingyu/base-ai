'use strict'

/** Base AI 自研 n8n 插件 ABI；不引用 n8n 引擎或 SDK。 */

class ApplicationError extends Error {
  /** 保存兼容错误选项。 */
  constructor(message, options = {}) { super(String(message || 'Plugin error')); this.options = options }
}

class NodeOperationError extends ApplicationError {
  /** 接受插件常用的节点与错误参数。 */
  constructor(node, error, options = {}) { super(error?.message || error, options); this.node = node }
}

class NodeApiError extends NodeOperationError {
  /** 规范化远端接口错误。 */
  constructor(node, error, options = {}) { super(node, error, options); this.httpCode = error?.statusCode || error?.response?.status }
}

/** 深复制可序列化插件数据。 */
function deepCopy(value) { return value === undefined ? undefined : structuredClone(value) }

/** 解析 JSON 并按插件选项决定是否回退。 */
function jsonParse(value, options = {}) {
  try { return JSON.parse(value) } catch (error) {
    if (Object.prototype.hasOwnProperty.call(options, 'fallbackValue')) return options.fallbackValue
    throw error
  }
}

/** 等待有限毫秒。 */
function sleep(milliseconds) { return new Promise(resolve => setTimeout(resolve, Math.max(0, Number(milliseconds) || 0))) }

/** 合并节点展示条件而不修改原对象。 */
function updateDisplayOptions(displayOptions, properties) {
  return properties.map(property => ({ ...property, displayOptions: { ...(property.displayOptions || {}), ...displayOptions } }))
}

/** 创建可由插件显式完成的 Promise。 */
function createDeferredPromise() {
  let resolve; let reject
  const promise = new Promise((success, failure) => { resolve = success; reject = failure })
  return { promise, resolve, reject }
}

const NodeConnectionType = Object.freeze({
  Main: 'main', AiTool: 'ai_tool', AiAgent: 'ai_agent', AiLanguageModel: 'ai_languageModel',
  AiMemory: 'ai_memory', AiOutputParser: 'ai_outputParser', AiRetriever: 'ai_retriever',
  AiTextSplitter: 'ai_textSplitter', AiEmbedding: 'ai_embedding', AiVectorStore: 'ai_vectorStore'
})

const NodeHelpers = new Proxy({ updateDisplayOptions }, {
  /** 为纯元数据帮助方法提供无副作用回退。 */
  get(target, property) { return target[property] || ((value) => value) }
})

/** 构造只保存参数的未知 ABI 占位类型。 */
function placeholder(name) {
  return class {
    /** 保存未知类型的构造参数。 */
    constructor(...args) { this.name = name; this.args = args }
  }
}

const known = {
  ApplicationError, NodeOperationError, NodeApiError, NodeConnectionType, NodeHelpers,
  BINARY_ENCODING: 'base64', deepCopy, jsonParse, sleep, updateDisplayOptions, createDeferredPromise,
}

module.exports = new Proxy(known, {
  /** 对只参与加载的未知符号返回稳定占位类型。 */
  get(target, property) {
    if (property in target) return target[property]
    const value = placeholder(String(property))
    target[property] = value
    return value
  }
})
