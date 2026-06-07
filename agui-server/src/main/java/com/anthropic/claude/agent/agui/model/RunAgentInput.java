package com.anthropic.claude.agent.agui.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** AG-UI 前端 POST 的运行输入；只取需要的字段，其余忽略（为后续 HITL/state 预留）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunAgentInput(String threadId, String runId, List<Message> messages) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String id, String role, String content) {}

    /** 最后一条 role=user 的 content；无则 null。 */
    public String lastUserText() {
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m != null && "user".equals(m.role())) {
                return m.content();
            }
        }
        return null;
    }
}
