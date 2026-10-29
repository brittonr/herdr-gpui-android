/// A key as a terminal needs it: GPUI's key name ("enter", "up", "a"), its
/// modifiers, and the text it types, if any.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct KeyPress {
    pub key: String,
    pub control: bool,
    pub alt: bool,
    pub shift: bool,
    /// Text the key types with its modifiers, as GPUI's `key_char`.
    pub text: Option<String>,
}

/// The bytes a key sends to the remote program, or `None` when it is not a
/// terminal key: plain printable keys arrive as typed text instead.
/// `app_cursor` follows DECCKM, which switches arrows to `ESC O` sequences.
pub fn key_bytes(key: &KeyPress, app_cursor: bool) -> Option<Vec<u8>> {
    let modifier =
        1 + usize::from(key.shift) + 2 * usize::from(key.alt) + 4 * usize::from(key.control);
    let cursor = match key.key.as_str() {
        "up" => Some('A'),
        "down" => Some('B'),
        "right" => Some('C'),
        "left" => Some('D'),
        "home" => Some('H'),
        "end" => Some('F'),
        _ => None,
    };
    if let Some(final_char) = cursor {
        return Some(
            if modifier > 1 {
                format!("\x1b[1;{modifier}{final_char}")
            } else if app_cursor {
                format!("\x1bO{final_char}")
            } else {
                format!("\x1b[{final_char}")
            }
            .into_bytes(),
        );
    }
    let numbered = match key.key.as_str() {
        "insert" => Some(2),
        "delete" => Some(3),
        "pageup" => Some(5),
        "pagedown" => Some(6),
        "f5" => Some(15),
        "f6" => Some(17),
        "f7" => Some(18),
        "f8" => Some(19),
        "f9" => Some(20),
        "f10" => Some(21),
        "f11" => Some(23),
        "f12" => Some(24),
        _ => None,
    };
    if let Some(n) = numbered {
        return Some(
            if modifier > 1 {
                format!("\x1b[{n};{modifier}~")
            } else {
                format!("\x1b[{n}~")
            }
            .into_bytes(),
        );
    }
    if let Some(c) = match key.key.as_str() {
        "f1" => Some('P'),
        "f2" => Some('Q'),
        "f3" => Some('R'),
        "f4" => Some('S'),
        _ => None,
    } {
        return Some(
            if modifier > 1 {
                format!("\x1b[1;{modifier}{c}")
            } else {
                format!("\x1bO{c}")
            }
            .into_bytes(),
        );
    }
    let text = match key.key.as_str() {
        "enter" => "\r",
        "backspace" if key.alt => "\x1b\x7f",
        "backspace" => "\x7f",
        "escape" => "\x1b",
        "tab" if key.shift => "\x1b[Z",
        "tab" => "\t",
        _ if key.control => {
            let c = match key.key.as_str() {
                "space" | "@" | "2" => 0,
                "[" => 27,
                "\\" => 28,
                "]" => 29,
                "^" | "6" => 30,
                "_" | "-" => 31,
                k if k.len() == 1 && k.as_bytes()[0].is_ascii_alphabetic() => {
                    k.as_bytes()[0].to_ascii_uppercase() & 0x1f
                }
                _ => return None,
            };
            return Some(if key.alt { vec![27, c] } else { vec![c] });
        }
        _ if key.alt => {
            return key
                .text
                .as_ref()
                .map(|text| format!("\x1b{text}").into_bytes());
        }
        _ => return None,
    };
    Some(text.as_bytes().to_vec())
}

#[cfg(test)]
mod tests {
    use super::{KeyPress, key_bytes};

    fn press(key: &str) -> KeyPress {
        KeyPress {
            key: key.into(),
            ..KeyPress::default()
        }
    }

    #[test]
    fn arrows_follow_application_cursor_mode_and_modifiers() {
        assert_eq!(key_bytes(&press("up"), false), Some(b"\x1b[A".to_vec()));
        assert_eq!(key_bytes(&press("up"), true), Some(b"\x1bOA".to_vec()));
        // A modified arrow always uses CSI 1;m, whatever DECCKM says.
        let control_left = KeyPress {
            control: true,
            ..press("left")
        };
        assert_eq!(key_bytes(&control_left, true), Some(b"\x1b[1;5D".to_vec()));
        let shift_pageup = KeyPress {
            shift: true,
            ..press("pageup")
        };
        assert_eq!(key_bytes(&shift_pageup, false), Some(b"\x1b[5;2~".to_vec()));
    }

    #[test]
    fn control_and_alt_send_their_conventional_bytes() {
        let control = |key: &str| KeyPress {
            control: true,
            ..press(key)
        };
        assert_eq!(key_bytes(&control("c"), false), Some(vec![3]));
        assert_eq!(key_bytes(&control("C"), false), Some(vec![3]));
        assert_eq!(key_bytes(&control("["), false), Some(vec![27]));
        assert_eq!(key_bytes(&control("space"), false), Some(vec![0]));
        let control_alt_d = KeyPress {
            alt: true,
            ..control("d")
        };
        assert_eq!(key_bytes(&control_alt_d, false), Some(vec![27, 4]));
        let alt_x = KeyPress {
            alt: true,
            text: Some("x".into()),
            ..press("x")
        };
        assert_eq!(key_bytes(&alt_x, false), Some(b"\x1bx".to_vec()));
        assert_eq!(
            key_bytes(
                &KeyPress {
                    shift: true,
                    ..press("tab")
                },
                false
            ),
            Some(b"\x1b[Z".to_vec())
        );
        assert_eq!(key_bytes(&press("enter"), false), Some(b"\r".to_vec()));
        assert_eq!(key_bytes(&press("backspace"), false), Some(vec![0x7f]));
    }

    #[test]
    fn printable_and_unknown_keys_are_left_to_typed_text() {
        assert_eq!(key_bytes(&press("a"), false), None);
        assert_eq!(key_bytes(&press("volumeup"), false), None);
        assert_eq!(
            key_bytes(
                &KeyPress {
                    control: true,
                    ..press("f")
                },
                false
            ),
            Some(vec![6])
        );
        assert_eq!(
            key_bytes(
                &KeyPress {
                    control: true,
                    ..press("é")
                },
                false
            ),
            None
        );
    }
}
