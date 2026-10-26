package app.opencodepair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Parses the real message shapes returned by opencode 2.x, not a simplified mock. */
public class ChatMessageTest {

    @Test public void readsUserMessage() {
        ChatMessage m = ChatMessage.from(json("{"
                + "\"id\":\"msg_user\","
                + "\"type\":\"user\","
                + "\"text\":\"hello world\","
                + "\"time\":{\"created\":1792657369415}}"));
        assertEquals(ChatMessage.TYPE_USER, m.kind);
        assertEquals("hello world", m.text);
        assertEquals(1792657369415L, m.created);
        assertFalse(m.isBusy());
        assertTrue(m.parts.isEmpty());
    }

    @Test public void readsAssistantPartsAndTools() {
        ChatMessage m = ChatMessage.from(json("{"
                + "\"id\":\"msg_assistant\","
                + "\"type\":\"assistant\","
                + "\"agent\":\"build\","
                + "\"model\":{\"id\":\"deepseek-v4.1-flash-free\",\"providerID\":\"opencode\",\"variant\":\"max\"},"
                + "\"time\":{\"created\":1000,\"completed\":2000},"
                + "\"content\":["
                + "{\"type\":\"reasoning\",\"text\":\"thinking\"},"
                + "{\"type\":\"text\",\"text\":\"Created hello.ts.\"},"
                + "{\"type\":\"tool\",\"id\":\"tool_1\",\"name\":\"read\","
                + "\"state\":{\"status\":\"completed\",\"input\":{\"path\":\"hello.ts\"},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"console.log('hi')\"}]},"
                + "\"time\":{\"created\":1100,\"completed\":1200}},"
                + "{\"type\":\"tool\",\"id\":\"tool_2\",\"name\":\"bash\","
                + "\"state\":{\"status\":\"streaming\",\"input\":\"{\\\"command\\\":\\\"ls\\\"}\"},"
                + "\"time\":{\"created\":1300}}"
                + "]}"));

        assertEquals(ChatMessage.TYPE_ASSISTANT, m.kind);
        assertEquals("deepseek-v4.1-flash-free", m.modelId);
        assertEquals("opencode", m.providerId);
        assertEquals("max", m.variant);
        assertEquals("build", m.agent);
        assertEquals(2000, m.completed);
        assertEquals(4, m.parts.size());

        ChatMessage.Part reasoning = m.parts.get(0);
        assertEquals(ChatMessage.PART_REASONING, reasoning.kind);
        assertEquals("thinking", reasoning.text);

        ChatMessage.Part text = m.parts.get(1);
        assertEquals(ChatMessage.PART_TEXT, text.kind);
        assertEquals("Created hello.ts.", text.text);

        ChatMessage.Part read = m.parts.get(2);
        assertEquals(ChatMessage.PART_TOOL, read.kind);
        assertEquals("read", read.toolName);
        assertEquals("completed", read.toolStatus);
        assertFalse(read.toolRunning);
        assertTrue(read.toolInput.contains("\"path\""));
        assertEquals("console.log('hi')", read.toolOutput);

        // A streaming tool's state.input is a JSON string, not an object.
        ChatMessage.Part bash = ChatMessage.from(json("{"
                + "\"id\":\"msg_stream\",\"type\":\"assistant\",\"time\":{\"created\":1},"
                + "\"content\":[{\"type\":\"tool\",\"name\":\"bash\","
                + "\"state\":{\"status\":\"streaming\",\"input\":\"{\\\"command\\\":\\\"ls\\\"}\"}}]}"))
                .parts.get(0);
        assertEquals("bash", bash.toolName);
        assertTrue(bash.toolRunning);
        assertTrue(bash.toolInput.contains("ls"));
    }

