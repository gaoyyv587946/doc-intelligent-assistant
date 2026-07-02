import React from 'react';

const Header = ({ 
  username, 
  isAdmin, 
  theme, 
  toggleTheme, 
  handleLogout, 
  activeTab, 
  setActiveTab 
}) => {
  return (
    <header className="header">
      <div className="header-left">
        <h1>AI Agent</h1>
        <nav className="tabs">
          <button className={`tab ${activeTab === 'chat' ? 'active' : ''}`} onClick={() => setActiveTab('chat')}>💬 聊天</button>
          {isAdmin && (
            <>
              <button className={`tab ${activeTab === 'docs' ? 'active' : ''}`} onClick={() => setActiveTab('docs')}>📄 文档管理</button>
              <button className={`tab ${activeTab === 'knowledge' ? 'active' : ''}`} onClick={() => setActiveTab('knowledge')}>📚 知识库</button>
              <button className={`tab ${activeTab === 'analyzer' ? 'active' : ''}`} onClick={() => setActiveTab('analyzer')}>🔍 检索分析</button>
              <button className={`tab ${activeTab === 'weight-tuning' ? 'active' : ''}`} onClick={() => setActiveTab('weight-tuning')}>⚖️ 权重调优</button>
              <button className={`tab ${activeTab === 'users' ? 'active' : ''}`} onClick={() => setActiveTab('users')}>👥 用户管理</button>
            </>
          )}
        </nav>
      </div>
      <div className="header-actions">
        <button className="btn-icon" onClick={toggleTheme} title="切换主题">
          {theme === 'dark' ? '☀️' : '🌙'}
        </button>
        <span className="user-info">
          {username}
          {isAdmin && <span className="badge-admin">Admin</span>}
        </span>
        <button className="btn-secondary" onClick={handleLogout}>退出</button>
      </div>
    </header>
  );
};

export default Header;
