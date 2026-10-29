//! GPUI elements rendered by the mobile wgpu/Vulkan backend, with virtual rows.
use crate::model::{self, DisplayMetrics, ViewState};
use gpui::{prelude::*, *};
use std::{ops::Range, sync::Arc};

/// Live companion terminal view, embeddable in a GPUI window.
pub struct TerminalView {
    state: ViewState,
    focus: FocusHandle,
    scroll: UniformListScrollHandle,
    follow: bool,
    marked: String,
    input_bounds: Bounds<Pixels>,
    viewport: Size<Pixels>,
    error: Option<String>,
    _updates: Task<()>,
}

impl TerminalView {
    pub fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let focus = cx.focus_handle();
        window.focus(&focus, cx);
        let changes = model::snapshot_changes();
        let updates = cx.spawn(async move |this, cx| {
            while changes.recv().await.is_ok() {
                if this
                    .update(cx, |this, cx| {
                        if this.refresh_snapshot() {
                            cx.notify();
                        }
                    })
                    .is_err()
                {
                    break;
                }
            }
        });
        Self {
            state: model::mailbox().state(),
            focus,
            scroll: UniformListScrollHandle::new(),
            follow: true,
            marked: String::new(),
            input_bounds: Bounds::default(),
            viewport: Size::default(),
            error: None,
            _updates: updates,
        }
    }

    fn refresh_snapshot(&mut self) -> bool {
        let state = model::mailbox().state();
        if self.state.revision == state.revision {
            return false;
        }
        let new_generation = self.state.generation != state.generation;
        if new_generation || !state.active {
            self.marked.clear();
            self.error = None;
        }
        let output_changed = !Arc::ptr_eq(&self.state.snapshot, &state.snapshot)
            && self.state.snapshot.lines != state.snapshot.lines;
        // A scrolled window keeps its line count; only an append moves the tail.
        let appended = self.state.snapshot.lines.len() != state.snapshot.lines.len();
        let geometry_changed = self.state.display_preferences != state.display_preferences
            || self.state.preedit.is_empty() != state.preedit.is_empty()
            || self.state.snapshot.truncated != state.snapshot.truncated;
        if self.follow != state.follow {
            self.follow = state.follow;
        }
        if self.follow
            && (new_generation || (output_changed && appended) || geometry_changed)
            && !state.snapshot.lines.is_empty()
        {
            self.scroll
                .scroll_to_item(state.snapshot.lines.len() - 1, ScrollStrategy::Bottom);
        }
        if self.marked != *state.preedit {
            self.marked = state.preedit.to_string();
        }
        self.state = state;
        true
    }

    #[allow(dead_code)]
    fn close(&mut self, generation: i64, cx: &mut Context<Self>) {
        #[cfg(target_os = "android")]
        if crate::android::close_activity(generation) {
            return;
        }
        let _ = generation;
        self.error = Some("Use Android Back to return to your host".into());
        cx.notify();
    }

    #[allow(dead_code)]
    fn keyboard(&mut self, generation: i64, window: &mut Window, cx: &mut Context<Self>) {
        let state = model::mailbox().state();
        if !state.active || state.generation != generation {
            self.error = Some("Terminal input is inactive".into());
            cx.notify();
            return;
        }
        window.focus(&self.focus, cx);
        #[cfg(target_os = "android")]
        if crate::android::request_keyboard(generation) {
            gpui_mobile::show_keyboard();
        }
    }

    fn ime_buffer(&self) -> &str {
        // A single virtual preceding cell lets an empty Android EditText request
        // backspace without pretending the rendered output is editable text.
        if self.marked.is_empty() {
            " "
        } else {
            &self.marked
        }
    }
}

