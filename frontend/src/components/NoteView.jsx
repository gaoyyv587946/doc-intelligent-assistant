import React from 'react';

const NoteView = ({ 
  notes = [], 
  fileNames = [], 
  noteForm, 
  editingNote, 
  showForm, 
  noteStatus, 
  noteLoading, 
  setShowForm, 
  setEditingNote, 
  setNoteForm, 
  handleCreateNote, 
  handleUpdateNote, 
  handleDeleteNote 
}) => {
  // 防御性编程：确保 notes 和 fileNames 是数组
  const noteList = Array.isArray(notes) ? notes : [];
  const fileNameList = Array.isArray(fileNames) ? fileNames : [];
  
  // 辅助函数：处理 tags（可能是字符串或数组）
  const renderTags = (tags) => {
    if (!tags) return null;
    const tagList = Array.isArray(tags) ? tags : 
                   (typeof tags === 'string' ? tags.split(',') : []);
    return tagList.map((tag, i) => (
      <span key={i} className="tag">
        {typeof tag === 'string' ? tag.trim() : String(tag)}
      </span>
    ));
  };
  
  return (
    <div className="knowledge-container">
      <div className="knowledge-header">
        <div className="knowledge-header-left">
          <h2>知识库笔记</h2>
          <span className="note-count">共 {noteList.length} 条笔记</span>
        </div>
        <div className="knowledge-header-right">
          <button className="btn-primary" onClick={() => { setShowForm(true); setEditingNote(null); }}>新建笔记</button>
        </div>
      </div>

      {noteStatus && <div className="status-message">{noteStatus}</div>}

      {(showForm || editingNote) && (
        <div className="note-form">
          <h3>{editingNote ? '编辑笔记' : '新建笔记'}</h3>
          <div className="form-group">
            <label>文件名</label>
            <input
              type="text"
              list="fileNames"
              value={editingNote ? editingNote.fileName : noteForm.fileName}
              onChange={(e) => editingNote
                ? setEditingNote({ ...editingNote, fileName: e.target.value })
                : setNoteForm({ ...noteForm, fileName: e.target.value })
              }
              placeholder="可选，关联文件名"
            />
            <datalist id="fileNames">
              {fileNameList.map((name, i) => <option key={i} value={name} />)}
            </datalist>
          </div>
          <div className="form-group">
            <label>标题 *</label>
            <input
              type="text"
              value={editingNote ? editingNote.title : noteForm.title}
              onChange={(e) => editingNote
                ? setEditingNote({ ...editingNote, title: e.target.value })
                : setNoteForm({ ...noteForm, title: e.target.value })
              }
              placeholder="笔记标题"
              required
            />
          </div>
          <div className="form-group">
            <label>内容 *</label>
            <textarea
              value={editingNote ? editingNote.content : noteForm.content}
              onChange={(e) => editingNote
                ? setEditingNote({ ...editingNote, content: e.target.value })
                : setNoteForm({ ...noteForm, content: e.target.value })
              }
              placeholder="笔记内容"
              rows={4}
              required
            />
          </div>
          <div className="form-group">
            <label>标签</label>
            <input
              type="text"
              value={editingNote ? editingNote.tags : noteForm.tags}
              onChange={(e) => editingNote
                ? setEditingNote({ ...editingNote, tags: e.target.value })
                : setNoteForm({ ...noteForm, tags: e.target.value })
              }
              placeholder="用逗号分隔多个标签"
            />
          </div>
          <div className="form-actions">
            <button className="btn-primary" onClick={editingNote ? handleUpdateNote : handleCreateNote}>
              {editingNote ? '保存' : '创建'}
            </button>
            <button className="btn-secondary" onClick={() => { setShowForm(false); setEditingNote(null); }}>取消</button>
          </div>
        </div>
      )}

      <div className="notes-list">
        {noteList.length === 0 && !noteLoading && (
          <div className="empty-state"><p>暂无笔记，点击「新建笔记」创建</p></div>
        )}
        {noteList.map((note) => (
          <div key={note.id} className="note-card">
            <div className="note-header">
              <h3 className="note-title">{note.title || '未命名笔记'}</h3>
              <div className="note-actions">
                <button className="btn-secondary btn-sm" onClick={() => { setEditingNote(note); setShowForm(false); }}>编辑</button>
                <button className="btn-danger btn-sm" onClick={() => handleDeleteNote(note.id)}>删除</button>
              </div>
            </div>
            {note.fileName && <div className="note-filename">📄 {note.fileName}</div>}
            <div className="note-content">{note.content || ''}</div>
            {note.tags && (
              <div className="note-tags">
                {renderTags(note.tags)}
              </div>
            )}
            <div className="note-meta">
              创建于 {note.createdAt?.substring(0, 16)?.replace('T', ' ') || '未知时间'}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

export default NoteView;
