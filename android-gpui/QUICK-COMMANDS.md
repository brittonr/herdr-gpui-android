# Oh My Pi quick commands — 0.4.0

Open a GPUI terminal and tap **Commands**, the lightning-bolt icon (**Cmds** in
earlier builds). Pinned commands appear first, marked with a star after the
name. Tap one to preview and edit it; opening the palette, pinning, saving,
deleting and canceling do not send terminal input.

The initial pinned entries are Context (`/context`), Usage (`/usage`), Model
(`/switch`), Effort (`/effort`) and Tasks (`/todo`). Jobs, Session info, Compact
context, Fresh state and Retry turn are also included. **OMP presets** lets you
browse these reference entries again after changing your saved list.

Presets were checked against Oh My Pi **18.8.3**, commit
`3e3c488a58d294e3a10051da588628e2cfb9d35c`. Your installed version is not detected;
all entries remain editable. Source definitions:

- [Session, tasks, jobs, usage and context](https://github.com/can1357/oh-my-pi/blob/3e3c488a58d294e3a10051da588628e2cfb9d35c/packages/coding-agent/src/slash-commands/builtin-session.ts)
- [Model switching and effort](https://github.com/can1357/oh-my-pi/blob/3e3c488a58d294e3a10051da588628e2cfb9d35c/packages/coding-agent/src/slash-commands/builtin-modes.ts)
- [Fresh state, compaction and retry](https://github.com/can1357/oh-my-pi/blob/3e3c488a58d294e3a10051da588628e2cfb9d35c/packages/coding-agent/src/slash-commands/builtin-lifecycle.ts)

Optional argument brackets are not inserted. For example, `/switch` and `/effort`
open their TUI choices; edit the preview to add a specific model or effort value.
`/model` is a model-management hub in this version; it is not used as a model-ID
shortcut. Destructive reset/delete commands are not provided as presets.

## Insert, send and save

- **Insert** sends the displayed text, without Enter. You can inspect or adjust
  the terminal input before submitting with your physical keyboard
- **Send + Enter** requires checking that OMP is idle and its prompt is empty
  first. Editing the text clears that check. The app cannot inspect OMP's input
  buffer and never clears existing input automatically
- **Save command** saves the name, text and pin for this host. Editing a preview
  does not overwrite a saved entry until you press Save
- **Add** creates an entry. **Delete** removes it after confirmation. An explicitly
  emptied list stays empty; it is not silently repopulated on restart

Use commands in OMP's input prompt. Single-line validation rejects line breaks,
tabs and control characters; entries are limited to 4096 UTF-8 bytes and 24
unique names per host. These are separate from the existing extra-key macros.
The palette remains safe to edit while the terminal is disconnected, but sending
requires the same live terminal that was selected when the palette opened.

`/compact` changes/summarizes context and may invoke the provider. `/fresh` resets
provider stream state while keeping the local transcript and requires an idle
session. `/retry` can resume a failed model/tool turn and requires an idle session.
The preview recomputes these notices from the edited command text.

## Input and storage guarantees

The transport remains the authenticated Herdr companion terminal API. No OMP RPC
client, process detection, version discovery or automatic command execution is
added. A submit is one generation-fenced FIFO item: text is acknowledged first,
then the same live connection is checked before sending Enter. An invalid or
uncertain text acknowledgment prevents Enter; no failed action is replayed.
A transport interruption can still leave text or a submitted action at the host,
so inspect the actual terminal before retrying.

An old preview cannot send to a resumed/replaced connection. Modal drafts isolate
physical and old GPUI IME input; physical Enter in an editor never implicitly
submits. Opening Commands during an unfinished terminal IME composition is
blocked until that composition is finished or canceled.

Commands are stored with the host in the existing AES-GCM/AndroidKeyStore profile
file, excluded from backup. Read-modify-write updates preserve other hosts and
profile edits, and reject saving against a deleted or retargeted host. Drafts,
tokens and command text are not logged or saved in framework view state.

## Upgrade

Version `0.4.0-gpui-compact`, code 4, keeps application ID
`dev.herdr.android.gpui.compact` and the 0.3.0 Compact signing certificate. Install
it as an update to retain saved hosts. Existing profiles gain the reference
commands when read; later edits and pin choices persist per host. No companion
server update is required if you already use the bridge supplied with Compact.

The earlier `dev.herdr.android.gpui` preview remains a separate app. The source
still includes the companion and its [trusted HTTPS setup](../android/README.md#host-prerequisites).
Runtime/build results are in `QUICK-COMMANDS-VALIDATION.md`.
