import React from 'react';

const DocumentView = ({ 
  documents = [], 
  docStats, 
  docStatus, 
  docActionLoading, 
  handleUpload, 
  handleReloadAll, 
  handleClearAll, 
  handleReloadOne, 
  handleClearDoc 
}) => {
  // 防御性编程：确保 documents 是数组
  const docList = Array.isArray(documents) ? documents : [];
  
  // 格式化文件大小
  const formatSize = (bytes) => {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  };
  
  // 格式化时间
  const formatTime = (timeStr) => {
    if (!timeStr) return '';
    try {
      const date = new Date(timeStr);
      return date.toLocaleDateString('zh-CN', { 
        month: '2-digit', 
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
      });
    } catch {
      return timeStr;
    }
  };
  
  return (
    <div className="knowledge-container">
      <div className="doc-section-header">
        <div className="knowledge-header-left">
          <h2>文档管理</h2>
          {docStats && (
            <div className="doc-stats">
              <span>已加载: {docStats.loadedCount} 个文档</span>
              <span>向量数: {docStats.vectorCount}</span>
            </div>
          )}
        </div>
        <div className="knowledge-header-right">
          <label className="btn-primary btn-upload">
            上传文档
            <input type="file" accept=".md,.txt,.json,.yaml,.yml,.pdf" onChange={handleUpload} hidden />
          </label>
          <button className="btn-secondary" onClick={handleReloadAll} disabled={docActionLoading === 'reload'}>
            {docActionLoading === 'reload' ? '重载中...' : '重载全部'}
          </button>
          <button className="btn-danger" onClick={handleClearAll} disabled={docActionLoading === 'clear'}>
            清除全部向量
          </button>
        </div>
      </div>

      {docStatus && <div className="status-message">{docStatus}</div>}

      {docList.length === 0 ? (
        <div className="empty-state">
          <p>暂无文档，点击「上传文档」添加</p>
        </div>
      ) : (
        <div className="doc-list">
          {docList.map((doc, i) => (
            <div key={i} className="doc-card">
              <div className="doc-card-info">
                <span className="doc-name">{doc.name || '未命名文档'}</span>
                <span className="doc-size">{formatSize(doc.size)}</span>
                <span className="doc-time">{formatTime(doc.lastModified)}</span>
              </div>
              <div className="doc-card-actions">
                <button className="btn-secondary btn-sm" onClick={() => handleReloadOne(doc.name)} disabled={docActionLoading === 'reload-' + doc.name}>重载</button>
                <button className="btn-danger btn-sm" onClick={() => handleClearDoc(doc.name)} disabled={docActionLoading === 'clear-' + doc.name}>清除向量</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default DocumentView;
