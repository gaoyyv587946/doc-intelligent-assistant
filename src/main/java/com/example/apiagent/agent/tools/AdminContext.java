package com.example.apiagent.agent.tools;

/**
 * ThreadLocal 权限上下文
 * 在 ChatService 中设置，在 Tool 方法中读取
 * 解决 LangChain4j @Tool 方法无法直接获取 HTTP 请求 SecurityContext 的问题
 *
 * 使用方式：
 * - ChatService 在调用 agent.chat() 前调用 AdminContext.set(isAdmin)
 * - Tool 方法内调用 AdminContext.get() 判断权限
 * - ChatService 在 finally 块中调用 AdminContext.clear() 防止线程池泄漏
 */
public final class AdminContext {

    private static final ThreadLocal<Boolean> IS_ADMIN = ThreadLocal.withInitial(() -> false);

    private AdminContext() {}

    public static void set(boolean isAdmin) {
        IS_ADMIN.set(isAdmin);
    }

    public static boolean get() {
        return IS_ADMIN.get();
    }

    public static void clear() {
        IS_ADMIN.remove();
    }
}
