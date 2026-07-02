import React, { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import CharacterScene from './CharacterScene';

const LoginForm = ({ 
  username, 
  password, 
  showPassword, 
  loginError, 
  setUsername, 
  setPassword, 
  setShowPassword, 
  handleLogin 
}) => {
  const [isLoading, setIsLoading] = useState(false);
  const [greeting, setGreeting] = useState('');

  useEffect(() => {
    const hour = new Date().getHours();
    if (hour < 6) setGreeting('🌙 夜深了，注意休息哦~');
    else if (hour < 12) setGreeting('🌅 早上好！新的一天开始啦~');
    else if (hour < 18) setGreeting('☀️ 下午好！一起努力吧~');
    else setGreeting('🌆 晚上好！辛苦啦~');
  }, []);

  const onSubmit = async (e) => {
    setIsLoading(true);
    try {
      await handleLogin(e);
    } finally {
      setIsLoading(false);
    }
  };

  const togglePasswordVisibility = () => {
    setShowPassword(!showPassword);
  };

  return (
    <div className="login-container">
      {/* 左侧角色场景 */}
      <div className="login-left">
        <CharacterScene showPassword={showPassword} />
      </div>
      
      {/* 右侧登录框 */}
      <div className="login-right">
        <motion.div
          className="login-box"
          initial={{ opacity: 0, y: 30, scale: 0.95 }}
          animate={{ opacity: 1, y: 0, scale: 1 }}
          transition={{ duration: 0.6, type: 'spring', stiffness: 100 }}
        >
          {/* 问候语 */}
          <motion.p
            className="login-greeting"
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.3, duration: 0.5 }}
          >
            {greeting}
          </motion.p>

          {/* 标题 */}
          <motion.h1
            initial={{ opacity: 0, scale: 0.8 }}
            animate={{ opacity: 1, scale: 1 }}
            transition={{ delay: 0.2, duration: 0.5, type: 'spring' }}
          >
            API Agent
          </motion.h1>
          
          <motion.p 
            className="login-subtitle"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            transition={{ delay: 0.4, duration: 0.5 }}
          >
            智能文档检索助手
          </motion.p>

          <form onSubmit={onSubmit}>
            <motion.div 
              className="form-group"
              initial={{ opacity: 0, x: -20 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: 0.5, duration: 0.4 }}
            >
              <label>用户名</label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="请输入用户名"
                required
              />
            </motion.div>

            <motion.div 
              className="form-group"
              initial={{ opacity: 0, x: -20 }}
              animate={{ opacity: 1, x: 0 }}
              transition={{ delay: 0.6, duration: 0.4 }}
            >
              <label>密码</label>
              <div className="password-input-wrapper">
                <input
                  type={showPassword ? 'text' : 'password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="请输入密码"
                  required
                />
                <button
                  type="button"
                  className="password-toggle-btn"
                  onClick={togglePasswordVisibility}
                  title={showPassword ? '隐藏密码' : '显示密码'}
                >
                  {showPassword ? '🙈' : '👁️'}
                </button>
              </div>
            </motion.div>

            <AnimatePresence>
              {loginError && (
                <motion.div 
                  className="login-error"
                  initial={{ opacity: 0, y: -10, height: 0 }}
                  animate={{ opacity: 1, y: 0, height: 'auto' }}
                  exit={{ opacity: 0, y: -10, height: 0 }}
                  transition={{ duration: 0.3 }}
                >
                  {loginError}
                </motion.div>
              )}
            </AnimatePresence>

            <motion.button
              type="submit"
              className="btn-primary login-btn"
              disabled={isLoading}
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.7, duration: 0.4 }}
              whileHover={{ scale: 1.02 }}
              whileTap={{ scale: 0.98 }}
            >
              {isLoading ? (
                <motion.span
                  animate={{ rotate: 360 }}
                  transition={{ duration: 1, repeat: Infinity, ease: 'linear' }}
                  style={{ display: 'inline-block' }}
                >
                  ⏳
                </motion.span>
              ) : (
                '登录'
              )}
            </motion.button>
          </form>

          {/* 底部提示 */}
          <motion.p
            className="login-hint"
            initial={{ opacity: 0 }}
            animate={{ opacity: 0.7 }}
            transition={{ delay: 1, duration: 0.5 }}
          >
            测试用户: admin/admin123
          </motion.p>
        </motion.div>
      </div>
    </div>
  );
};

export default LoginForm;