#[allow(dead_code)]
fn button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    metrics: DisplayMetrics,
) -> Stateful<Div> {
    div()
        .id(id)
        .flex_1()
        .min_w_0()
        .h(px(metrics.touch_height))
        .min_h(px(metrics.touch_height))
        .flex()
        .items_center()
        .justify_center()
        .px(px(3.))
        .rounded_md()
        .bg(rgb(model::palette().chrome))
        .text_color(rgb(model::palette().foreground))
        .text_size(px(metrics.font_size))
        .cursor_pointer()
        .child(div().min_w_0().truncate().child(label.into()))
}

/// One terminal row as a row of painted runs. Linked runs are clickable and
/// open through the bounded Android intent path.
fn styled_row_element(
    row: &model::StyledRow,
    links: &Arc<Vec<String>>,
    generation: i64,
    row_index: usize,
    metrics: DisplayMetrics,
) -> Div {
    let mut line = div().flex().flex_none().items_center();
    for (index, run) in row.runs.iter().enumerate() {
        let piece = &row.text[run.start..run.start + run.len];
        // Interactive spans need an element id; the run index keeps it stable.
        let mut span = div()
            .flex_none()
            .whitespace_nowrap()
            .id(ElementId::Name(format!("r{row_index}-{index}").into()));
        if let Some(url) = run.link.and_then(|link| links.get(usize::from(link)).cloned()) {
            span = span
                .cursor_pointer()
                .hover(|style| style.bg(rgb(model::palette().chrome)))
                .on_click(move |_, _, _| {
                    crate::android::open_link(generation, &url);
                });
        }
        line = line.child(span.child(styled_run(piece, run, metrics)));
    }
    line
}

/// One terminal run painted with the palette's colors and its attributes.
fn styled_run(text: &str, run: &model::StyledRun, metrics: DisplayMetrics) -> StyledText {
    let base = Font {
        family: crate::fonts::MONO_FAMILY.into(),
        features: Default::default(),
        fallbacks: None,
        weight: FontWeight::NORMAL,
        style: FontStyle::Normal,
    };
    let palette = model::palette();
    let mut font = base;
    if run.modifier & model::BOLD != 0 {
        font.weight = FontWeight::BOLD;
    }
    if run.modifier & model::ITALIC != 0 {
        font.style = FontStyle::Italic;
    }
    let (fg, bg) = model::styled_colors(run, &palette);
    let color = rgb(fg).into();
    let _ = metrics;
    StyledText::new(text.to_owned()).with_runs(vec![TextRun {
        len: text.len(),
        font,
        color,
        background_color: (bg != palette.background).then(|| rgb(bg).into()),
        underline: (run.modifier & model::UNDERLINE != 0).then(|| UnderlineStyle {
            thickness: px(1.),
            color: Some(color),
            wavy: false,
        }),
        strikethrough: (run.modifier & model::STRIKETHROUGH != 0).then(|| StrikethroughStyle {
            thickness: px(1.),
            color: Some(color),
        }),
    }])
}

