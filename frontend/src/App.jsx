import React, { useState, useRef, useEffect } from 'react';
import { motion } from 'framer-motion';
import {
  chat, chatStream, recoverStream, uploadDocument, login, getToken, removeToken, getIsAdmin,
  listNotes, createNote, updateNote, deleteNote, getFileNames,
  listDocuments, getDocStats, reloadDocuments, reloadDocument, clearAllVectors,
  clearDocumentVectors,
  listUsers, createUser, deleteUser, updateUserPassword, updateUserAdmin,
  forceOffline, allowOnline, analyzeSearch, tuneBM25, runWeightTuning,
  decryptUrl
} from './api';

import LoginForm from './components/LoginForm';
import Header from './components/Header';
import ChatView from './components/ChatView';
import DocumentView from './components/DocumentView';
import NoteView from './components/NoteView';
import UserView from './components/UserView';
import SearchView from './components/SearchView';
import WeightTuningView from './components/WeightTuningView';

function App() {
  const [isLoggedIn, setIsLoggedIn] = useState(!!getToken());
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);

  const [loginError, setLoginError] = useState('');
  const [activeTab, setActiveTab] = useState('chat');
  const [isAdmin, setIsAdminState] = useState(getIsAdmin());

  const [theme, setTheme] = useState(() => {
    return localStorage.getItem('theme') || 'dark';
  });

  const toggleTheme = () => {
    const newTheme = theme === 'dark' ? 'light' : 'dark';
    setTheme(newTheme);
    localStorage.setItem('theme', newTheme);
    document.documentElement.setAttribute('data-theme', newTheme);
  };

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [isStreaming, setIsStreaming] = useState(false);
  const [needReconnect, setNeedReconnect] = useState(false);
  const messagesEndRef = useRef(null);
  const messagesContainerRef = useRef(null);
  const cancelStreamRef = useRef(null);
  const sessionId = useRef('session_' + generateUUID());

  function generateUUID() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID();
    }
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
      const r = Math.random() * 16 | 0;
      const v = c === 'x' ? r : (r & 0x3 | 0x8);
      return v.toString(16);
    });
  }

  const [documents, setDocuments] = useState([]);
  const [docStats, setDocStats] = useState(null);
  const [docStatus, setDocStatus] = useState('');
  const [docActionLoading, setDocActionLoading] = useState('');

  const [notes, setNotes] = useState([]);
  const [fileNames, setFileNames] = useState([]);
  const [noteForm, setNoteForm] = useState({ fileName: '', title: '', content: '', tags: '' });
  const [editingNote, setEditingNote] = useState(null);
  const [noteStatus, setNoteStatus] = useState('');
  const [noteLoading, setNoteLoading] = useState(false);
  const [showForm, setShowForm] = useState(false);

  const [users, setUsers] = useState([]);
  const [userStatus, setUserStatus] = useState('');
  const [userLoading, setUserLoading] = useState(false);
  const [showCreateUser, setShowCreateUser] = useState(false);
  const [newUser, setNewUser] = useState({ username: '', password: '', admin: false });
  const [editingUserId, setEditingUserId] = useState(null);
  const [newPassword, setNewPassword] = useState('');

  const [searchQuery, setSearchQuery] = useState('');
  const [searchResult, setSearchResult] = useState(null);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchError, setSearchError] = useState('');
  const [analyzerSubTab, setAnalyzerSubTab] = useState('rrf');
  const [expandedBm25, setExpandedBm25] = useState({});
  const [tuneK1, setTuneK1] = useState(1.5);
  const [tuneB, setTuneB] = useState(0.4);
  const [tuneResult, setTuneResult] = useState(null);
  const [tuneLoading, setTuneLoading] = useState(false);

  const [wtResult, setWtResult] = useState(null);
  const [wtLoading, setWtLoading] = useState(false);
  const [wtCustomWeights, setWtCustomWeights] = useState('');
  const [wtK, setWtK] = useState(5);
  const [wtError, setWtError] = useState('');

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (!isLoggedIn) return;
    if (activeTab === 'docs') {
      loadDocuments();
    } else if (activeTab === 'knowledge') {
      loadNotes();
    } else if (activeTab === 'users') {
      loadUsers();
    }
  }, [activeTab, isLoggedIn]);

  const loadDocuments = async () => {
    try {
      const data = await listDocuments();
      // 防御性编程：确保 data 是数组
      // API 可能返回：数组、{ documents: [...] }、{ data: [...] }、{ list: [...] } 等
      let docList = [];
      if (Array.isArray(data)) {
        docList = data;
      } else if (data && typeof data === 'object') {
        docList = data.documents || data.data || data.list || data.items || [];
      }
      setDocuments(Array.isArray(docList) ? docList : []);
      const statsData = await getDocStats();
      // 后端返回 { success, keywordIndex: { docCount, termCount, metadataCount } }
      // 前端期望 { loadedCount, vectorCount }
      const stats = {
        loadedCount: statsData?.keywordIndex?.docCount || 0,
        vectorCount: statsData?.keywordIndex?.termCount || 0
      };
      setDocStats(stats);
    } catch (e) {
      setDocStatus('加载失败: ' + e.message);
    }
  };

  const loadNotes = async () => {
    setNoteLoading(true);
    try {
      const data = await listNotes();
      // 防御性编程：确保 data 是数组
      setNotes(Array.isArray(data) ? data : (data?.notes || data?.data || []));
      const names = await getFileNames();
      setFileNames(Array.isArray(names) ? names : (names?.fileNames || names?.data || []));
    } catch (e) {
      setNoteStatus('加载失败: ' + e.message);
    }
    setNoteLoading(false);
  };

  const loadUsers = async () => {
    setUserLoading(true);
    try {
      const data = await listUsers();
      // 防御性编程：确保 data 是数组
      setUsers(Array.isArray(data) ? data : (data?.users || data?.data || []));
    } catch (e) {
      setUserStatus('加载失败: ' + e.message);
    }
    setUserLoading(false);
  };

  const handleLogin = async (e) => {
    e.preventDefault();
    setLoginError('');
    try {
      const data = await login(username, password);
      setIsLoggedIn(true);
      setIsAdminState(data.admin || false);
    } catch (e) {
      setLoginError(e.message || '登录失败');
    }
  };

  const handleLogout = () => {
    removeToken();
    setIsLoggedIn(false);
    setIsAdminState(false);
    setMessages([]);
    setActiveTab('chat');
  };

  const handleSend = async () => {
    if (!input.trim() || loading || isStreaming) return;
    const userMessage = input.trim();
    setInput('');
    setLoading(true);
    setNeedReconnect(false);

    const userMsg = {
      role: 'user',
      content: userMessage,
      time: new Date().toLocaleTimeString()
    };
    setMessages(prev => [...prev, userMsg]);

    const assistantMsg = {
      role: 'assistant',
      content: '',
      streaming: true,
      time: new Date().toLocaleTimeString()
    };
    setMessages(prev => [...prev, assistantMsg]);

    try {
      setIsStreaming(true);
      cancelStreamRef.current = chatStream(
        sessionId.current,
        userMessage,
        {
          onStatus: (status) => {
            setMessages(prev => {
              const newMessages = [...prev];
              const lastMsg = newMessages[newMessages.length - 1];
              if (lastMsg && lastMsg.role === 'assistant') {
                newMessages[newMessages.length - 1] = {
                  ...lastMsg,
                  content: status.message || '处理中...'
                };
              }
              return newMessages;
            });
          },
          onDone: (data) => {
            setMessages(prev => {
              const newMessages = [...prev];
              const lastMsg = newMessages[newMessages.length - 1];
              if (lastMsg && lastMsg.role === 'assistant') {
                newMessages[newMessages.length - 1] = {
                  ...lastMsg,
                  content: data.reply || '处理完成',
                  streaming: false,
                  urlToOpen: data.urlToOpen || null
                };
              }
              return newMessages;
            });
            // 如果有 urlToOpen，解密并打开浏览器
            if (data.urlToOpen) {
              try {
                const decryptedUrl = decryptUrl(data.urlToOpen);
                if (decryptedUrl && decryptedUrl !== data.urlToOpen) {
                  window.open(decryptedUrl, '_blank');
                }
              } catch (e) {
                console.error('打开浏览器失败:', e);
              }
            }
            setLoading(false);
            setIsStreaming(false);
          },
          onError: (error) => {
            setMessages(prev => {
              const newMessages = [...prev];
              const lastMsg = newMessages[newMessages.length - 1];
              if (lastMsg && lastMsg.role === 'assistant') {
                newMessages[newMessages.length - 1] = {
                  ...lastMsg,
                  content: '错误: ' + error.message,
                  streaming: false,
                  isError: true
                };
              }
              return newMessages;
            });
            setLoading(false);
            setIsStreaming(false);
          },
          onAuthExpired: (error) => {
            handleLogout();
          }
        }
      );
    } catch (e) {
      setMessages(prev => {
        const newMessages = [...prev];
        const lastMsg = newMessages[newMessages.length - 1];
        if (lastMsg && lastMsg.role === 'assistant') {
          newMessages[newMessages.length - 1] = {
            ...lastMsg,
            content: '错误: ' + e.message,
            streaming: false,
            isError: true
          };
        }
        return newMessages;
      });
      setLoading(false);
      setIsStreaming(false);
    }
  };

  const handleCancelStream = () => {
    if (cancelStreamRef.current) {
      cancelStreamRef.current();
      setNeedReconnect(true);
    }
    setIsStreaming(false);
    setLoading(false);
  };

  const handleReconnect = async () => {
    setNeedReconnect(false);
    setLoading(true);
    try {
      const data = await recoverStream(sessionId.current);
      setMessages(prev => {
        const newMessages = [...prev];
        const lastMsg = newMessages[newMessages.length - 1];
        if (lastMsg && lastMsg.role === 'assistant') {
          newMessages[newMessages.length - 1] = {
            ...lastMsg,
            content: data.reply || '重连成功',
            streaming: false
          };
        }
        return newMessages;
      });
    } catch (e) {
      setMessages(prev => {
        const newMessages = [...prev];
        const lastMsg = newMessages[newMessages.length - 1];
        if (lastMsg && lastMsg.role === 'assistant') {
          newMessages[newMessages.length - 1] = {
            ...lastMsg,
            content: '重连失败: ' + e.message,
            streaming: false,
            isError: true
          };
        }
        return newMessages;
      });
    }
    setLoading(false);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleUpload = async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    setDocActionLoading('upload');
    setDocStatus('上传中...');
    try {
      const result = await uploadDocument(file);
      setDocStatus(result.message || '上传成功');
      loadDocuments();
    } catch (e) {
      setDocStatus('上传失败: ' + e.message);
    }
    setDocActionLoading('');
  };

  const handleReloadAll = async () => {
    setDocActionLoading('reload');
    setDocStatus('重新加载中...');
    try {
      const result = await reloadDocuments();
      setDocStatus(result.message || '重载成功');
      loadDocuments();
    } catch (e) {
      setDocStatus('重载失败: ' + e.message);
    }
    setDocActionLoading('');
  };

  const handleReloadOne = async (fileName) => {
    setDocActionLoading('reload-' + fileName);
    try {
      const result = await reloadDocument(fileName);
      setDocStatus(result.message || '重载成功');
      loadDocuments();
    } catch (e) {
      setDocStatus('重载失败: ' + e.message);
    }
    setDocActionLoading('');
  };

  const handleClearAll = async () => {
    if (!window.confirm('确定清除所有向量数据？')) return;
    setDocActionLoading('clear');
    try {
      const result = await clearAllVectors();
      setDocStatus(result.message || '已清除');
      loadDocuments();
    } catch (e) {
      setDocStatus('清除失败: ' + e.message);
    }
    setDocActionLoading('');
  };

  const handleClearDoc = async (fileName) => {
    if (!window.confirm(`确定清除 ${fileName} 的向量数据？`)) return;
    setDocActionLoading('clear-' + fileName);
    try {
      const result = await clearDocumentVectors(fileName);
      setDocStatus(result.message || '已清除');
      loadDocuments();
    } catch (e) {
      setDocStatus('清除失败: ' + e.message);
    }
    setDocActionLoading('');
  };

  const handleCreateNote = async () => {
    if (!noteForm.title || !noteForm.content) {
      setNoteStatus('标题和内容不能为空');
      return;
    }
    try {
      await createNote(noteForm.fileName, noteForm.title, noteForm.content, noteForm.tags);
      setNoteStatus('创建成功');
      setNoteForm({ fileName: '', title: '', content: '', tags: '' });
      setShowForm(false);
      loadNotes();
    } catch (e) {
      setNoteStatus('创建失败: ' + e.message);
    }
  };

  const handleUpdateNote = async () => {
    if (!editingNote) return;
    try {
      await updateNote(editingNote.id, editingNote.fileName, editingNote.title, editingNote.content, editingNote.tags);
      setNoteStatus('更新成功');
      setEditingNote(null);
      loadNotes();
    } catch (e) {
      setNoteStatus('更新失败: ' + e.message);
    }
  };

  const handleDeleteNote = async (id) => {
    if (!window.confirm('确定删除此笔记？')) return;
    try {
      await deleteNote(id);
      setNoteStatus('删除成功');
      loadNotes();
    } catch (e) {
      setNoteStatus('删除失败: ' + e.message);
    }
  };

  const handleCreateUser = async () => {
    if (!newUser.username || !newUser.password) {
      setUserStatus('用户名和密码不能为空');
      return;
    }
    try {
      await createUser(newUser.username, newUser.password, newUser.admin);
      setUserStatus('创建成功');
      setNewUser({ username: '', password: '', admin: false });
      setShowCreateUser(false);
      loadUsers();
    } catch (e) {
      setUserStatus('创建失败: ' + e.message);
    }
  };

  const handleChangePassword = async (id) => {
    if (!newPassword) {
      setUserStatus('密码不能为空');
      return;
    }
    try {
      await updateUserPassword(id, newPassword);
      setUserStatus('密码修改成功');
      setEditingUserId(null);
      setNewPassword('');
    } catch (e) {
      setUserStatus('修改失败: ' + e.message);
    }
  };

  const handleToggleAdmin = async (id, currentAdmin) => {
    try {
      await updateUserAdmin(id, !currentAdmin);
      setUserStatus('权限更新成功');
      loadUsers();
    } catch (e) {
      setUserStatus('更新失败: ' + e.message);
    }
  };

  const handleForceOffline = async (username) => {
    if (!window.confirm(`确定强制用户 ${username} 下线？`)) return;
    try {
      await forceOffline(username);
      setUserStatus('已强制下线');
      loadUsers();
    } catch (e) {
      setUserStatus('操作失败: ' + e.message);
    }
  };

  const handleDeleteUser = async (id, username) => {
    if (!window.confirm(`确定删除用户 ${username}？`)) return;
    try {
      await deleteUser(id);
      setUserStatus('删除成功');
      loadUsers();
    } catch (e) {
      setUserStatus('删除失败: ' + e.message);
    }
  };

  const handleAnalyze = async () => {
    if (!searchQuery.trim()) return;
    setSearchLoading(true);
    setSearchError('');
    setSearchResult(null);
    try {
      const data = await analyzeSearch(searchQuery);
      setSearchResult(data);
    } catch (e) {
      setSearchError(e.message);
    }
    setSearchLoading(false);
  };

  const handleTune = async () => {
    if (!searchQuery.trim()) return;
    setTuneLoading(true);
    try {
      const data = await tuneBM25(searchQuery, tuneK1, tuneB);
      setTuneResult(data);
    } catch (e) {
      setSearchError(e.message);
    }
    setTuneLoading(false);
  };

  const handleRunWeightTuning = async () => {
    setWtLoading(true);
    setWtError('');
    setWtResult(null);
    try {
      const data = await runWeightTuning(wtCustomWeights, wtK);
      setWtResult(data);
    } catch (e) {
      setWtError(e.message);
    }
    setWtLoading(false);
  };

  const toggleBm25Expand = (key) => {
    setExpandedBm25(prev => ({ ...prev, [key]: !prev[key] }));
  };

  if (!isLoggedIn) {
    return (
      <LoginForm
        username={username}
        password={password}
        showPassword={showPassword}
        loginError={loginError}
        setUsername={setUsername}
        setPassword={setPassword}
        setShowPassword={setShowPassword}
        handleLogin={handleLogin}
      />
    );
  }

  return (
    <div className="app">
      <Header
        username={username}
        isAdmin={isAdmin}
        theme={theme}
        toggleTheme={toggleTheme}
        handleLogout={handleLogout}
        activeTab={activeTab}
        setActiveTab={setActiveTab}
      />

      <main className="main-content">
        {activeTab === 'chat' && (
          <ChatView
            messages={messages}
            input={input}
            loading={loading}
            isStreaming={isStreaming}
            needReconnect={needReconnect}
            messagesEndRef={messagesEndRef}
            messagesContainerRef={messagesContainerRef}
            setInput={setInput}
            handleKeyDown={handleKeyDown}
            handleSend={handleSend}
            handleCancelStream={handleCancelStream}
            handleReconnect={handleReconnect}
          />
        )}

        {activeTab === 'docs' && (
          <DocumentView
            documents={documents}
            docStats={docStats}
            docStatus={docStatus}
            docActionLoading={docActionLoading}
            handleUpload={handleUpload}
            handleReloadAll={handleReloadAll}
            handleClearAll={handleClearAll}
            handleReloadOne={handleReloadOne}
            handleClearDoc={handleClearDoc}
          />
        )}

        {activeTab === 'knowledge' && (
          <NoteView
            notes={notes}
            fileNames={fileNames}
            noteForm={noteForm}
            editingNote={editingNote}
            showForm={showForm}
            noteStatus={noteStatus}
            noteLoading={noteLoading}
            setShowForm={setShowForm}
            setEditingNote={setEditingNote}
            setNoteForm={setNoteForm}
            handleCreateNote={handleCreateNote}
            handleUpdateNote={handleUpdateNote}
            handleDeleteNote={handleDeleteNote}
          />
        )}

        {activeTab === 'analyzer' && (
          <SearchView
            searchQuery={searchQuery}
            setSearchQuery={setSearchQuery}
            searchResult={searchResult}
            searchLoading={searchLoading}
            searchError={searchError}
            analyzerSubTab={analyzerSubTab}
            setAnalyzerSubTab={setAnalyzerSubTab}
            handleAnalyze={handleAnalyze}
            expandedBm25={expandedBm25}
            toggleBm25Expand={toggleBm25Expand}
            tuneK1={tuneK1}
            setTuneK1={setTuneK1}
            tuneB={tuneB}
            setTuneB={setTuneB}
            tuneResult={tuneResult}
            tuneLoading={tuneLoading}
            handleTune={handleTune}
          />
        )}

        {activeTab === 'weight-tuning' && isAdmin && (
          <WeightTuningView
            wtResult={wtResult}
            wtLoading={wtLoading}
            wtError={wtError}
            wtCustomWeights={wtCustomWeights}
            wtK={wtK}
            setWtCustomWeights={setWtCustomWeights}
            setWtK={setWtK}
            handleRunWeightTuning={handleRunWeightTuning}
          />
        )}

        {activeTab === 'users' && isAdmin && (
          <UserView
            users={users}
            userStatus={userStatus}
            userLoading={userLoading}
            showCreateUser={showCreateUser}
            newUser={newUser}
            editingUserId={editingUserId}
            newPassword={newPassword}
            setShowCreateUser={setShowCreateUser}
            setNewUser={setNewUser}
            setEditingUserId={setEditingUserId}
            setNewPassword={setNewPassword}
            handleCreateUser={handleCreateUser}
            handleChangePassword={handleChangePassword}
            handleToggleAdmin={handleToggleAdmin}
            handleForceOffline={handleForceOffline}
            handleDeleteUser={handleDeleteUser}
          />
        )}
      </main>
    </div>
  );
}

export default App;