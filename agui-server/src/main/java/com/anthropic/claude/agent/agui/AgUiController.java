package com.anthropic.claude.agent.agui;

import com.anthropic.claude.agent.ClaudeAgent;
import com.anthropic.claude.agent.Options;
import com.anthropic.claude.agent.Query;
import com.anthropic.claude.agent.agui.event.AgUiEvent;
import com.anthropic.claude.agent.agui.model.RunAgentInput;
import com.anthropic.claude.agent.permission.PermissionMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AG-UI 协议 endpoint：POST /agui → 启动 claude query → 翻译成 AG-UI 事件经 SSE 流回。 */
@RestController
@CrossOrigin                       // demo：允许前端 dev server 跨域；生产再收紧
public class AgUiController {

    private static final Logger log = LoggerFactory.getLogger(AgUiController.class);

    private final SessionStore sessionStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService timeoutPool =
            Executors.newSingleThreadScheduledExecutor();

    @Value("${agui.claude.model:sonnet}")
    private String model;
    @Value("${agui.claude.timeout-seconds:120}")
    private long timeoutSeconds;

    public AgUiController(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @PostMapping(path = "/agui", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@RequestBody RunAgentInput input) {
        String threadId = input.threadId() != null ? input.threadId() : "thread-" + System.nanoTime();
        String runId = input.runId() != null ? input.runId() : "run-" + System.nanoTime();
        String prompt = input.lastUserText();

        SseEmitter emitter = new SseEmitter(timeoutSeconds * 2 * 1000);
        // 终结事件（RunFinished/RunError）发出后立即 complete，不依赖 SDK 是否回调 onComplete
        Consumer<AgUiEvent> sink = e -> {
            send(emitter, e);
            if (e instanceof AgUiEvent.RunFinished || e instanceof AgUiEvent.RunError) {
                emitter.complete();
            }
        };

        if (prompt == null || prompt.isBlank()) {
            sink.accept(new AgUiEvent.RunError("no user message in input", "bad_request"));
            return emitter;
        }

        sink.accept(new AgUiEvent.RunStarted(threadId, runId));

        String resumeSid = sessionStore.get(threadId);
        Options.Builder ob = Options.builder()
                .includePartialMessages(true)
                .permissionMode(PermissionMode.BYPASS_PERMISSIONS)
                .model(model)
                .unsetEnv("CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_EXECPATH", "TMUX");
        if (resumeSid != null) {
            ob.resume(resumeSid);
        }
        Options options = ob.build();

        log.info("agui run thread={} run={} resume={} promptLen={}",
                threadId, runId, resumeSid != null, prompt.length());

        Query query;
        try {
            query = ClaudeAgent.query(prompt, options);
        } catch (RuntimeException ex) {
            log.error("failed to start claude query", ex);
            sink.accept(new AgUiEvent.RunError(String.valueOf(ex.getMessage()), "spawn_failed"));
            return emitter;
        }

        // 超时守护：到点强制 RunError + 关进程（complete 由 sink 触发）
        ScheduledFuture<?> timeoutTask = timeoutPool.schedule(() -> {
            log.warn("agui run timeout thread={} run={}", threadId, runId);
            sink.accept(new AgUiEvent.RunError("run timed out", "timeout"));
            query.close();
        }, timeoutSeconds, TimeUnit.SECONDS);

        emitter.onCompletion(() -> { timeoutTask.cancel(false); query.close(); });
        emitter.onError(t -> { timeoutTask.cancel(false); query.close(); });
        emitter.onTimeout(() -> { query.close(); emitter.complete(); });

        query.subscribe(new RunTranslator(sink, sessionStore, threadId, runId));
        return emitter;
    }

    private void send(SseEmitter emitter, AgUiEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(mapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.warn("sse send failed (client gone?) type={}", event.type());
        }
    }
}
