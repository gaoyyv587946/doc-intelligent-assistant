# doc-intelligent-assistant
基于 LangChain4j + Spring Boot + React 构建的智能文档检索助手。支持文档上传、向量化检索、混合搜索（BM25 + 向量）、知识库管理、实时流式对话等功能。
存在部分问题，欢迎提出。

## 功能特性

- **智能对话** - 基于 LLM 的自然语言问答，支持工具调用（文档检索、API调用、页面打开）
- **混合检索** - BM25 关键词检索 + 向量语义检索，RRF 融合排序
- **文档管理** - 支持 Markdown/TXT/JSON/YAML/PDF 文档上传和解析
- **知识库笔记** - 关联文档的知识点管理
- **检索分析** - 可视化展示查询分析、权重计算、融合过程
- **权重调优** - BM25 参数调优实验，自动测试不同权重组合
- **用户管理** - JWT 认证，管理员/普通用户权限控制
- **实时反馈** - SSE 流式输出，后端处理进度实时反馈前端

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Spring Boot 3.3.5, LangChain4j 0.35.0 |
| 前端 | React 18, Vite 5, Framer Motion |
| 数据库 | SQLite |
| 向量存储 | 内存 + JSON 持久化 |
| 分词 | SmartCN / jieba |
| Embedding | all-minilm / bge-small-zh |
| 认证 | JWT (JSON Web Token) |

## 快速开始

### 环境要求

- JDK 17+
- Node.js 18+
- npm 或 pnpm

### 1. 配置后端

复制并编辑配置文件：

```bash
# 设置环境变量（或直接修改 application.yml）
export CHAT_API_KEY=sk-your-api-key
export CHAT_BASE_URL=https://api.deepseek.com/v1
export CHAT_MODEL=deepseek-v4-flash
```

`application.yml` 主要配置项：

```yaml
chat:
  model:
    api-key: ${CHAT_API_KEY:sk-your-api-key}
    base-url: ${CHAT_BASE_URL:https://api.deepseek.com/v1}
    model-name: ${CHAT_MODEL:deepseek-v4-flash}

embedding:
  model-type: bge-small-zh  # 可选: all-minilm, bge-small-zh

tokenizer:
  type: jieba  # 可选: smartcn, jieba
```

### 2. 启动后端

```bash
mvn spring-boot:run
```

后端默认运行在 `http://localhost:54189`

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端默认运行在 `http://localhost:5173`

### 4. 构建生产版本

```bash
cd frontend
npm run build
```

构建产物输出到 `frontend/dist/`，可由后端静态资源服务提供。

## 项目结构

```
txt-agent/
├── src/main/java/com/example/apiagent/
│   ├── agent/                  # AI Agent 定义
│   │   ├── ApiAgent.java       # Agent 接口
│   │   └── tools/              # 工具实现（文档检索、API调用等）
│   ├── config/                 # 配置类（Security、LangChain4j、Web）
│   ├── controller/             # REST API 控制器
│   ├── filter/                 # 过滤器（API白名单、敏感数据脱敏）
│   ├── model/                  # 数据模型
│   ├── parser/                 # 文档解析器（Markdown、TXT、JSON、PDF）
│   ├── repository/             # 数据访问层
│   ├── security/               # JWT 认证
│   ├── service/                # 业务服务层
│   └── store/                  # 向量存储持久化
├── src/main/resources/
│   ├── application.yml         # 应用配置
│   └── docs/                   # 示例文档
├── frontend/
│   ├── src/
│   │   ├── App.jsx             # 主应用（状态管理、路由）
│   │   ├── api.js              # API 请求封装
│   │   ├── style.css           # 全局样式
│   │   └── components/         # UI 组件
│   │       ├── LoginForm.jsx
│   │       ├── Header.jsx
│   │       ├── ChatView.jsx
│   │       ├── DocumentView.jsx
│   │       ├── NoteView.jsx
│   │       ├── SearchView.jsx
│   │       ├── WeightTuningView.jsx
│   │       └── UserView.jsx
│   └── package.json
└── pom.xml
```

## API 接口

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/auth/login` | POST | 用户登录 |
| `/api/chat/send` | POST | 普通聊天 |
| `/api/chat/stream` | GET | SSE 流式聊天 |
| `/api/documents/**` | GET/POST | 文档管理 |
| `/api/notes/**` | GET/POST/PUT/DELETE | 知识库笔记 |
| `/api/search/analyze` | POST | 检索分析 |
| `/api/users/**` | GET/POST/PUT/DELETE | 用户管理 |

## 默认账户

| 用户名 | 密码 | 角色 |
|--------|------|------|
| admin | admin123 | 管理员 |

首次启动时自动初始化管理员账户，建议登录后立即修改密码。

## 配置说明

### RAG 检索配置

```yaml
rag:
  chunk-size: 1500          # 文档分片最大字符数
  min-score: 0.6            # 最低相似度阈值
  max-results: 5            # 最大返回结果数
  rrf:
    k: 60                   # RRF 平滑参数
  weight:
    exact-keyword: 1.5      # 精确查询时 BM25 权重
    semantic-vector: 1.5    # 语义查询时向量权重
  boost:
    title-match: 1.5        # 标题匹配加分上限
    param-match: 1.3        # 参数名匹配加分上限
    api-path-match: 1.4     # API路径匹配加分上限
```

### API 白名单

```yaml
api:
  whitelist:
    enabled: true
    prefixes:
      - "https://api.company.com/"
      - "http://localhost:"
```
### 效果展示
#### 登录界面
<img width="1920" height="879" alt="image" src="https://github.com/user-attachments/assets/5dcffbc3-e062-42eb-ad03-02faf26cae67" />
默认admin/admin123

#### chat
<img width="1920" height="879" alt="image" src="https://github.com/user-attachments/assets/692c4cb0-1660-41b8-aebb-c0dc41746532" />

#### 分析
<img width="1920" height="879" alt="image" src="https://github.com/user-attachments/assets/371ac267-f912-4b5f-b562-9c0d786fb1d0" />

#### 更多等你发现
## License

MIT
