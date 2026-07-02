package com.example.apiagent.repository;

import com.example.apiagent.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite 用户数据访问层
 */
@Repository
public class UserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    private final String dbPath;
    private Connection connection;

    public UserRepository(@Value("${db.path:data/app.db}") String dbPath) {
        this.dbPath = dbPath;
    }

    @PostConstruct
    public void init() throws SQLException {
        File dbFile = new File(dbPath);
        dbFile.getParentFile().mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        connection.setAutoCommit(true);
        createTables();
        log.info("SQLite 数据库已初始化: {}", dbPath);
    }

    @PreDestroy
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    username TEXT NOT NULL UNIQUE,
                    password TEXT NOT NULL,
                    admin INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS token_blacklist (
                    token_jti TEXT PRIMARY KEY,
                    blacklisted_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL
                )
            """);
            // 定期清理过期黑名单token
            stmt.execute("""
                DELETE FROM token_blacklist WHERE expires_at < datetime('now')
            """);
        }
    }

    // ==================== User CRUD ====================

    public User create(String username, String hashedPassword, boolean admin) throws SQLException {
        String now = LocalDateTime.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (username, password, admin, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, hashedPassword);
            ps.setInt(3, admin ? 1 : 0);
            ps.setString(4, now);
            ps.setString(5, now);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return new User(rs.getLong(1), username, hashedPassword, admin,
                        LocalDateTime.parse(now), LocalDateTime.parse(now));
            }
        }
    }

    public Optional<User> findById(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM users WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapUser(rs)) : Optional.empty();
            }
        }
    }

    public Optional<User> findByUsername(String username) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM users WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapUser(rs)) : Optional.empty();
            }
        }
    }

    public List<User> findAll() throws SQLException {
        List<User> users = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY id ASC")) {
            while (rs.next()) {
                users.add(mapUser(rs));
            }
        }
        return users;
    }

    public boolean updatePassword(long id, String hashedPassword) throws SQLException {
        String now = LocalDateTime.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE users SET password = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, hashedPassword);
            ps.setString(2, now);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean updateAdmin(long id, boolean admin) throws SQLException {
        String now = LocalDateTime.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE users SET admin = ?, updated_at = ? WHERE id = ?")) {
            ps.setInt(1, admin ? 1 : 0);
            ps.setString(2, now);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean deleteById(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public int count() throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM users")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // ==================== Token Blacklist ====================

    public void blacklistToken(String jti, LocalDateTime expiresAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO token_blacklist (token_jti, blacklisted_at, expires_at) VALUES (?, ?, ?)")) {
            ps.setString(1, jti);
            ps.setString(2, LocalDateTime.now().toString());
            ps.setString(3, expiresAt.toString());
            ps.executeUpdate();
        }
    }

    public boolean isTokenBlacklisted(String jti) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM token_blacklist WHERE token_jti = ?")) {
            ps.setString(1, jti);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ==================== Helper ====================

    private User mapUser(ResultSet rs) throws SQLException {
        return new User(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password"),
                rs.getInt("admin") == 1,
                LocalDateTime.parse(rs.getString("created_at")),
                LocalDateTime.parse(rs.getString("updated_at"))
        );
    }
}
