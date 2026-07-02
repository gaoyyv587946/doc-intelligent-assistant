const API_BASE = '/api';

/**
 * 解密ENC:开头的加密URL（与后端UrlEncryptor一致）
 * 只用于前端打开浏览器，不对外暴露
 */
export function decryptUrl(encrypted) {
  if (!encrypted || !encrypted.startsWith('ENC:')) return encrypted;
  try {
    const base64 = encrypted.substring(4);
    // URL-safe Base64 → standard Base64
    const standard = base64.replace(/-/g, '+').replace(/_/g, '/');
    // 补齐padding
    const padded = standard + '='.repeat((4 - standard.length % 4) % 4);
    return atob(padded);
  } catch {
    return encrypted;
  }
}

function getToken() {
  return localStorage.getItem('token');
}

function setToken(token) {
  localStorage.setItem('token', token);
}

function removeToken() {
  localStorage.removeItem('token');
  localStorage.removeItem('isAdmin');
}

function getIsAdmin() {
  const val = localStorage.getItem('isAdmin');
  return val === 'true' || val === '1' || val === 'yes';
}

function setIsAdmin(val) {
  localStorage.setItem('isAdmin', val ? 'true' : 'false');
}

function authHeaders() {
  const token = getToken();
  return token ? { 'Authorization': `Bearer ${token}` } : {};
}

export async function login(username, password) {
  const res = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  });
  const data = await res.json();
  if (res.ok && data.token) {
    setToken(data.token);
    setIsAdmin(data.admin);
  }
  return data;
}

export async function chat(sessionId, message) {
  const res = await fetch(`${API_BASE}/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({ sessionId, message })
  });
  if (res.status === 401) {
    removeToken();
    throw new Error('登录已过期，请重新登录');
  }
  if (res.status === 403) {
    throw new Error('无权限访问');
  }
  return res.json();
}

/**
 * SSE流式聊天
 * @param {string} sessionId - 会话ID
 * @param {string} message - 用户消息
 * @param {object} callbacks - 回调函数
 * @param {function} callbacks.onStatus - 接收到处理状态时的回调（实时反馈）
 * @param {function} callbacks.onDone - 完成时的回调，参数为完整响应数据
 * @param {function} callbacks.onError - 错误时的回调
 * @param {function} callbacks.onAuthExpired - 认证过期时的回调
 * @returns {function} 取消函数，调用后中断流式输出
 */
export function chatStream(sessionId, message, { onStatus, onDone, onError, onAuthExpired }) {
  const token = getToken();
  const params = new URLSearchParams({
    sessionId,
    message,
    token: token || ''  // 将token作为URL参数，确保SSE连接期间token可用
  });

  const url = `${API_BASE}/chat/stream?${params}`;

  // 使用AbortController支持取消
  const controller = new AbortController();

  fetch(url, {
    headers: {
      'Authorization': `Bearer ${token}`,
      'Accept': 'text/event-stream'
    },
    signal: controller.signal
  }).then(response => {
    if (!response.ok) {
      if (response.status === 401) {
        removeToken();
        throw new Error('登录已过期，请重新登录');
      }
      if (response.status === 403) {
        throw new Error('无权限访问');
      }
      throw new Error(`HTTP ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    function processBuffer() {
      // 按行分割
      const lines = buffer.split('\n');
      // 最后一行可能不完整，保留到下次处理
      buffer = lines.pop() || '';

      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed) continue;

        // 解析SSE格式
        if (trimmed.startsWith('event:')) {
          // 事件类型，忽略（我们通过data内容判断）
          continue;
        }

        if (trimmed.startsWith('data:')) {
          const data = trimmed.substring(5).trim();
          if (!data) continue;

          // 判断事件类型
          // status事件：data是JSON包含type和message
          // done事件：data是JSON包含reply
          // error事件：data是JSON包含message
          // auth_expired事件：data是JSON包含authExpired
          try {
            const parsed = JSON.parse(data);
            // JSON解析成功，判断事件类型
            if (parsed.authExpired && onAuthExpired) {
              // auth_expired事件 - 认证过期
              onAuthExpired(new Error(parsed.message || '登录已过期，请重新登录'));
            } else if (parsed.type === 'status' && onStatus) {
              // status事件 - 处理状态反馈
              onStatus(parsed);
            } else if (parsed.message && onError) {
              // error事件
              onError(new Error(parsed.message));
            } else if (parsed.reply !== undefined && onDone) {
              // done事件
              onDone(parsed);
            }
          } catch {
            // JSON解析失败，忽略
          }
        }
      }
    }

    function read() {
      reader.read().then(({ done, value }) => {
        if (done) {
          // 处理剩余buffer
          if (buffer.trim()) {
            processBuffer();
          }
          return;
        }

        // 将新数据追加到buffer
        buffer += decoder.decode(value, { stream: true });
        processBuffer();

        // 继续读取
        read();
      }).catch(err => {
        if (err.name !== 'AbortError' && onError) {
          onError(err);
        }
      });
    }

    read();
  }).catch(err => {
    if (err.name !== 'AbortError' && onError) {
      onError(err);
    }
  });

  // 返回取消函数
  return () => controller.abort();
}

/**
 * 恢复已缓存的流式输出内容（用于断线重连）
 * @param {string} sessionId - 会话ID
 * @returns {Promise<object>} 包含content、completed、error、urlToOpen等字段
 */