impl Render for TerminalView {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let snapshot = self.state.snapshot.clone();
        let rows = snapshot.clone();
        let entity = cx.entity();
        let active = self.state.active;
        let generation = self.state.generation;
        let status = self
            .error
            .clone()
            .unwrap_or_else(|| snapshot.status.clone());
        let viewport = window.viewport_size();
        let metrics = DisplayMetrics::fit(
            self.state.display_preferences,
            viewport.width.into(),
            snapshot.widest_line_chars,
        );
        if viewport != self.viewport {
            self.viewport = viewport;
            if self.follow && !snapshot.lines.is_empty() {
                self.scroll
                    .scroll_to_item(snapshot.lines.len() - 1, ScrollStrategy::Bottom);
            }
        }
        let row_width = px(metrics.row_width(snapshot.widest_line_chars));
        // The daemon's pane is its own height, usually shorter than a phone
        // surface. Spreading its rows over the surface removes the dead band
        // without depending on row identity the daemon does not provide, and
        // without shrinking the number of readable columns.
        // Natural leading: a terminal's rhythm is information. Spare height goes
        // above the block, never into the line spacing.
        let fit = 1.0f32;
        let pan = self.state.pan;
        let row_height = px(metrics.row_height);
        let font_size = px(metrics.font_size);
        // A pane whose grid is shorter than the surface used to paint from the
        // top, leaving a dead band under the last row. Anchor those rows to the
        // bottom the way a terminal does.
        let surface_height = viewport.height.to_f64() - f64::from(metrics.header_height);
        let content_height = f64::from(metrics.row_height * fit) * snapshot.lines.len() as f64;
        let short_pane = content_height < surface_height - 1.;
        // An empty pane must say why it is empty: a bare surface reads as a crash.
        let empty_card = div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(metrics.gap * 2.))
            .px(px(metrics.padding * 4.))
            .child(
                div()
                    .text_size(px(metrics.font_size * 1.3))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child(if status.is_empty() {
                        SharedString::from("Connecting to the pane…")
                    } else {
                        SharedString::from(status.clone())
                    }),
            )
            .child(
                div()
                    .text_size(px(metrics.font_size))
                    .text_color(rgb(model::palette().muted))
                    .text_center()
                    .child(
                        "No terminal surface yet. Press Back to choose a host, \
                         or wait if the connection is still opening.",
                    ),
            );
        div()
            .size_full()
            .flex()
            .flex_col()
            .overflow_hidden()
            .bg(rgb(model::palette().background))
            .text_color(rgb(model::palette().foreground))
            .when(false, |root| root)
            .font_family(crate::fonts::SANS_FAMILY)
            .track_focus(&self.focus)
            .child(
                div()
                    .flex_none()
                    .min_w_0()
                    .h(px(metrics.header_height))
                    // Fixed dark frame: the Java rail and toolbar cannot follow a
                    // palette, so the header joins them instead of the page.
                    .bg(rgb(0x1e222a))
                    .text_color(rgb(0xdce5ef))
                    .flex()
                    .items_center()
                    .gap(px(metrics.gap))
                    .px(px(metrics.padding + 4.))
                    .border_b_1()
                    .border_color(rgb(model::palette().border))
                    // Chrome keeps its own scale: the pane title must stay legible
                    // on the cover, where the fitted terminal type drops to 11 px.
                    .text_size(px(metrics.font_size.clamp(13., 16.)))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .truncate()
                            .font_weight(FontWeight::SEMIBOLD)
                            .child(snapshot.title.clone()),
                    )
                    .child(
                        // The status is a short state word, not a second title: give
                        // the pane identity the rest of the row instead of half of it.
                        div()
                            .flex_none()
                            .max_w(px(metrics.font_size * 14.))
                            .min_w_0()
                            .truncate()
                            .text_right()
                            .text_color({
                                let palette = model::palette();
                                rgb(if self.error.is_some() {
                                    palette.ansi[1]
                                } else if active {
                                    palette.accent
                                } else {
                                    palette.ansi[3]
                                })
                            })
                            .child(status),
                    ),
            )
            .child({
                let mut body = div()
                    .flex_1()
                    .min_h_0()
                    .min_w_0()
                    .relative()
                    .overflow_hidden()
                    .flex()
                    .flex_col();
                // A horizontal drag pans the pane; vertical deltas stay with the
                // list so scrolling keeps working exactly as before.
                body = body.on_scroll_wheel(move |event, _, _| {
                    let (dx, dy) = match event.delta {
                        ScrollDelta::Pixels(point) => (f32::from(point.x), f32::from(point.y)),
                        ScrollDelta::Lines(point) => (point.x, point.y),
                    };
                    if let Some(delta) = model::pan_from_wheel(dx, dy) {
                        model::mailbox().pan(delta);
                    }
                });
                body
                    .when(snapshot.lines.is_empty(), |body| {
                        body.items_center().justify_center()
                    })
                    .when(pan > 0., |body| {
                        // A panned pane has no visible left edge; mark it so a cut
                        // line is never mistaken for a broken render.
                        body.child(
                            div()
                                .absolute()
                                .left_0()
                                .top_0()
                                .bottom_0()
                                .w(px(2.))
                                .bg(rgb(model::palette().accent)),
                        )
                    })
                    .when(!snapshot.lines.is_empty(), |body| {
                        // A pane shorter than the surface gets even margins above
                        // and below; one tall band over the text reads as a fault.
                        body.when(short_pane, |body| body.justify_center())
                    .child(
                        div()
                            .min_w_0()
                            .when(short_pane, |box_| {
                                box_.h(px(content_height.max(0.) as f32))
                            })
                            .when(!short_pane, |box_| box_.size_full())
                            .child(
                                div()
                                    .h_full()
                                    .w(row_width)
                                    .ml(px(-pan))
                                    .child(uniform_list(
                            "terminal-lines",
                            snapshot.lines.len(),
                            move |range, _, _| {
                                range
                                    .map(|index| {
                                        let line = div()
                                            .h(row_height)
                                            .w(row_width)
                                            // Match the header's inset so the terminal and
                                            // the pane title share one left edge.
                                            .px(px(metrics.padding + 4.))
                                            .font_family(crate::fonts::MONO_FAMILY)
                                            .text_size(font_size)
                                            .line_height(row_height)
                                            .whitespace_nowrap();
                                        // Painted cells win when their runs tile the
                                        // plain line; otherwise keep the plain text.
                                        match rows.styled.as_ref().and_then(|styled| styled.get(index)) {
                                            Some(styled_line) => line.child(styled_row_element(
                                                styled_line,
                                                &rows.links,
                                                generation,
                                                index,
                                                metrics,
                                            )),
                                            None => line.child(SharedString::from(rows.lines[index].clone())),
                                        }
                                    })
                                    .collect::<Vec<_>>()
                            },
                        )
                        .size_full()
                        .with_horizontal_sizing_behavior(
                            ListHorizontalSizingBehavior::Unconstrained,
                        )
                        .track_scroll(&self.scroll),
                            ),
                            ),
                    )
                    })
                    .when(snapshot.lines.is_empty(), |body| body.child(empty_card))
                    .child(
                        canvas(
                            |_, _, _| (),
                            move |bounds, _, window, cx| {
                                entity.update(cx, |this, cx| {
                                    let height = row_height.min(bounds.size.height);
                                    this.input_bounds = Bounds::new(
                                        point(bounds.left(), bounds.bottom() - height),
                                        size(px(2.).min(bounds.size.width), height),
                                    );
                                    window.handle_input(
                                        &this.focus,
                                        ElementInputHandler::new(this.input_bounds, cx.entity()),
                                        cx,
                                    );
                                });
                            },
                        )
                        .absolute()
                        .size_full(),
                    )
                    .into_any_element()
            })
            .when(snapshot.truncated, |root| {
                root.child(
                    div()
                        .flex_none()
                        .px(px(metrics.padding + 4.))
                        .py_1()
                        .text_size(px(metrics.font_size))
                        .truncate()
                        .text_color(rgb(model::palette().ansi[3]))
                        .child(if self.state.display_preferences.compact {
                            "Display limit reached"
                        } else {
                            "Display capped at 4,000 lines / 4,096 characters per line"
                        }),
                )
            })
            .when(!self.marked.is_empty(), |root| {
                root.child(
                    div()
                        .flex_none()
                        .px(px(metrics.padding + 4.))
                        .py_1()
                        .text_size(px(metrics.font_size))
                        .truncate()
                        .bg(rgb(model::palette().border))
                        .child(format!("Composing: {}", self.marked)),
                )
            })
    }
}

