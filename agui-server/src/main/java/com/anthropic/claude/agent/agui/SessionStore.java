package com.anthropic.claude.agent.agui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** threadId → claude sessionId 的内存映射（demo 级；无持久化）。 */
@Component
public class SessionStore {

    private final Map<String, String> threadToSession = new ConcurrentHashMap<>();

    /** 已有会话的 claude sessionId；首轮返回 null。 */
    public String get(String threadId) {
        return threadId == null ? null : threadToSession.get(threadId);
    }

    public void put(String threadId, String sessionId) {
        if (threadId != null && sessionId != null) {
            threadToSession.put(threadId, sessionId);
        }
    }
}
