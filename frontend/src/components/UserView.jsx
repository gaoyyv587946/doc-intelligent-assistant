import React from 'react';

const UserView = ({ 
  users = [], 
  userStatus, 
  userLoading, 
  showCreateUser, 
  newUser, 
  editingUserId, 
  newPassword, 
  setShowCreateUser, 
  setNewUser, 
  setEditingUserId, 
  setNewPassword, 
  handleCreateUser, 
  handleChangePassword, 
  handleToggleAdmin, 
  handleForceOffline, 
  handleDeleteUser 
}) => {
  // 防御性编程：确保 users 是数组
  const userList = Array.isArray(users) ? users : [];
  
  return (
    <div className="knowledge-container">
      <div className="doc-section-header">
        <div className="knowledge-header-left">
          <h2>用户管理</h2>
        </div>
        <div className="knowledge-header-right">
          <button className="btn-primary" onClick={() => setShowCreateUser(true)}>创建用户</button>
        </div>
      </div>

      {userStatus && <div className="status-message">{userStatus}</div>}

      {showCreateUser && (
        <div className="note-form">
          <h3>创建新用户</h3>
          <div className="form-group">
            <label>用户名 *</label>
            <input type="text" value={newUser.username} onChange={(e) => setNewUser({ ...newUser, username: e.target.value })} placeholder="用户名" required />
          </div>
          <div className="form-group">
            <label>密码 *</label>
            <input type="password" value={newUser.password} onChange={(e) => setNewUser({ ...newUser, password: e.target.value })} placeholder="密码" required />
          </div>
          <div className="form-group checkbox">
            <label>
              <input type="checkbox" checked={newUser.admin} onChange={(e) => setNewUser({ ...newUser, admin: e.target.checked })} />
              管理员权限
            </label>
          </div>
          <div className="form-actions">
            <button className="btn-primary" onClick={handleCreateUser}>创建</button>
            <button className="btn-secondary" onClick={() => setShowCreateUser(false)}>取消</button>
          </div>
        </div>
      )}

      {userList.length === 0 && !userLoading ? (
        <div className="empty-state"><p>暂无用户</p></div>
      ) : (
        <div className="doc-list">
          {userList.map(u => (
            <div key={u.id} className="doc-card user-card">
              <div className="doc-card-info">
                <span className="doc-name">
                  {u.username || '未知用户'}
                  {u.admin && <span className="badge-admin">Admin</span>}
                  <span className={`badge-online ${u.online ? 'online' : 'offline'}`}>
                    {u.online ? '在线' : '离线'}
                  </span>
                </span>
                <span className="doc-size">创建于 {u.createdAt?.substring(0, 10) || '未知时间'}</span>
              </div>
              <div className="user-card-actions">
                {editingUserId === u.id ? (
                  <div className="inline-password">
                    <input type="password" placeholder="新密码" value={newPassword} onChange={e => setNewPassword(e.target.value)} />
                    <button className="btn-primary btn-sm" onClick={() => handleChangePassword(u.id)}>确认</button>
                    <button className="btn-secondary btn-sm" onClick={() => { setEditingUserId(null); setNewPassword(''); }}>取消</button>
                  </div>
                ) : (
                  <>
                    <button className="btn-secondary btn-sm" onClick={() => { setEditingUserId(u.id); setNewPassword(''); }} title="修改密码">改密</button>
                    <button className={`btn-sm ${u.admin ? 'btn-danger' : 'btn-primary'}`} onClick={() => handleToggleAdmin(u.id, u.admin)} title={u.admin ? '取消管理员' : '设为管理员'}>
                      {u.admin ? '取消管理' : '设为管理'}
                    </button>
                    {u.online && (
                      <button className="btn-danger btn-sm" onClick={() => handleForceOffline(u.username)} title="强制下线">踢下线</button>
                    )}
                    <button className="btn-danger-icon btn-sm" onClick={() => handleDeleteUser(u.id, u.username)} title="删除用户">🗑️</button>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default UserView;
