import React, { useState, useRef, useEffect, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { decryptUrl } from '../api';
import ReactMarkdown from 'react-markdown';

const ChatView = ({ 
  messages, 
  input, 
  loading, 
  isStreaming, 
  needReconnect, 
  messagesEndRef, 
  messagesContainerRef, 
  setInput, 
  handleKeyDown, 
  handleSend, 
  handleCancelStream, 
  handleReconnect,
  chatHistory = [],
  onHistoryClick
}) => {
  const [userQuestionPositions, setUserQuestionPositions] = useState([]);
  const [activeMarker, setActiveMarker] = useState(-1);
  const [showNavigator, setShowNavigator] = useState(false);
  const navigatorRef = useRef(null);

  // 提取用户问题的位置
  useEffect(() => {
    const positions = [];
    messages.forEach((msg, index) => {
      if (msg.role === 'user') {
        positions.push({
          index,
          preview: msg.content?.substring(0, 20) + (msg.content?.length > 20 ? '...' : ''),
          time: msg.time
        });
      }
    });
    setUserQuestionPositions(positions);
  }, [messages]);

  // 监听滚动，更新当前可见的消息
  useEffect(() => {
    const container = messagesContainerRef?.current;
    if (!container) return;

    const handleScroll = () => {
      const messageElements = container.querySelectorAll('.message');
      const containerRect = container.getBoundingClientRect();
      const scrollTop = container.scrollTop;
      const scrollHeight = container.scrollHeight - container.clientHeight;
      
      // 找到当前可见的用户消息
      let currentActive = -1;
      messageElements.forEach((el, idx) => {
        const rect = el.getBoundingClientRect();
        if (rect.top <= containerRect.top + 100 && messages[idx]?.role === 'user') {
          currentActive = idx;
        }
      });
      setActiveMarker(currentActive);
    };

    container.addEventListener('scroll', handleScroll);
    return () => container.removeEventListener('scroll', handleScroll);
  }, [messages, messagesContainerRef]);

  // 滚动到指定消息
  const scrollToMessage = useCallback((index) => {
    const container = messagesContainerRef?.current;
    if (!container) return;
    
    const messageElements = container.querySelectorAll('.message');
    if (messageElements[index]) {
      messageElements[index].scrollIntoView({ 
        behavior: 'smooth', 
        block: 'center' 
      });
    }
  }, [messagesContainerRef]);

  // 滚动到底部
  const scrollToBottom = useCallback(() => {
    messagesEndRef?.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messagesEndRef]);

  const handleOpenUrl = (encryptedUrl) => {
    try {
      const decryptedUrl = decryptUrl(encryptedUrl);
      if (decryptedUrl && decryptedUrl !== encryptedUrl) {
        window.open(decryptedUrl, '_blank');
      }
    } catch (e) {
      console.error('打开浏览器失败:', e);
    }
  };

  return (
    <div className="chat-container">
      {/* 历史问题快速导航 */}
      {userQuestionPositions.length > 1 && (
        <div 
          className="message-navigator"
          ref={navigatorRef}
          onMouseEnter={() => setShowNavigator(true)}
          onMouseLeave={() => setShowNavigator(false)}
        >
          <div className="navigator-track">
            {userQuestionPositions.map((pos, i) => {
              const percent = messages.length > 1 
                ? (pos.index / (messages.length - 1)) * 100 
                : 0;
              return (
                <div
                  key={i}
                  className={`navigator-dot ${activeMarker === pos.index ? 'active' : ''}`}
                  style={{ top: `${percent}%` }}
                  onClick={() => scrollToMessage(pos.index)}
                  title={pos.preview}
                >
                  <AnimatePresence>
                    {showNavigator && (
                      <motion.div
                        className="navigator-tooltip"
                        initial={{ opacity: 0, x: 10 }}
                        animate={{ opacity: 1, x: 0 }}
                        exit={{ opacity: 0, x: 10 }}
                      >
                        <span className="tooltip-time">{pos.time}</span>
                        <span className="tooltip-text">{pos.preview}</span>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
              );
            })}
          </div>
          
          {/* 回到底部按钮 */}
          <div className="navigator-bottom" onClick={scrollToBottom} title="回到底部">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <path d="M12 5v14M5 12l7 7 7-7" />
            </svg>
          </div>
        </div>
      )}

      {/* 消息列表 */}
      <div className="chat-messages" ref={messagesContainerRef}>
        {messages.length === 0 && (
          <div className="empty-state">
            <p>👋 你好！我是API文档智能助手</p>
            <p>可以问我关于接口的问题，例如预报接口有哪些？</p>
            <p>你可以问我关于API文档的问题，或者让我帮你调用API接口？</p>
            <p>可以问我关于文档路径，并且打开对应的地址页面</p>
          </div>
        )}
        {messages.map((msg, i) => (
          <motion.div
            key={i}
            className={`message ${msg.role} ${msg.error ? 'error' : ''} ${msg.isError ? 'is-error' : ''}`}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.3 }}
            data-index={i}
          >
            <div className="message-header">
              <span className="message-role">
                {msg.role === 'user' ? '👤 你' : msg.role === 'assistant' ? '🤖 AI' : '⚠️ 错误'}
              </span>&nbsp;
              <span className="message-time">{msg.time}</span>
            </div>
            <div className="message-content">
              {msg.streaming ? (
                <div className="streaming-content">
                  {msg.content || '请稍候，正在获取页面内容...'}
                  <span className="cursor-blink">|</span>
                </div>
              ) : (
                <div className="message-text">
                  <ReactMarkdown
                    components={{
                      code({ node, inline, className, children, ...props }) {
                        const match = /language-(\w+)/.exec(className || '');
                        return !inline && match ? (
                          <div className="code-block">
                            <div className="code-header">
                              <span className="code-language">{match[1]}</span>
                            </div>
                            <pre className={className} {...props}>
                              <code>{children}</code>
                            </pre>
                          </div>
                        ) : (
                          <code className={className} {...props}>
                            {children}
                          </code>
                        );
                      },
                      table({ children }) {
                        return (
                          <div className="table-container">
                            <table className="markdown-table">{children}</table>
                          </div>
                        );
                      },
                      thead({ children }) {
                        return <thead className="table-header">{children}</thead>;
                      },
                      tbody({ children }) {
                        return <tbody className="table-body">{children}</tbody>;
                      },
                      tr({ children }) {
                        return <tr className="table-row">{children}</tr>;
                      },
                      th({ children }) {
                        return <th className="table-cell-header">{children}</th>;
                      },
                      td({ children }) {
                        return <td className="table-cell">{children}</td>;
                      },
                      h1({ children }) {
                        return <h1 className="markdown-h1">{children}</h1>;
                      },
                      h2({ children }) {
                        return <h2 className="markdown-h2">{children}</h2>;
                      },
                      h3({ children }) {
                        return <h3 className="markdown-h3">{children}</h3>;
                      },
                      ul({ children }) {
                        return <ul className="markdown-ul">{children}</ul>;
                      },
                      ol({ children }) {
                        return <ol className="markdown-ol">{children}</ol>;
                      },
                      li({ children }) {
                        return <li className="markdown-li">{children}</li>;
                      },
                      blockquote({ children }) {
                        return <blockquote className="markdown-blockquote">{children}</blockquote>;
                      },
                      hr() {
                        return <hr className="markdown-hr" />;
                      },
                      strong({ children }) {
                        return <strong className="markdown-strong">{children}</strong>;
                      },
                      em({ children }) {
                        return <em className="markdown-em">{children}</em>;
                      },
                      a({ children, href }) {
                        return (
                          <a href={href} target="_blank" rel="noopener noreferrer" className="markdown-link">
                            {children}
                          </a>
                        );
                      }
                    }}
                  >
                    {msg.content}
                  </ReactMarkdown>
                </div>
              )}
              {msg.urlToOpen && !msg.streaming && (
                <button 
                  className="btn-open-url" 
                  onClick={() => handleOpenUrl(msg.urlToOpen)}
                  title="打开相关网页"
                >
                  🔗 打开网页
                </button>
              )}
            </div>
          </motion.div>
        ))}
        <div ref={messagesEndRef} />
      </div>

      {/* 输入区域 */}
      <div className="chat-input-area">
        <div className="input-container">
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="输入消息...Shift + enter换行"
            rows={1}
            disabled={loading || isStreaming}
          />
          <div className="input-buttons">
            {isStreaming ? (
              <button className="btn-cancel" onClick={handleCancelStream}>停止</button>
            ) : (
              <button className="btn-primary" onClick={handleSend} disabled={loading || !input.trim()}>
                {loading ? '处理中...' : '发送'}
              </button>
            )}
            {needReconnect && (
              <button className="btn-reconnect" onClick={handleReconnect} disabled={loading}>重连</button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ChatView;
