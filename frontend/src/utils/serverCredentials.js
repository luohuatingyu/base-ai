export const MAX_PRIVATE_KEY_FILE_SIZE = 65_536

/** 读取本地私钥文本，并在进入服务器表单前限制空内容和字节大小。 */
export async function readPrivateKeyFile(file) {
  if (!file || file.size === 0) throw new Error('PRIVATE_KEY_FILE_EMPTY')
  if (file.size > MAX_PRIVATE_KEY_FILE_SIZE) throw new Error('PRIVATE_KEY_FILE_TOO_LARGE')
  let content
  try {
    content = await file.text()
  } catch {
    throw new Error('PRIVATE_KEY_FILE_READ_FAILED')
  }
  if (!content.trim()) throw new Error('PRIVATE_KEY_FILE_EMPTY')
  if (new TextEncoder().encode(content).length > MAX_PRIVATE_KEY_FILE_SIZE) {
    throw new Error('PRIVATE_KEY_FILE_TOO_LARGE')
  }
  return content
}