    @Test public void readsProviderRetryState() {
        ChatMessage m = ChatMessage.from(json("{\"id\":\"msg_retry\",\"type\":\"assistant\","
                + "\"time\":{\"created\":1},\"agent\":\"build\","
                + "\"model\":{\"id\":\"flint-40b-free\",\"providerID\":\"opencode\"},"
                + "\"content\":[],"
                + "\"retry\":{\"attempt\":3,\"at\":1792661293562,"
                + "\"error\":{\"type\":\"provider.internal\","
                + "\"message\":\"Upstream request failed: Endpoint is unavailable.\","
                + "\"status\":503}}}"));
        assertEquals(ChatMessage.TYPE_ASSISTANT, m.kind);
        assertEquals(3, m.retryAttempt);
        assertEquals("Upstream request failed: Endpoint is unavailable.", m.retryError);
        assertTrue(m.isBusy());
        assertEquals("flint-40b-free", m.modelId);
    }

    @Test public void readsToolError() {        ChatMessage m = ChatMessage.from(json("{"
                + "\"id\":\"msg_error\",\"type\":\"assistant\",\"time\":{\"created\":1,\"completed\":2},"
                + "\"content\":[{\"type\":\"tool\",\"name\":\"bash\","
                + "\"state\":{\"status\":\"error\",\"input\":{\"command\":\"false\"},"
                + "\"error\":{\"type\":\"unknown\",\"message\":\"exit code 1\",\"status\":500}}}]}"));

        ChatMessage.Part tool = m.parts.get(0);
        assertEquals("error", tool.toolStatus);
        assertEquals("exit code 1", tool.toolError);
        assertFalse(tool.toolRunning);
    }

    @Test public void readsIdleCompactionAndShell() {
        ChatMessage idle = ChatMessage.from(json("{"
                + "\"id\":\"msg_idle\",\"type\":\"idle\",\"time\":{\"created\":1},"
                + "\"outcome\":\"failed\",\"error\":\"provider unavailable\"}"));
        assertEquals(ChatMessage.TYPE_IDLE, idle.kind);
        assertEquals("failed", idle.outcome);
        assertEquals("provider unavailable", idle.error);

        ChatMessage compaction = ChatMessage.from(json("{"
                + "\"id\":\"msg_compact\",\"type\":\"compaction\",\"time\":{\"created\":1},"
                + "\"summary\":\"The conversation was compacted.\",\"status\":\"completed\"}"));
        assertEquals(ChatMessage.TYPE_COMPACTION, compaction.kind);
        assertEquals("The conversation was compacted.", compaction.text);

        ChatMessage shell = ChatMessage.from(json("{"
                + "\"id\":\"msg_shell\",\"type\":\"shell\",\"time\":{\"created\":1},"
                + "\"command\":\"ls\",\"output\":{\"output\":\"a.txt\\nb.txt\"}}"));
        assertEquals(ChatMessage.TYPE_SHELL, shell.kind);
        assertTrue(shell.text.startsWith("ls"));
        assertTrue(shell.text.contains("a.txt"));
    }

    @Test public void signatureTracksContentNotOnlyLength() {
        ChatMessage a = ChatMessage.optimistic("m1", "aaaa");
        ChatMessage b = ChatMessage.optimistic("m1", "bbbb");
        a.created = b.created = 1;
        assertNotEquals(a.signature(), b.signature());

        ChatMessage assistant = ChatMessage.from(json("{"
                + "\"id\":\"m2\",\"type\":\"assistant\",\"time\":{\"created\":1},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"one\"},"
                + "{\"type\":\"tool\",\"name\":\"bash\",\"state\":{\"status\":\"running\","
                + "\"input\":{},\"content\":[{\"type\":\"text\",\"text\":\"OUT1\"}]}}]}"));
        String before = assistant.signature();
        assistant.parts.get(0).text = "two";
        assistant.parts.get(1).toolOutput = "OUT2";
        assertNotEquals(before, assistant.signature());

        ChatMessage streaming = ChatMessage.from(json("{"
                + "\"id\":\"m3\",\"type\":\"assistant\",\"time\":{\"created\":1},"
                + "\"content\":[{\"type\":\"tool\",\"name\":\"bash\","
                + "\"state\":{\"status\":\"running\",\"input\":{}}}]}"));
        assertTrue(streaming.isBusy());
        streaming.completed = 2;
        streaming.parts.get(0).toolRunning = false;
        assertFalse(streaming.isBusy());
    }

    private static JSONObject json(String raw) {
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            throw new AssertionError("Invalid test JSON: " + e.getMessage(), e);
        }
    }
}