export async function recoverStream(sessionId) {
  const token = getToken();
  const params = new URLSearchParams({ sessionId });
  const url = `${API_BASE}/chat/stream/recover?${params}`;

  const res = await fetch(url, {
    headers: {
      'Authorization': `Bearer ${token}`
    }
  });

  if (res.status === 401) {
    removeToken();
    throw new Error('登录已过期，请重新登录');
  }
  if (res.status === 403) {
    throw new Error('无权限访问');
  }
  return res.json();
}

/**
 * 清除流式会话缓存
 * @param {string} sessionId - 会话ID
 * @returns {Promise<object>}
 */
export async function clearStreamCache(sessionId) {
  const token = getToken();
  const params = new URLSearchParams({ sessionId });
  const url = `${API_BASE}/chat/stream/cache?${params}`;

  const res = await fetch(url, {
    method: 'DELETE',
    headers: {
      'Authorization': `Bearer ${token}`
    }
  });

  if (res.status === 401) {
    removeToken();
    throw new Error('登录已过期，请重新登录');
  }
  return res.json();
}

export async function uploadDocument(file) {
  const formData = new FormData();
  formData.append('file', file);

  const res = await fetch(`${API_BASE}/documents/upload`, {
    method: 'POST',
    headers: authHeaders(),
    body: formData
  });
  if (res.status === 401) {
    removeToken();
    throw new Error('登录已过期，请重新登录');
  }
  if (res.status === 403) {
    throw new Error('无权限访问');
  }
  return res.json();
}

export { getToken, removeToken, getIsAdmin };

// ==================== 知识库笔记 API ====================

function handleAuthError(res, silent = false) {
  if (res.status === 401) {
    if (!silent) {
      removeToken();
      throw new Error('登录已过期，请重新登录');
    }
    throw new Error('认证失败');
  }
  if (res.status === 403) {
    throw new Error('无权限访问');
  }
}

export async function listNotes() {
  const res = await fetch(`${API_BASE}/notes`, {
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function createNote(fileName, title, content, tags) {
  const res = await fetch(`${API_BASE}/notes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ fileName, title, content, tags })
  });
  handleAuthError(res);
  return res.json();
}

export async function updateNote(id, fileName, title, content, tags) {
  const res = await fetch(`${API_BASE}/notes/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ fileName, title, content, tags })
  });
  handleAuthError(res);
  return res.json();
}

export async function deleteNote(id) {
  const res = await fetch(`${API_BASE}/notes/${id}`, {
    method: 'DELETE',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function getFileNames() {
  const res = await fetch(`${API_BASE}/notes/filenames`, {
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

// ==================== 文档管理 API ====================

export async function listDocuments() {
  const res = await fetch(`${API_BASE}/documents`, {
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function getTaskStatus(taskId) {
  const res = await fetch(`${API_BASE}/documents/task/${taskId}`, {
    headers: authHeaders()
  });
  handleAuthError(res, true); // 轮询时静默处理, 不踢登录
  return res.json();
}

export async function reloadDocuments() {
  const res = await fetch(`${API_BASE}/documents/reload`, {
    method: 'POST',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function reloadDocument(fileName) {
  const res = await fetch(`${API_BASE}/documents/reload/${encodeURIComponent(fileName)}`, {
    method: 'POST',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function clearAllVectors() {
  const res = await fetch(`${API_BASE}/documents/vectors`, {
    method: 'DELETE',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function clearDocumentVectors(fileName) {
  const res = await fetch(`${API_BASE}/documents/vectors/${encodeURIComponent(fileName)}`, {
    method: 'DELETE',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function getDocStats() {
  const res = await fetch(`${API_BASE}/documents/stats`, {
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

// ==================== 用户管理 API (仅Admin) ====================

export async function listUsers() {
  const res = await fetch(`${API_BASE}/users`, { headers: authHeaders() });
  handleAuthError(res);
  return res.json();
}

export async function createUser(username, password, admin) {
  const res = await fetch(`${API_BASE}/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ username, password, admin })
  });
  handleAuthError(res);
  return res.json();
}

export async function deleteUser(id) {
  const res = await fetch(`${API_BASE}/users/${id}`, {
    method: 'DELETE',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function updateUserPassword(id, password) {
  const res = await fetch(`${API_BASE}/users/${id}/password`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ password })
  });
  handleAuthError(res);
  return res.json();
}

export async function updateUserAdmin(id, admin) {
  const res = await fetch(`${API_BASE}/users/${id}/admin`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ admin })
  });
  handleAuthError(res);
  return res.json();
}

export async function forceOffline(username) {
  const res = await fetch(`${API_BASE}/users/${encodeURIComponent(username)}/force-offline`, {
    method: 'POST',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function allowOnline(username) {
  const res = await fetch(`${API_BASE}/users/${encodeURIComponent(username)}/allow-online`, {
    method: 'POST',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

// ========== 检索分析 ==========
export async function analyzeSearch(query) {
  const res = await fetch(`${API_BASE}/search/analyze?q=${encodeURIComponent(query)}`, {
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

export async function tuneBM25(query, k1, b) {
  const params = new URLSearchParams({ q: query, k1, b });
  const res = await fetch(`${API_BASE}/search/tune?${params}`, {
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}

// ========== 权重调优实验 ==========
export async function runWeightTuning(customWeights, k) {
  const params = new URLSearchParams({ k: k || 5 });
  if (customWeights) params.set('customWeights', customWeights);
  const res = await fetch(`${API_BASE}/tuning/run?${params}`, {
    method: 'POST',
    headers: authHeaders()
  });
  handleAuthError(res);
  return res.json();
}
