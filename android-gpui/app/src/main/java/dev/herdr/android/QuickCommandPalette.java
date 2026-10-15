package dev.herdr.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.herdr.android.core.OmpQuickCommands;
import dev.herdr.android.core.QuickCommand;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** App-owned command drafts. Only an explicit preview action can request terminal input. */
final class QuickCommandPalette {
    interface Owner {
        void show(AlertDialog dialog);
        boolean isCurrentDialog(AlertDialog dialog);
    }
    interface Sender {
        boolean canSend();
        boolean send(AlertDialog dialog, String text, boolean submit);
    }

    private final Activity activity;
    private interface SaveCommands { List<QuickCommand> save(List<QuickCommand> commands) throws Exception; }
    private final String hostName;
    private final SaveCommands persistence;
    private final String targetTitle;
    private final Owner owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "herdr-command-settings");
        thread.setDaemon(true);
        return thread;
    });
    private List<QuickCommand> commands;
    private boolean saving;
    private boolean closed;

    QuickCommandPalette(Activity activity, SavedHost host, String targetTitle, Owner owner) {
        this.activity = activity;
        this.hostName = host.profile.getName();
        this.persistence = values -> new HostStore(activity).updateQuickCommands(host.id, host.profile, values).quickCommands;
        this.targetTitle = targetTitle;
        this.owner = owner;
        commands = host.quickCommands;
    }

    QuickCommandPalette(Activity activity, SavedRemoteHost host, String targetTitle, Owner owner) {
        this.activity = activity;
        this.hostName = host.name;
        this.persistence = values -> new RemoteHostStore(activity).updateQuickCommands(host, values).quickCommands;
        this.targetTitle = targetTitle;
        this.owner = owner;
        commands = host.quickCommands;
    }

    void open(Sender sender) {
        if (saving) {
            android.widget.Toast.makeText(activity, "Saving quick commands…", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        showList(sender, false);
    }

    void close() {
        closed = true;
        // A Save the user already accepted may finish atomically; no remote action follows it.
        writer.shutdown();
    }

    private void showList(Sender sender, boolean presets) {
        if (closed) return;
        List<QuickCommand> source = presets ? OmpQuickCommands.defaults() : commands;
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) indices.add(i);
        Collections.sort(indices, Comparator.comparing((Integer i) -> !source.get(i).isPinned()));
        String[] labels = new String[indices.size()];
        for (int i = 0; i < labels.length; i++) {
            QuickCommand command = source.get(indices.get(i));
            labels[i] = (command.isPinned() ? "★ " : "") + command.getLabel() + "\n" + summary(command.getText());
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(presets ? "OMP presets · " + OmpQuickCommands.REFERENCE_VERSION : "Quick commands")
                .setItems(labels, (dialog, selected) -> {
                    if (saving || !owner.isCurrentDialog((AlertDialog) dialog)) return;
                    int index = indices.get(selected);
                    preview(sender, presets ? -1 : index, source.get(index));
                }).setNegativeButton("Close", null);
        if (!presets) {
            builder.setPositiveButton("Add", (dialog, which) -> {
                if (!saving && owner.isCurrentDialog((AlertDialog) dialog)) preview(sender, -1, null);
            });
            builder.setNeutralButton("OMP presets", (dialog, which) -> {
                if (!saving && owner.isCurrentDialog((AlertDialog) dialog)) showList(sender, true);
            });
        }
        AlertDialog dialog = builder.create();
        owner.show(dialog);
        if (saving) {
            dialog.getListView().setEnabled(false);
            if (!presets) { dialog.getButton(-1).setEnabled(false); dialog.getButton(-3).setEnabled(false); }
        }
    }

    private void preview(Sender sender, int index, QuickCommand original) {
        if (closed || saving) return;
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(4), dp(16), dp(8));
        body.addView(label(hostName + " · " + targetTitle));
        body.addView(label("Use in OMP's input prompt. Insert adds text without Enter. Existing input is never erased."));
        EditText name = editor("Name", original == null ? "" : original.getLabel());
        EditText text = editor("Command", original == null ? "" : original.getText());
        body.addView(name); body.addView(text);
        CheckBox pinned = new CheckBox(activity);
        pinned.setText("Pin when saved"); pinned.setChecked(original != null && original.isPinned());
        pinned.setSaveEnabled(false); body.addView(pinned);
        TextView warning = label(OmpQuickCommands.warningFor(text.getText().toString()));
        warning.setTextColor(0xffe2a24e); body.addView(warning);
        CheckBox emptyPrompt = new CheckBox(activity);
        emptyPrompt.setText("OMP is idle and its input prompt is empty");
        emptyPrompt.setSaveEnabled(false); body.addView(emptyPrompt);
        TextView error = label(""); error.setTextColor(0xffee7777); body.addView(error);
        LinearLayout local = new LinearLayout(activity);
        Button save = new Button(activity); save.setText("Save command"); save.setAllCaps(false);
        local.addView(save, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button delete = new Button(activity); delete.setText("Delete"); delete.setAllCaps(false);
        if (index >= 0) local.addView(delete, new LinearLayout.LayoutParams(0, dp(48), 1));
        body.addView(local);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("Command preview")
                .setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Insert", null).setNeutralButton("Send + Enter", null).create();
        owner.show(dialog);
        final boolean[] used = {false};
        Button insert = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button submit = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        insert.setEnabled(sender.canSend()); submit.setEnabled(false);
        emptyPrompt.setOnCheckedChangeListener((view, checked) -> submit.setEnabled(checked && sender.canSend() && !used[0] && !saving));
        text.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                emptyPrompt.setChecked(false);
                warning.setText(OmpQuickCommands.warningFor(s.toString()));
            }
            public void afterTextChanged(Editable value) { }
        });
        View.OnClickListener send = view -> {
            if (used[0] || saving || !owner.isCurrentDialog(dialog)) return;
            boolean enter = view == submit;
            if (enter && !emptyPrompt.isChecked()) return;
            String value = text.getText().toString();
            try { QuickCommand.validateText(value); }
            catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); return; }
            used[0] = true;
            insert.setEnabled(false); submit.setEnabled(false);
            if (sender.send(dialog, value, enter)) dialog.dismiss();
            else error.setText("Terminal changed or input is unavailable. Close this preview and reopen Commands.");
        };
        insert.setOnClickListener(send); submit.setOnClickListener(send);
        save.setOnClickListener(view -> {
            if (used[0] || saving || !owner.isCurrentDialog(dialog)) return;
            try {
                QuickCommand value = new QuickCommand(name.getText().toString(), text.getText().toString(), pinned.isChecked());
                List<QuickCommand> next = new ArrayList<>(commands);
                if (index < 0) next.add(value); else next.set(index, value);
                persist(QuickCommand.validatedCopy(next), dialog, error, () -> showList(sender, false));
                insert.setEnabled(false); submit.setEnabled(false); save.setEnabled(false); delete.setEnabled(false);
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
        });
        delete.setOnClickListener(view -> {
            if (saving || !owner.isCurrentDialog(dialog)) return;
            AlertDialog confirm = new AlertDialog.Builder(activity).setTitle("Delete quick command?")
                    .setMessage("Remove this saved command from this host. Nothing is sent to the terminal.")
                    .setNegativeButton("Cancel", (d, w) -> {
                        if (!saving && owner.isCurrentDialog((AlertDialog) d)) preview(sender, index, original);
                    })
                    .setPositiveButton("Delete", null).create();
            owner.show(confirm);
            confirm.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (saving || !owner.isCurrentDialog(confirm)) return;
                List<QuickCommand> next = new ArrayList<>(commands); next.remove(index);
                persist(next, confirm, null, () -> showList(sender, false));
                confirm.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            });
        });
    }

    private void persist(List<QuickCommand> next, AlertDialog dialog, TextView error, Runnable success) {
        saving = true;
        if (error != null) error.setText("Saving…");
        writer.execute(() -> {
            List<QuickCommand> updated = null;
            try { updated = persistence.save(next); }
            catch (Exception failure) { /* No credential or command text is logged. */ }
            final List<QuickCommand> result = updated;
            main.post(() -> {
                saving = false;
                if (closed) return;
                if (result != null) commands = result;
                if (!owner.isCurrentDialog(dialog)) return;
                if (result != null) success.run();
                else if (error != null) error.setText("Could not save. The host may have changed; reopen it and try again.");
                else { dialog.dismiss(); android.widget.Toast.makeText(activity, "Could not save quick commands", android.widget.Toast.LENGTH_LONG).show(); }
            });
        });
    }

    private EditText editor(String hint, String value) {
        EditText edit = new EditText(activity);
        edit.setHint(hint); edit.setText(value); edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        edit.setSaveEnabled(false);
        edit.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        edit.setOnEditorActionListener((view, action, event) -> true);
        edit.setOnKeyListener((view, code, event) -> code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER);
        return edit;
    }
    private TextView label(String text) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(13);
        view.setPadding(0, dp(4), 0, dp(4)); view.setSaveEnabled(false); return view;
    }
    private String summary(String value) {
        int count = value.codePointCount(0, value.length());
        return count <= 80 ? value : value.substring(0, value.offsetByCodePoints(0, 80)) + "…";
    }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
