









# 示例API文档 - 用户管理模块

## 1. 用户注册接口

- **接口路径**: POST /api/users/register
- **接口描述**: 新用户注册，创建用户账号
- **请求参数**:
  - username (string, 必填): 用户名，长度3-20位
  - password (string, 必填): 密码，长度8-32位，需包含字母和数字
  - email (string, 必填): 邮箱地址
  - phone (string, 可选): 手机号
- **请求示例**:
  ```json
  {
    "username": "zhangsan",
    "password": "test1234",
    "email": "zhangsan@example.com",
    "phone": "13800138000"
  }
  ```
- **返回示例**:
  ```json
  {
    "code": 200,
    "message": "注册成功",
    "data": {
      "userId": "U10001",
      "username": "zhangsan",
      "createdAt": "2024-01-15 10:30:00"
    }
  }
  ```
- **错误码**:
  - 4001: 用户名已存在
  - 4002: 邮箱已被注册
  - 4003: 密码格式不符合要求

## 2. 用户登录接口

- **接口路径**: POST /api/users/login
- **接口描述**: 用户登录，返回访问令牌
- **请求参数**:
  - username (string, 必填): 用户名
  - password (string, 必填): 密码
- **返回示例**:
  ```json
  {
    "code": 200,
    "message": "登录成功",
    "data": {
      "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
      "userId": "U10001",
      "username": "zhangsan",
      "expiresIn": 86400
    }
  }
  ```

## 3. 查询用户列表接口

- **接口路径**: GET /api/users
- **接口描述**: 分页查询用户列表
- **请求参数**:
  - page (int, 可选): 页码，默认1
  - size (int, 可选): 每页数量，默认10
  - keyword (string, 可选): 搜索关键词（用户名或邮箱）
  - status (int, 可选): 用户状态 0-禁用 1-正常
- **请求头**:
  - Authorization: Bearer {token}
- **返回示例**:
  ```json
  {
    "code": 200,
    "data": {
      "total": 100,
      "page": 1,
      "size": 10,
      "list": [
        {
          "userId": "U10001",
          "username": "zhangsan",
          "email": "zhangsan@example.com",
          "status": 1,
          "createdAt": "2024-01-15 10:30:00"
        }
      ]
    }
  }
  ```

## 4. 查询单个用户详情接口

- **接口路径**: GET /api/users/{userId}
- **接口描述**: 根据用户ID查询用户详情
- **路径参数**:
  - userId (string, 必填): 用户ID，如 U10001
- **请求头**:
  - Authorization: Bearer {token}
- **返回示例**:
  ```json
  {
    "code": 200,
    "data": {
      "userId": "U10001",
      "username": "zhangsan",
      "email": "zhangsan@example.com",
      "phone": "13800138000",
      "status": 1,
      "role": "user",
      "createdAt": "2024-01-15 10:30:00",
      "lastLoginAt": "2024-01-20 15:45:00"
    }
  }
  ```

## 5. 更新用户信息接口

- **接口路径**: PUT /api/users/{userId}
- **接口描述**: 更新用户的基本信息
- **路径参数**:
  - userId (string, 必填): 用户ID
- **请求参数**:
  - email (string, 可选): 新邮箱地址
  - phone (string, 可选): 新手机号
  - nickname (string, 可选): 昵称
- **请求头**:
  - Authorization: Bearer {token}
- **返回示例**:
  ```json
  {
    "code": 200,
    "message": "更新成功",
    "data": {
      "userId": "U10001",
      "username": "zhangsan",
      "email": "newemail@example.com"
    }
  }
  ```

## 6. 删除用户接口

- **接口路径**: DELETE /api/users/{userId}
- **接口描述**: 删除指定用户（软删除，标记为已删除状态）
- **路径参数**:
  - userId (string, 必填): 用户ID
- **请求头**:
  - Authorization: Bearer {token}
- **返回示例**:
  ```json
  {
    "code": 200,
    "message": "用户已删除"
  }
  ```

## 7. 用户状态枚举

| 状态值 | 说明 |
|--------|------|
| 0 | 禁用 |
| 1 | 正常 |
| 2 | 锁定（连续登录失败） |
| 3 | 已删除 |

## 8. 用户角色枚举

| 角色值 | 说明 |
|--------|------|
| admin | 管理员 |
| user | 普通用户 |
| guest | 访客 |
