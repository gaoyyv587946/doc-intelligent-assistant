package com.example.apiagent.service;

import com.example.apiagent.model.User;
import com.example.apiagent.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户管理服务
 * - CRUD + BCrypt密码加密
 * - 在线状态追踪
 * - 强制下线（token黑名单）
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /** 在线用户追踪: username -> lastActiveTime */
    private final Map<String, LocalDateTime> onlineUsers = new ConcurrentHashMap<>();

    /** 被强制下线的用户名集合（下次请求时踢出） */
    private final Set<String> forceOfflineUsers = ConcurrentHashMap.newKeySet();

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    // ==================== 初始化默认用户 ====================

    public void initDefaultAdmin() throws SQLException {
        if (userRepository.count() == 0) {
            userRepository.create("admin", passwordEncoder.encode("admin123"), true);
            log.info("已创建默认管理员账号: admin / admin123");
        }
    }

    // ==================== 用户 CRUD ====================

    public User.UserInfo createUser(String username, String rawPassword, boolean admin) throws SQLException {
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在: " + username);
        }
        User user = userRepository.create(username, passwordEncoder.encode(rawPassword), admin);
        log.info("创建用户: {} (admin={})", username, admin);
        return user.toInfo();
    }

    public List<User.UserInfo> listUsers() throws SQLException {
        return userRepository.findAll().stream()
                .map(User::toInfo)
                .toList();
    }

    public Optional<User.UserInfo> getUser(long id) throws SQLException {
        return userRepository.findById(id).map(User::toInfo);
    }

    public Optional<User.UserInfo> getUserByUsername(String username) throws SQLException {
        return userRepository.findByUsername(username).map(User::toInfo);
    }

    public User.UserInfo updatePassword(long id, String rawPassword) throws SQLException {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        userRepository.updatePassword(id, passwordEncoder.encode(rawPassword));
        // 强制该用户重新登录
        forceOfflineUsers.add(user.username());
        log.info("修改用户密码: {}", user.username());
        return userRepository.findById(id).map(User::toInfo).orElseThrow();
    }

    public User.UserInfo updateAdmin(long id, boolean admin) throws SQLException {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        userRepository.updateAdmin(id, admin);
        // 权限变更，强制重新登录
        forceOfflineUsers.add(user.username());
        log.info("修改用户权限: {} -> admin={}", user.username(), admin);
        return userRepository.findById(id).map(User::toInfo).orElseThrow();
    }

    public boolean deleteUser(long id) throws SQLException {
        Optional<User> user = userRepository.findById(id);
        if (user.isPresent()) {
            forceOfflineUsers.add(user.get().username());
            onlineUsers.remove(user.get().username());
            log.info("删除用户: {}", user.get().username());
            return userRepository.deleteById(id);
        }
        return false;
    }

    // ==================== 登录验证 ====================

    /**
     * 验证用户名和密码，返回 User（含密码）
     * 用于 AuthController 登录
     */
    public Optional<User> authenticate(String username, String rawPassword) throws SQLException {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) return Optional.empty();
        User user = userOpt.get();
        // 验证：BCrypt(rawPassword) == stored_hash
        if (!passwordEncoder.matches(rawPassword, user.password())) {
            return Optional.empty();
        }
        // 标记在线
        onlineUsers.put(username, LocalDateTime.now());
        // 清除强制下线标记
        forceOfflineUsers.remove(username);
        return Optional.of(user);
    }

    // ==================== 在线状态 ====================

    /** 刷新用户活跃时间 */
    public void refreshOnline(String username) {
        onlineUsers.put(username, LocalDateTime.now());
    }

    /** 检查用户是否被强制下线 */
    public boolean isForcedOffline(String username) {
        return forceOfflineUsers.contains(username);
    }

    /** 获取在线用户列表（5分钟内有活动视为在线） */
    public Map<String, Boolean> getOnlineStatus() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);
        Map<String, Boolean> status = new LinkedHashMap<>();
        onlineUsers.forEach((username, lastActive) ->
                status.put(username, lastActive.isAfter(threshold)));
        return status;
    }

    /** 判断用户是否在线（5分钟内活跃） */
    public boolean isOnline(String username) {
        LocalDateTime lastActive = onlineUsers.get(username);
        if (lastActive == null) return false;
        return lastActive.isAfter(LocalDateTime.now().minusMinutes(5));
    }

    /** 强制用户下线 */
    public void forceOffline(String username) {
        forceOfflineUsers.add(username);
        onlineUsers.remove(username);
        log.info("强制用户下线: {}", username);
    }

    /** 清除强制下线标记 */
    public void clearForceOffline(String username) {
        forceOfflineUsers.remove(username);
    }
}
