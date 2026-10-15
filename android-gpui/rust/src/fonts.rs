//! Deterministic Android fonts, independent of device/OEM font discovery.

pub(crate) const SANS_FAMILY: &str = "Noto Sans";
pub(crate) const MONO_FAMILY: &str = "Noto Mono";

#[cfg(any(target_os = "android", test))]
pub(crate) fn install(text_system: &gpui::TextSystem) -> crate::Result<()> {
    use std::borrow::Cow;

    text_system
        .add_fonts(vec![
            Cow::Borrowed(include_bytes!("../assets/fonts/NotoSans-Regular.ttf")),
            Cow::Borrowed(include_bytes!("../assets/fonts/NotoSans-Bold.ttf")),
            Cow::Borrowed(include_bytes!("../assets/fonts/NotoMono-Regular.ttf")),
        ])
        .map_err(|error| crate::Error::FontRegistration(error.into()))?;

    // The backend can accept a font blob without loading a usable face. Check
    // exact metadata names before GPUI's infallible resolver can panic on paint.
    let families = text_system.all_font_names();
    for family in [SANS_FAMILY, MONO_FAMILY] {
        if !families.iter().any(|name| name == family) {
            return Err(crate::Error::MissingBundledFont(family));
        }
    }
    Ok(())
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use gpui::{
        FontWeight, PlatformTextSystem, RenderGlyphParams, TextRun, TextSystem, WindowTextSystem,
        font, point, px,
    };
    use gpui_wgpu::CosmicTextSystem;
    use std::sync::Arc;

    fn isolated_text_system() -> (Arc<CosmicTextSystem>, Arc<TextSystem>) {
        // Same backend and system alias as GPUI Mobile, deliberately without
        // host fonts. A Linux machine's fonts must not mask the Android failure.
        let backend = Arc::new(CosmicTextSystem::new_without_system_fonts("Roboto"));
        assert!(backend.all_font_names().is_empty());
        assert!(backend.font_id(&font(MONO_FAMILY)).is_err());
        let text_system = Arc::new(TextSystem::new(backend.clone()));
        install(&text_system).unwrap();
        (backend, text_system)
    }

    #[test]
    fn bundled_families_resolve_without_any_system_fonts() {
        let (backend, text_system) = isolated_text_system();
        assert_eq!(text_system.all_font_names(), [MONO_FAMILY, SANS_FAMILY]);
        let mono = backend.font_id(&font(MONO_FAMILY)).unwrap();
        let sans = backend.font_id(&font(SANS_FAMILY)).unwrap();
        assert_ne!(mono, sans);

        let mut bold = font(SANS_FAMILY);
        bold.weight = FontWeight::BOLD;
        assert_ne!(sans, backend.font_id(&bold).unwrap());
        // GPUI's default style can be resolved during window layout, before a
        // child applies its explicit family. Noto Sans also covers that path.
        assert_eq!(text_system.resolve_font(&gpui::Font::default()), sans);
    }

    #[test]
    fn bundled_fonts_shape_and_rasterize_without_any_system_fonts() {
        let (backend, text_system) = isolated_text_system();
        let window_text = WindowTextSystem::new(text_system);
        for family in [MONO_FAMILY, SANS_FAMILY] {
            let text = "Terminal 0123 café";
            let line = window_text.shape_line(
                text.into(),
                px(14.),
                &[TextRun {
                    len: text.len(),
                    font: font(family),
                    ..TextRun::default()
                }],
                None,
            );
            assert!(line.width() > px(0.));
            assert!(!line.runs.is_empty());
            assert!(
                line.runs
                    .iter()
                    .flat_map(|run| &run.glyphs)
                    .all(|glyph| glyph.id.0 != 0)
            );
            let run = &line.runs[0];
            let glyph = &run.glyphs[0];
            let params = RenderGlyphParams {
                font_id: run.font_id,
                glyph_id: glyph.id,
                font_size: px(14.),
                subpixel_variant: point(0, 0),
                scale_factor: 1.,
                is_emoji: false,
                subpixel_rendering: false,
                dilation: 0,
            };
            let bounds = backend.glyph_raster_bounds(&params).unwrap();
            let (_, pixels) = backend.rasterize_glyph(&params, bounds).unwrap();
            assert!(pixels.iter().any(|value| *value != 0));
        }
    }

    #[test]
    fn unsupported_glyphs_do_not_require_a_system_font() {
        let (_, text_system) = isolated_text_system();
        let window_text = WindowTextSystem::new(text_system);
        // Baseline bundled fonts do not cover every script or emoji. Missing
        // glyphs should still shape safely when no device fallback is present.
        let text = "→ ─ 漢 🦀";
        let line = window_text.shape_line(
            text.into(),
            px(14.),
            &[TextRun {
                len: text.len(),
                font: font(MONO_FAMILY),
                ..TextRun::default()
            }],
            None,
        );
        assert!(line.width() > px(0.));
        assert_eq!(line.len(), text.len());
    }

    #[test]
    fn terminal_ascii_glyphs_have_fixed_advance() {
        let (backend, _) = isolated_text_system();
        let mono = backend.font_id(&font(MONO_FAMILY)).unwrap();
        let reference = backend
            .advance(mono, backend.glyph_for_char(mono, 'M').unwrap())
            .unwrap()
            .width;
        for character in ' '..='~' {
            let glyph = backend.glyph_for_char(mono, character).unwrap();
            assert_eq!(backend.advance(mono, glyph).unwrap().width, reference);
        }
    }
}