impl EntityInputHandler for TerminalView {
    fn text_for_range(
        &mut self,
        range: Range<usize>,
        adjusted: &mut Option<Range<usize>>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<String> {
        let text: Vec<u16> = self.ime_buffer().encode_utf16().collect();
        let start = range.start.min(text.len());
        let end = range.end.min(text.len()).max(start);
        *adjusted = Some(start..end);
        Some(String::from_utf16_lossy(&text[start..end]))
    }
    fn selected_text_range(
        &mut self,
        _: bool,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<UTF16Selection> {
        let end = self.ime_buffer().encode_utf16().count();
        Some(UTF16Selection {
            range: end..end,
            reversed: false,
        })
    }
    fn marked_text_range(&self, _: &mut Window, _: &mut Context<Self>) -> Option<Range<usize>> {
        (!self.marked.is_empty()).then(|| 0..self.marked.encode_utf16().count())
    }
    fn unmark_text(&mut self, _: &mut Window, cx: &mut Context<Self>) {
        if !self.marked.is_empty() {
            self.marked.clear();
            cx.notify();
        }
    }
    fn replace_text_in_range(
        &mut self,
        _: Option<Range<usize>>,
        _: &str,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        // Java's ordered nativeImeEvent bridge is the sole remote commit path.
        // The platform handler only presents local composition/configuration.
        if !self.marked.is_empty() {
            self.marked.clear();
            cx.notify();
        }
    }
    fn replace_and_mark_text_in_range(
        &mut self,
        _: Option<Range<usize>>,
        text: &str,
        _: Option<Range<usize>>,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let state = model::mailbox().state();
        if state.active
            && state.generation == self.state.generation
            && text.len() <= model::MAX_TEXT_BYTES
            && self.marked != text
        {
            self.marked = text.into();
            cx.notify();
        }
    }
    fn bounds_for_range(
        &mut self,
        _: Range<usize>,
        _: Bounds<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<Bounds<Pixels>> {
        Some(self.input_bounds)
    }
    fn character_index_for_point(
        &mut self,
        _: Point<Pixels>,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> Option<usize> {
        None
    }
    fn accepts_text_input(&self, _: &mut Window, _: &mut Context<Self>) -> bool {
        let state = model::mailbox().state();
        state.active && state.generation == self.state.generation
    }
    fn text_input_configuration(
        &mut self,
        _: &mut Window,
        _: &mut Context<Self>,
    ) -> TextInputConfiguration {
        TextInputConfiguration {
            input_action: TextInputAction::Enter,
            ..TextInputConfiguration::default()
        }
    }
}

#[cfg(test)]
mod tests {
    #![allow(clippy::unwrap_used)]
    use super::DisplayMetrics;
    use crate::model;
    use gpui::{TextRun, TextSystem, WindowTextSystem, font, px};
    use std::sync::Arc;

    #[test]
    fn bundled_control_labels_fit_at_all_supported_sizes_in_320_dp() {
        // Exercise actual bundled glyph advances, not just an estimated
        // character width. No host/OEM font fallback may mask clipping.
        let text_system = Arc::new(TextSystem::new(Arc::new(
            gpui_wgpu::CosmicTextSystem::new_without_system_fonts("Roboto"),
        )));
        crate::fonts::install(&text_system).unwrap();
        let window_text = WindowTextSystem::new(text_system);
        for compact in [false, true] {
            for font_size in [11., 12., 13., 18., 24., 36.] {
                let metrics = DisplayMetrics::new(
                    model::DisplayPreferences::new(font_size, compact).unwrap(),
                    320.,
                );
                let available = 320. - 2. * metrics.padding;
                for label in ["Keyboard", "Follow", "Paused", "Back"] {
                    let width = if metrics.stacked_controls {
                        if label == "Keyboard" {
                            available
                        } else {
                            (available - metrics.gap) / 2.
                        }
                    } else {
                        (available - 2. * metrics.gap) / 3.
                    };
                    let line = window_text.shape_line(
                        label.into(),
                        px(font_size),
                        &[TextRun {
                            len: label.len(),
                            font: font(crate::fonts::SANS_FAMILY),
                            ..TextRun::default()
                        }],
                        None,
                    );
                    assert!(line.width() <= px(width - 6.));
                }
            }
        }
    }
}
