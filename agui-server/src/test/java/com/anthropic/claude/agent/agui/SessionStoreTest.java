package com.anthropic.claude.agent.agui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SessionStoreTest {

    @Test
    public void firstTurnHasNoSession() {
        SessionStore store = new SessionStore();
        assertNull(store.get("thread-A"));
    }

    @Test
    public void putThenGetReturnsSessionId() {
        SessionStore store = new SessionStore();
        store.put("thread-A", "sess-123");
        assertEquals("sess-123", store.get("thread-A"));
    }

    @Test
    public void perThreadIsolation() {
        SessionStore store = new SessionStore();
        store.put("thread-A", "sess-A");
        store.put("thread-B", "sess-B");
        assertEquals("sess-A", store.get("thread-A"));
        assertEquals("sess-B", store.get("thread-B"));
    }
}
