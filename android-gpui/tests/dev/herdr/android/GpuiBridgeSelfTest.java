package dev.herdr.android;

import dev.herdr.android.core.HostProfile;
import dev.herdr.android.core.QuickCommand;
import dev.herdr.android.core.Shortcut;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host-side boundary tests. These do not simulate Android lifecycle, IME, Vulkan, or JNI. */
public final class GpuiBridgeSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        GpuiTerminalProtocol.Command text = GpuiTerminalProtocol.command("{\"type\":\"text\",\"text\":\"echo hi\"}");
        check("echo hi".equals(text.text) && text.keys.isEmpty(), "text never appends Enter");
        check("\n".equals(GpuiTerminalProtocol.command("{\"type\":\"text\",\"text\":\"\\n\"}").text), "explicit text preserves newline");
        check("ctrl+c".equals(GpuiTerminalProtocol.command("{\"type\":\"keys\",\"keys\":[\"CTRL+C\"]}").keys.get(0)), "core normalizes supported keys");
        check("close".equals(GpuiTerminalProtocol.command("{\"type\":\"close\"}").type), "close is local");
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"prompt\",\"text\":\"run\"}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"text\",\"text\":1}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"text\",\"text\":\"x\",\"submit\":true}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"text\",\"text\":\"\"}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"keys\",\"keys\":[\"meta+c\"]}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"keys\",\"keys\":[\"ctrl+shift+c\"]}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"keys\",\"keys\":[1]}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"keys\",\"keys\":[]}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"close\",\"text\":\"x\"}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"close\",\"type\":\"close\"}"));
        reject(() -> GpuiTerminalProtocol.command("{type:'close'}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"close\",}"));
        reject(() -> GpuiTerminalProtocol.command("{\"type\":\"close\"} true"));
        reject(() -> GpuiTerminalProtocol.command("[]"));
        reject(() -> GpuiTerminalProtocol.command(new JSONObject().put("type", "text").put("text", repeat("é", 32769)).toString()));
        reject(() -> GpuiTerminalProtocol.command(new JSONObject().put("type", "text").put("text", "\uD800").toString()));
        JSONArray manyKeys = new JSONArray();
        for (int i = 0; i < 17; i++) manyKeys.put("esc");
        reject(() -> GpuiTerminalProtocol.command(new JSONObject().put("type", "keys").put("keys", manyKeys).toString()));

        quickCommands();

        GpuiTerminalProtocol.requireAck("{\"ok\":true}");
        checks++;
        reject(() -> GpuiTerminalProtocol.requireAck("{\"ok\":false}"));
        reject(() -> GpuiTerminalProtocol.requireAck("{\"ok\":\"true\"}"));
        reject(() -> GpuiTerminalProtocol.requireAck("{}"));
        reject(() -> GpuiTerminalProtocol.requireAck("{\"ok\":true,\"ok\":true}"));
        reject(() -> GpuiTerminalProtocol.requireAck("{\"ok\":true}{}"));
        check("hello\nworld".equals(GpuiTerminalProtocol.screen("{\"text\":\"hello\\nworld\",\"future\":{\"version\":2}}")), "screen text parses with bounded extension fields");
        check("".equals(GpuiTerminalProtocol.screen("{\"text\":\"\"}")), "empty screen is valid");
        reject(() -> GpuiTerminalProtocol.screen("{\"text\":false}"));
        reject(() -> GpuiTerminalProtocol.screen("{\"text\":\"a\",\"text\":\"b\"}"));
        reject(() -> GpuiTerminalProtocol.screen("{\"text\":\"x\",\"future\":" + repeat("[", 17) + "0" + repeat("]", 17) + "}"));
        reject(() -> GpuiTerminalProtocol.screen(new JSONObject().put("text", repeat("é", 131073)).toString()));
        reject(() -> GpuiTerminalProtocol.screen("{\"text\":\"\\ud800\"}"));
        JSONArray shortcuts = new JSONArray(GpuiTerminalProtocol.shortcuts("Stop = key:ctrl+c\nStatus = text:git status"));
        check(shortcuts.length() == 2 && shortcuts.getJSONObject(1).getString("text").equals("git status"), "macros preserve explicit actions");
        check("git status".equals(GpuiTerminalProtocol.command(GpuiTerminalProtocol.shortcutCommand(
                Shortcut.text("Status", "git status"))).text), "native macro serializes text without Enter");
        check(GpuiTerminalProtocol.command(GpuiTerminalProtocol.shortcutCommand(
                Shortcut.keys("Next", "down,enter"))).keys.size() == 2, "native shortcut preserves explicit ordered key batch");
        check(!GpuiTerminalProtocol.title("a\nb").contains("\n"), "title is single line");
        check(GpuiTerminalProtocol.title(repeat("😀", 200)).length() == 256, "title keeps complete Unicode at cap");
        check("é".equals(GpuiTerminalProtocol.physicalText(0xe9)), "hardware layout Unicode is preserved");
        check("😀".equals(GpuiTerminalProtocol.physicalText(0x1f600)), "supplementary hardware characters are complete");
        reject(() -> GpuiTerminalProtocol.physicalText(0x80000060));
        reject(() -> GpuiTerminalProtocol.physicalText(0xd800));
        reject(() -> GpuiTerminalProtocol.physicalText(0));
        reject(() -> GpuiTerminalProtocol.physicalText(0x110000));
        GpuiShortcutTouch tap = new GpuiShortcutTouch();
        check(!tap.event(0, true, true) && tap.isPressed(), "macro waits for release");
        check(tap.event(1, true, true) && !tap.isPressed(), "macro commits synchronously on valid release");
        check(!tap.event(1, true, true), "repeated release cannot duplicate a macro");
        tap.event(0, true, true);
        tap.event(2, false, true);
        check(!tap.event(1, true, true), "drag outside cancels macro");
        tap.event(0, true, true);
        tap.event(3, true, true);
        check(!tap.event(1, true, true), "scroll interception cancels macro");
        tap.event(0, true, true);
        tap.event(5, true, false);
        check(!tap.event(1, true, true), "multitouch cannot trigger macro");

        SavedHost host = new SavedHost("test", new HostProfile("Local test", "https://invalid.example", "fixture-not-real"), "Stop = key:ctrl+c");
        GpuiLaunch.Session pending = GpuiLaunch.reserve(host, "pane:1", "Test", 0);
        reject(() -> GpuiLaunch.reserve(host, "pane:2", "Other", 0));
        check(GpuiLaunch.consume(1) == pending, "launch grant is consumed once");
        check(GpuiLaunch.consume(2) == null, "consumed grant is unavailable");
        reject(() -> GpuiLaunch.reserve(host, "pane:2", "Other", 3));
        GpuiLaunch.release(pending);
        check(GpuiLaunch.consume(4) == null, "released grant cannot be restored");
        GpuiLaunch.reserve(host, "pane:1", "Test", 0);
        check(GpuiLaunch.consume(TimeUnit.SECONDS.toNanos(30)) == null, "unconsumed grants expire");
        GpuiLaunch.Session next = GpuiLaunch.reserve(host, "pane:2", "Next", TimeUnit.SECONDS.toNanos(31));
        GpuiLaunch.release(pending);
        check(GpuiLaunch.consume(TimeUnit.SECONDS.toNanos(32)) == next, "old release cannot clear new grant");
        GpuiLaunch.release(next);
        reject(() -> GpuiLaunch.reserve(host, "../other", "Test", 0));
        System.out.println("GPUI Java bridge: " + checks + " checks passed");
    }

    private static void quickCommands() throws Exception {
        for (String value : new String[] {
                "x", " git status ", "echo \"😀\" \\",
                repeat("a", QuickCommand.MAX_TEXT_BYTES),
                repeat("é", QuickCommand.MAX_TEXT_BYTES / 2),
                repeat("😀", QuickCommand.MAX_TEXT_BYTES / 4) }) {
            for (boolean submit : new boolean[] { false, true }) {
                String json = GpuiTerminalProtocol.quickCommand(value, submit);
                GpuiTerminalProtocol.Command command = GpuiTerminalProtocol.command(json);
                check((submit ? "submit" : "text").equals(command.type)
                                && value.equals(command.text) && command.keys.isEmpty(),
                        "quick command keeps exact text and explicit submission intent");
                check(new JSONObject(json).length() == 2, "quick command has no hidden keys or fields");
            }
        }
        for (String invalid : new String[] { null, "",
                repeat("a", QuickCommand.MAX_TEXT_BYTES + 1),
                repeat("é", QuickCommand.MAX_TEXT_BYTES / 2 + 1),
                repeat("😀", QuickCommand.MAX_TEXT_BYTES / 4 + 1),
                "\uD800", "\uDC00", "\uD800x", "\uDC00\uD800" }) {
            reject(() -> GpuiTerminalProtocol.quickCommand(invalid, false));
            reject(() -> GpuiTerminalProtocol.quickCommand(invalid, true));
            reject(() -> GpuiTerminalProtocol.command(new JSONObject()
                    .put("type", "submit").put("text", invalid).toString()));
        }
        for (int codepoint = 0; codepoint <= 0x9f; codepoint++) {
            if (codepoint >= 0x20 && codepoint < 0x7f) continue;
            rejectSingleLine("before" + (char) codepoint + "after");
        }
        rejectSingleLine("before\u2028after");
        rejectSingleLine("before\u2029after");
        for (String invalid : new String[] {
                "{\"type\":\"submit\"}",
                "{\"type\":\"submit\",\"text\":null}",
                "{\"type\":\"submit\",\"text\":1}",
                "{\"type\":\"submit\",\"text\":\"x\",\"keys\":[]}",
                "{\"type\":\"submit\",\"text\":\"x\",\"keys\":[\"enter\"]}",
                "{\"type\":\"submit\",\"text\":\"x\",\"submit\":true}",
                "{\"type\":\"submit\",\"text\":\"x\",\"type\":\"text\"}",
                "{\"type\":\"submit\",\"text\":\"x\",\"text\":\"y\"}",
                "{\"type\":\"submit\",\"text\":\"x\"}{}",
                "{\"type\":\"submit\",\"text\":\"\\ud800\"}",
                "{\"type\":\"submit\",\"text\":\"\\udc00\"}" }) {
            reject(() -> GpuiTerminalProtocol.command(invalid));
        }
        check(repeat("a", QuickCommand.MAX_TEXT_BYTES + 1).equals(
                        GpuiTerminalProtocol.command(new JSONObject().put("type", "text")
                                .put("text", repeat("a", QuickCommand.MAX_TEXT_BYTES + 1)).toString()).text),
                "ordinary text retains its larger input bound");
    }

    private static void rejectSingleLine(String invalid) throws Exception {
        reject(() -> GpuiTerminalProtocol.quickCommand(invalid, false));
        reject(() -> GpuiTerminalProtocol.quickCommand(invalid, true));
        reject(() -> GpuiTerminalProtocol.command(new JSONObject()
                .put("type", "submit").put("text", invalid).toString()));
        check(invalid.equals(GpuiTerminalProtocol.command(new JSONObject()
                        .put("type", "text").put("text", invalid).toString()).text),
                "ordinary text preserves controls without implying submission");
    }

    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void reject(Action action) throws Exception {
        try { action.run(); }
        catch (java.io.IOException | IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Invalid data was accepted");
    }

    private interface Action { void run() throws Exception; }
}
