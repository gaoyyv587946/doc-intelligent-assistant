import React, { useRef, useEffect } from 'react';
import { motion, useMotionValue, useTransform, useSpring } from 'framer-motion';

// 纯几何长方体装饰组件 - 不含人物特征
const GeometricBlock = ({ mouseX, mouseY, index }) => {
  const configs = [
    { x: -140, width: 45, height: 120, gradient: 'linear-gradient(135deg, #6B9E7A, #4A7C59)', delay: 0 },
    { x: 0, width: 60, height: 160, gradient: 'linear-gradient(135deg, #5B7FA5, #4A6B8A)', delay: 0.15 },
    { x: 140, width: 40, height: 100, gradient: 'linear-gradient(135deg, #E87070, #C45A5A)', delay: 0.3 },
  ];
  
  const config = configs[index];
  
  // 跟随鼠标轻微倾斜
  const rotateZ = useTransform(mouseX, [-1, 1], [-5, 5]);
  const springRotate = useSpring(rotateZ, { stiffness: 100, damping: 15 });
  
  // 浮动动画偏移
  const floatY = useTransform(mouseY, [-1, 1], [-8, 8]);
  const springFloat = useSpring(floatY, { stiffness: 80, damping: 12 });

  return (
    <motion.div
      style={{
        position: 'absolute',
        bottom: '25%',
        left: `calc(50% + ${config.x}px)`,
        zIndex: 10,
      }}
      initial={{ y: 80, opacity: 0, scale: 0.8 }}
      animate={{ y: 0, opacity: 1, scale: 1 }}
      transition={{ delay: config.delay, type: 'spring', stiffness: 80, damping: 12 }}
    >
      {/* 主长方体 */}
      <motion.div
        style={{
          width: `${config.width}px`,
          height: `${config.height}px`,
          background: config.gradient,
          borderRadius: '4px',
          position: 'relative',
          boxShadow: '0 8px 24px rgba(0,0,0,0.15)',
          rotateZ: springRotate,
          y: springFloat,
        }}
      >
        {/* 装饰线条 */}
        <div style={{
          position: 'absolute',
          top: '20%',
          left: '15%',
          right: '15%',
          height: '2px',
          backgroundColor: 'rgba(255,255,255,0.25)',
          borderRadius: '1px',
        }} />
        <div style={{
          position: 'absolute',
          top: '40%',
          left: '15%',
          right: '15%',
          height: '2px',
          backgroundColor: 'rgba(255,255,255,0.2)',
          borderRadius: '1px',
        }} />
        <div style={{
          position: 'absolute',
          top: '60%',
          left: '15%',
          right: '15%',
          height: '2px',
          backgroundColor: 'rgba(255,255,255,0.15)',
          borderRadius: '1px',
        }} />
      </motion.div>
      
      {/* 底部阴影 */}
      <motion.div
        style={{
          width: `${config.width + 10}px`,
          height: '6px',
          backgroundColor: 'rgba(0,0,0,0.08)',
          borderRadius: '50%',
          filter: 'blur(3px)',
          margin: '4px auto 0',
          marginLeft: '-5px',
        }}
        animate={{
          scaleX: [1, 1.05, 1],
          opacity: [0.5, 0.3, 0.5],
        }}
        transition={{
          duration: 3,
          repeat: Infinity,
          delay: index * 0.5,
        }}
      />
    </motion.div>
  );
};

// 浮动粒子装饰
const FloatingParticle = ({ delay, x, y, size, color }) => (
  <motion.div
    style={{
      position: 'absolute',
      width: size,
      height: size,
      borderRadius: '50%',
      backgroundColor: color,
      left: x,
      top: y,
      pointerEvents: 'none',
      opacity: 0,
    }}
    animate={{
      y: [0, -30, -60],
      opacity: [0, 0.6, 0],
      scale: [0.5, 1, 0.5],
    }}
    transition={{
      duration: 4 + Math.random() * 3,
      repeat: Infinity,
      delay: delay,
      ease: 'easeInOut',
    }}
  />
);

// 主场景组件
const CharacterScene = ({ showPassword }) => {
  const containerRef = useRef(null);
  const mouseX = useSpring(useMotionValue(0), { stiffness: 150, damping: 15 });
  const mouseY = useSpring(useMotionValue(0), { stiffness: 150, damping: 15 });
  
  useEffect(() => {
    const handleMouseMove = (e) => {
      if (!containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      const centerX = rect.left + rect.width / 2;
      const centerY = rect.top + rect.height / 2;
      const nx = (e.clientX - centerX) / (window.innerWidth / 2);
      const ny = (e.clientY - centerY) / (window.innerHeight / 2);
      mouseX.set(Math.max(-1, Math.min(1, nx)));
      mouseY.set(Math.max(-1, Math.min(1, ny)));
    };
    window.addEventListener('mousemove', handleMouseMove);
    return () => window.removeEventListener('mousemove', handleMouseMove);
  }, [mouseX, mouseY]);

  // 粒子配置
  const particles = [
    { delay: 0, x: '15%', y: '60%', size: '6px', color: 'rgba(107, 158, 122, 0.4)' },
    { delay: 1.5, x: '30%', y: '70%', size: '4px', color: 'rgba(91, 127, 165, 0.3)' },
    { delay: 3, x: '50%', y: '55%', size: '8px', color: 'rgba(232, 112, 112, 0.3)' },
    { delay: 4.5, x: '70%', y: '65%', size: '5px', color: 'rgba(107, 158, 122, 0.35)' },
    { delay: 6, x: '85%', y: '60%', size: '7px', color: 'rgba(91, 127, 165, 0.25)' },
  ];

  return (
    <>
      {/* 背景粒子 */}
      <motion.div
        style={{
          position: 'fixed',
          top: 0,
          left: 0,
          width: '100%',
          height: '100%',
          pointerEvents: 'none',
          zIndex: 0,
          overflow: 'hidden',
        }}
        animate={{ opacity: showPassword ? 0.3 : 0.6 }}
        transition={{ duration: 0.5 }}
      >
        {particles.map((p, i) => (
          <FloatingParticle key={i} {...p} />
        ))}
      </motion.div>

      {/* 几何装饰 */}
      <div 
        ref={containerRef}
        style={{
          position: 'relative',
          width: '100%',
          height: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {[0, 1, 2].map(index => (
          <GeometricBlock 
            key={index}
            mouseX={mouseX} 
            mouseY={mouseY} 
            index={index}
          />
        ))}
      </div>
    </>
  );
};

export { CharacterScene, GeometricBlock };
export default CharacterScene;
