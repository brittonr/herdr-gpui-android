//! Box-drawing and block-element characters painted as shapes instead of
//! glyphs. A glyph covers only its font's own line, so when a pane's rows are
//! spread over a taller surface its borders and scrollbar break between rows,
//! and the system fonts these characters fall back to disagree on weight and
//! extent. Shapes fill the whole cell, so every line meets the cells above,
//! below and beside it. Geometry is in whole device pixels, so a stroke keeps
//! one width wherever it is drawn.

/// A rectangle of device pixels; `right` and `bottom` are exclusive.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Rect {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}

impl Rect {
    fn width(self) -> i32 {
        self.right - self.left
    }

    fn height(self) -> i32 {
        self.bottom - self.top
    }

    /// Left edge of a vertical stroke `thickness` wide, centred in the cell.
    fn column(self, thickness: i32) -> i32 {
        self.left + (self.width() - thickness) / 2
    }

    /// Top edge of a horizontal stroke `thickness` high, centred in the cell.
    fn row(self, thickness: i32) -> i32 {
        self.top + (self.height() - thickness) / 2
    }
}

/// The corner of an outline that an arc rounds.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Corner {
    TopLeft,
    TopRight,
    BottomRight,
    BottomLeft,
}

/// One piece of a drawn cell, painted in the cell's colour.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) enum Shape {
    Fill(Rect),
    /// The colour at this fraction of its opacity: a shade character.
    Shade(Rect, f32),
    /// The two sides of `outline` that meet at `corner`, each `width` thick
    /// and rounded there with outer radius `radius`, shown only inside `clip`.
    Arc {
        outline: Rect,
        corner: Corner,
        radius: i32,
        width: i32,
        clip: Rect,
    },
}

const NONE: u8 = 0;
const HEAVY: u8 = 2;
const DOUBLE: u8 = 3;

/// Weights of the four arms leaving a cell's centre.
#[derive(Clone, Copy)]
struct Arms {
    left: u8,
    right: u8,
    up: u8,
    down: u8,
}

/// Arms of U+2500..=U+257F, two bits each from the low end: left, right, up,
/// down, weighted 0 none, 1 light, 2 heavy, 3 double. Generated from the
/// Unicode character names. Dashed lines and arcs keep the arms of the line
/// they break or bend; the diagonals are not drawn and have none.
const ARMS: [u8; 128] = [
    // ─    ━    │    ┃    ┄    ┅    ┆    ┇    ┈    ┉    ┊    ┋    ┌    ┍    ┎    ┏
    0x05, 0x0a, 0x50, 0xa0, 0x05, 0x0a, 0x50, 0xa0, 0x05, 0x0a, 0x50, 0xa0, 0x44, 0x48, 0x84, 0x88,
    // ┐    ┑    ┒    ┓    └    ┕    ┖    ┗    ┘    ┙    ┚    ┛    ├    ┝    ┞    ┟
    0x41, 0x42, 0x81, 0x82, 0x14, 0x18, 0x24, 0x28, 0x11, 0x12, 0x21, 0x22, 0x54, 0x58, 0x64, 0x94,
    // ┠    ┡    ┢    ┣    ┤    ┥    ┦    ┧    ┨    ┩    ┪    ┫    ┬    ┭    ┮    ┯
    0xa4, 0x68, 0x98, 0xa8, 0x51, 0x52, 0x61, 0x91, 0xa1, 0x62, 0x92, 0xa2, 0x45, 0x46, 0x49, 0x4a,
    // ┰    ┱    ┲    ┳    ┴    ┵    ┶    ┷    ┸    ┹    ┺    ┻    ┼    ┽    ┾    ┿
    0x85, 0x86, 0x89, 0x8a, 0x15, 0x16, 0x19, 0x1a, 0x25, 0x26, 0x29, 0x2a, 0x55, 0x56, 0x59, 0x5a,
    // ╀    ╁    ╂    ╃    ╄    ╅    ╆    ╇    ╈    ╉    ╊    ╋    ╌    ╍    ╎    ╏
    0x65, 0x95, 0xa5, 0x66, 0x69, 0x96, 0x99, 0x6a, 0x9a, 0xa6, 0xa9, 0xaa, 0x05, 0x0a, 0x50, 0xa0,
    // ═    ║    ╒    ╓    ╔    ╕    ╖    ╗    ╘    ╙    ╚    ╛    ╜    ╝    ╞    ╟
    0x0f, 0xf0, 0x4c, 0xc4, 0xcc, 0x43, 0xc1, 0xc3, 0x1c, 0x34, 0x3c, 0x13, 0x31, 0x33, 0x5c, 0xf4,
    // ╠    ╡    ╢    ╣    ╤    ╥    ╦    ╧    ╨    ╩    ╪    ╫    ╬    ╭    ╮    ╯
    0xfc, 0x53, 0xf1, 0xf3, 0x4f, 0xc5, 0xcf, 0x1f, 0x35, 0x3f, 0x5f, 0xf5, 0xff, 0x44, 0x41, 0x11,
    // ╰    ╱    ╲    ╳    ╴    ╵    ╶    ╷    ╸    ╹    ╺    ╻    ╼    ╽    ╾    ╿
    0x14, 0x00, 0x00, 0x00, 0x01, 0x10, 0x04, 0x40, 0x02, 0x20, 0x08, 0x80, 0x09, 0x90, 0x06, 0x60,
];

/// Quadrants of U+2596..=U+259F: upper left 1, upper right 2, lower left 4,
/// lower right 8.
const QUADRANTS: [u8; 10] = [4, 8, 1, 13, 9, 7, 11, 2, 6, 14];

/// Whether `ch` is painted as shapes: every box-drawing character except the
/// three diagonals, and every block element.
pub(crate) fn drawn(ch: char) -> bool {
    matches!(ch, '\u{2500}'..='\u{2570}' | '\u{2574}'..='\u{259f}')
}

/// A light stroke for cells `cell_width` device pixels wide: an eighth of the
/// cell, about the stem of the bundled mono font, and never under a pixel.
pub(crate) fn light_stroke(cell_width: i32) -> i32 {
    ((cell_width + 4) / 8).max(1)
}

/// Calls `paint` with each shape of `ch` in `cell`, light strokes `light`
/// pixels thick. A character [`drawn`] rejects paints nothing.
pub(crate) fn shapes(ch: char, cell: Rect, light: i32, mut paint: impl FnMut(Shape)) {
    if !drawn(ch) || cell.width() <= 0 || cell.height() <= 0 {
        return;
    }
    let code = u32::from(ch);
    let paint = &mut paint;
    if code >= 0x2580 {
        block(code, cell, paint);
        return;
    }
    let Some(&bits) = ARMS.get((code - 0x2500) as usize) else {
        return;
    };
    let arms = Arms {
        left: bits & 3,
        right: (bits >> 2) & 3,
        up: (bits >> 4) & 3,
        down: bits >> 6,
    };
    let light = light.max(1);
    match code {
        0x2504..=0x250b | 0x254c..=0x254f => dashed(code, arms, cell, light, paint),
        0x256d..=0x2570 => arc(code, cell, light, paint),
        _ if [arms.left, arms.right, arms.up, arms.down].contains(&DOUBLE) => {
            double(arms, cell, light, paint);
        }
        _ => solid(arms, cell, light, paint),
    }
}

fn fill(paint: &mut impl FnMut(Shape), left: i32, top: i32, right: i32, bottom: i32) {
    if right > left && bottom > top {
        paint(Shape::Fill(Rect {
            left,
            top,
            right,
            bottom,
        }));
    }
}

/// Light and heavy lines. An arm runs from its edge to the centre and on
/// through the widest stroke crossing it, so every joint is closed; a half
/// line stops just past the centre.
fn solid(arms: Arms, cell: Rect, light: i32, paint: &mut impl FnMut(Shape)) {
    let thickness = |weight: u8| if weight == HEAVY { 2 * light } else { light };
    let vertical = arms.up.max(arms.down);
    let horizontal = arms.left.max(arms.right);
    if arms.left == arms.right {
        if arms.left != NONE {
            let t = thickness(arms.left);
            fill(paint, cell.left, cell.row(t), cell.right, cell.row(t) + t);
        }
    } else {
        for (weight, from_left) in [(arms.left, true), (arms.right, false)] {
            if weight == NONE {
                continue;
            }
            let t = thickness(weight);
            let reach = thickness(if vertical == NONE { weight } else { vertical });
            let (start, end) = if from_left {
                (cell.left, cell.column(reach) + reach)
            } else {
                (cell.column(reach), cell.right)
            };
            fill(paint, start, cell.row(t), end, cell.row(t) + t);
        }
    }
    if arms.up == arms.down {
        if arms.up != NONE {
            let t = thickness(arms.up);
            fill(
                paint,
                cell.column(t),
                cell.top,
                cell.column(t) + t,
                cell.bottom,
            );
        }
    } else {
        for (weight, from_top) in [(arms.up, true), (arms.down, false)] {
            if weight == NONE {
                continue;
            }
            let t = thickness(weight);
            let reach = thickness(if horizontal == NONE {
                weight
            } else {
                horizontal
            });
            let (start, end) = if from_top {
                (cell.top, cell.row(reach) + reach)
            } else {
                (cell.row(reach), cell.bottom)
            };
            fill(paint, cell.column(t), start, cell.column(t) + t, end);
        }
    }
}

/// Lines with a double arm: two light strokes a light stroke apart, single
/// arms where they meet one. Where a double line turns or branches, the
/// stroke on the outside of the turn runs on to the far corner and the one on
/// the inside stops at its partner, so both meet like the font's own glyphs.
fn double(arms: Arms, cell: Rect, light: i32, paint: &mut impl FnMut(Shape)) {
    let t = light;
    // Outer edges of a double vertical and a double horizontal line.
    let vs = cell.column(3 * t);
    let hs = cell.row(3 * t);
    let vertical = arms.up == DOUBLE || arms.down == DOUBLE;
    let horizontal = arms.left == DOUBLE || arms.right == DOUBLE;
    let through_vertical = arms.up == DOUBLE && arms.down == DOUBLE;
    let through_horizontal = arms.left == DOUBLE && arms.right == DOUBLE;

    if vertical {
        for (x, side) in [(vs, arms.left), (vs + 2 * t, arms.right)] {
            let inner = side == DOUBLE;
            if inner && through_vertical {
                // A double line leaving on this side cuts the stroke.
                fill(paint, x, cell.top, x + t, hs + t);
                fill(paint, x, hs + 2 * t, x + t, cell.bottom);
                continue;
            }
            let top = if arms.up == DOUBLE {
                cell.top
            } else if horizontal {
                if inner { hs + 2 * t } else { hs }
            } else {
                cell.row(t)
            };
            let bottom = if arms.down == DOUBLE {
                cell.bottom
            } else if horizontal {
                if inner { hs + t } else { hs + 3 * t }
            } else {
                cell.row(t) + t
            };
            fill(paint, x, top, x + t, bottom);
        }
    } else if arms.up != NONE || arms.down != NONE {
        let top = if arms.up != NONE {
            cell.top
        } else if through_horizontal {
            hs + 2 * t
        } else {
            hs
        };
        let bottom = if arms.down != NONE {
            cell.bottom
        } else if through_horizontal {
            hs + t
        } else {
            hs + 3 * t
        };
        fill(paint, cell.column(t), top, cell.column(t) + t, bottom);
    }

    if horizontal {
        for (y, side) in [(hs, arms.up), (hs + 2 * t, arms.down)] {
            let inner = side == DOUBLE;
            if inner && through_horizontal {
                fill(paint, cell.left, y, vs + t, y + t);
                fill(paint, vs + 2 * t, y, cell.right, y + t);
                continue;
            }
            let left = if arms.left == DOUBLE {
                cell.left
            } else if vertical {
                if inner { vs + 2 * t } else { vs }
            } else {
                cell.column(t)
            };
            let right = if arms.right == DOUBLE {
                cell.right
            } else if vertical {
                if inner { vs + t } else { vs + 3 * t }
            } else {
                cell.column(t) + t
            };
            fill(paint, left, y, right, y + t);
        }
    } else if arms.left != NONE || arms.right != NONE {
        let left = if arms.left != NONE {
            cell.left
        } else if through_vertical {
            vs + 2 * t
        } else {
            vs
        };
        let right = if arms.right != NONE {
            cell.right
        } else if through_vertical {
            vs + t
        } else {
            vs + 3 * t
        };
        fill(paint, left, cell.row(t), right, cell.row(t) + t);
    }
}

/// Dashed lines: two, three or four dashes per cell, each the middle half of
/// an equal slot, so the gaps keep one rhythm from cell to cell.
fn dashed(code: u32, arms: Arms, cell: Rect, light: i32, paint: &mut impl FnMut(Shape)) {
    let count = match code {
        0x2504..=0x2507 => 3,
        0x2508..=0x250b => 4,
        _ => 2,
    };
    let t = if arms.left == HEAVY || arms.up == HEAVY {
        2 * light
    } else {
        light
    };
    let horizontal = arms.left != NONE;
    let (start, end) = if horizontal {
        (cell.left, cell.right)
    } else {
        (cell.top, cell.bottom)
    };
    let length = end - start;
    for slot in 0..count {
        let from = start + length * slot / count;
        let to = start + length * (slot + 1) / count;
        let gap = (to - from) / 2;
        let (a, b) = (from + gap / 2, to - (gap - gap / 2));
        if horizontal {
            fill(paint, a, cell.row(t), b, cell.row(t) + t);
        } else {
            fill(paint, cell.column(t), a, cell.column(t) + t, b);
        }
    }
}

/// Rounded corners: the corner of an outline whose two sides lie on the
/// light strokes, extended past the cell and clipped to it. The outer radius
/// reaches the nearer far edge, where the arc meets a straight line exactly.
fn arc(code: u32, cell: Rect, t: i32, paint: &mut impl FnMut(Shape)) {
    let (x, y) = (cell.column(t), cell.row(t));
    let (w, h) = (cell.width(), cell.height());
    let (outline, corner, radius) = match code {
        0x256d => (
            Rect {
                left: x,
                top: y,
                right: cell.right + w,
                bottom: cell.bottom + h,
            },
            Corner::TopLeft,
            (cell.right - x).min(cell.bottom - y),
        ),
        0x256e => (
            Rect {
                left: cell.left - w,
                top: y,
                right: x + t,
                bottom: cell.bottom + h,
            },
            Corner::TopRight,
            (x + t - cell.left).min(cell.bottom - y),
        ),
        0x256f => (
            Rect {
                left: cell.left - w,
                top: cell.top - h,
                right: x + t,
                bottom: y + t,
            },
            Corner::BottomRight,
            (x + t - cell.left).min(y + t - cell.top),
        ),
        _ => (
            Rect {
                left: x,
                top: cell.top - h,
                right: cell.right + w,
                bottom: y + t,
            },
            Corner::BottomLeft,
            (cell.right - x).min(y + t - cell.top),
        ),
    };
    paint(Shape::Arc {
        outline,
        corner,
        radius,
        width: t,
        clip: cell,
    });
}

/// Block elements: halves, eighths, quadrants and shades of the whole cell.
/// A sliver of a cell keeps at least one pixel.
fn block(code: u32, cell: Rect, paint: &mut impl FnMut(Shape)) {
    let Rect {
        left,
        top,
        right,
        bottom,
    } = cell;
    let x = |eighths: i32| left + (cell.width() * eighths + 4) / 8;
    let y = |eighths: i32| top + (cell.height() * eighths + 4) / 8;
    match code {
        0x2580 => fill(paint, left, top, right, y(4)),
        0x2581..=0x2588 => {
            let eighths = (code - 0x2580) as i32;
            fill(paint, left, y(8 - eighths).min(bottom - 1), right, bottom);
        }
        0x2589..=0x258f => {
            let eighths = 8 - (code - 0x2588) as i32;
            fill(paint, left, top, x(eighths).max(left + 1), bottom);
        }
        0x2590 => fill(paint, x(4), top, right, bottom),
        0x2591..=0x2593 => paint(Shape::Shade(cell, (code - 0x2590) as f32 / 4.)),
        0x2594 => fill(paint, left, top, right, y(1).max(top + 1)),
        0x2595 => fill(paint, x(7).min(right - 1), top, right, bottom),
        _ => {
            let Some(&quadrants) = QUADRANTS.get((code - 0x2596) as usize) else {
                return;
            };
            let (mid_x, mid_y) = (x(4), y(4));
            for (bit, l, t, r, b) in [
                (1, left, top, mid_x, mid_y),
                (2, mid_x, top, right, mid_y),
                (4, left, mid_y, mid_x, bottom),
                (8, mid_x, mid_y, right, bottom),
            ] {
                if quadrants & bit != 0 {
                    fill(paint, l, t, r, b);
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIGHT: u8 = 1;

    /// An odd-sized cell, so centring and eighths both have to round.
    const CELL: Rect = Rect {
        left: 100,
        top: 200,
        right: 121,
        bottom: 249,
    };
    const LIGHT_STROKE: i32 = 3;

    fn shapes_of(ch: char, cell: Rect) -> Vec<Shape> {
        let mut out = Vec::new();
        shapes(ch, cell, LIGHT_STROKE, |shape| out.push(shape));
        out
    }

    fn fills(ch: char, cell: Rect) -> Vec<Rect> {
        shapes_of(ch, cell)
            .into_iter()
            .map(|shape| match shape {
                Shape::Fill(rect) => rect,
                other => panic!("{ch} painted {other:?}"),
            })
            .collect()
    }

    #[derive(Clone, Copy, Debug)]
    enum Side {
        Left,
        Right,
        Top,
        Bottom,
    }

    /// Pixels the shapes of `ch` cover in the outermost row or column of
    /// `cell` on `side`, as positions along that edge.
    fn edge(ch: char, cell: Rect, side: Side) -> Vec<i32> {
        let rects = fills(ch, cell);
        let (fixed, range) = match side {
            Side::Left => (cell.left, cell.top..cell.bottom),
            Side::Right => (cell.right - 1, cell.top..cell.bottom),
            Side::Top => (cell.top, cell.left..cell.right),
            Side::Bottom => (cell.bottom - 1, cell.left..cell.right),
        };
        range
            .filter(|&along| {
                rects.iter().any(|rect| match side {
                    Side::Left | Side::Right => {
                        (rect.left..rect.right).contains(&fixed)
                            && (rect.top..rect.bottom).contains(&along)
                    }
                    Side::Top | Side::Bottom => {
                        (rect.top..rect.bottom).contains(&fixed)
                            && (rect.left..rect.right).contains(&along)
                    }
                })
            })
            .collect()
    }

    #[test]
    fn only_box_drawing_and_block_characters_are_drawn() {
        for ch in [
            '─', '│', '┼', '║', '╬', '╭', '╰', '╴', '╿', '▀', '█', '▐', '▔', '▕', '░', '▟',
        ] {
            assert!(drawn(ch), "{ch}");
        }
        // Diagonals, ASCII lookalikes, braille and geometric shapes keep their glyphs.
        for ch in ['╱', '╲', '╳', '|', '-', '+', 'a', '⠿', '■', '◀', '\u{24ff}'] {
            assert!(!drawn(ch), "{ch}");
        }
    }

    #[test]
    fn light_strokes_are_an_eighth_of_the_cell_and_never_vanish() {
        assert_eq!(light_stroke(21), 3);
        assert_eq!(light_stroke(17), 2);
        assert_eq!(light_stroke(7), 1);
        assert_eq!(light_stroke(0), 1);
        let heavy = fills('┃', CELL);
        assert_eq!(heavy.len(), 1);
        assert!(
            heavy
                .iter()
                .all(|rect| rect.right - rect.left == 2 * LIGHT_STROKE)
        );
    }

    #[test]
    fn a_vertical_border_fills_its_row_however_far_rows_are_spread() {
        // The reported fault: rows spread to twice their pitch left each `│` a
        // dash. The stroke now spans every pixel row of the cell, so it meets
        // the row below exactly.
        let spread = Rect {
            bottom: CELL.top + 98,
            ..CELL
        };
        let below = Rect {
            top: spread.bottom,
            bottom: spread.bottom + 98,
            ..CELL
        };
        let [upper] = fills('│', spread)[..] else {
            panic!("one stroke");
        };
        let [lower] = fills('│', below)[..] else {
            panic!("one stroke");
        };
        assert_eq!((upper.top, upper.bottom), (spread.top, spread.bottom));
        assert_eq!(upper.bottom, lower.top);
        assert_eq!((upper.left, upper.right), (lower.left, lower.right));
        assert_eq!(upper.right - upper.left, LIGHT_STROKE);
        // Centred: the stroke leaves equal margins, to within a pixel.
        assert!(((upper.left - CELL.left) - (CELL.right - upper.right)).abs() <= 1);
    }

    #[test]
    fn every_line_meets_its_neighbours_edge_to_edge() {
        // Every arm covers exactly the span the straight line of its weight
        // covers where the cells meet, and nothing reaches an edge without an
        // arm, so corners, tees and crosses join borders on every side.
        let straight = |side: Side, weight: u8| match (side, weight) {
            (Side::Left | Side::Right, LIGHT) => Some('─'),
            (Side::Left | Side::Right, HEAVY) => Some('━'),
            (Side::Left | Side::Right, DOUBLE) => Some('═'),
            (Side::Top | Side::Bottom, LIGHT) => Some('│'),
            (Side::Top | Side::Bottom, HEAVY) => Some('┃'),
            (Side::Top | Side::Bottom, DOUBLE) => Some('║'),
            _ => None,
        };
        let mut checked = 0;
        for (index, &bits) in ARMS.iter().enumerate() {
            let code = 0x2500 + index as u32;
            let dashed_or_curved =
                matches!(code, 0x2504..=0x250b | 0x254c..=0x254f | 0x256d..=0x2570);
            let Some(ch) = char::from_u32(code) else {
                continue;
            };
            if !drawn(ch) || dashed_or_curved {
                continue;
            }
            for (side, weight) in [
                (Side::Left, bits & 3),
                (Side::Right, (bits >> 2) & 3),
                (Side::Top, (bits >> 4) & 3),
                (Side::Bottom, bits >> 6),
            ] {
                let expected = straight(side, weight)
                    .map(|line| edge(line, CELL, side))
                    .unwrap_or_default();
                assert_eq!(edge(ch, CELL, side), expected, "{ch} at its {side:?} edge");
            }
            checked += 1;
        }
        // Every character but the twelve dashed lines, four arcs and three diagonals.
        assert_eq!(checked, 109);
    }

    #[test]
    fn double_lines_are_two_light_strokes_a_light_stroke_apart() {
        let [a, b] = fills('║', CELL)[..] else {
            panic!("two strokes");
        };
        assert_eq!(a.right - a.left, LIGHT_STROKE);
        assert_eq!(b.right - b.left, LIGHT_STROKE);
        assert_eq!(b.left - a.right, LIGHT_STROKE);
        assert_eq!(
            (a.top, a.bottom, b.top, b.bottom),
            (CELL.top, CELL.bottom, CELL.top, CELL.bottom)
        );
        // A double corner keeps its outer and inner strokes apart: the pixel
        // just inside the outer bend of `╔` stays empty.
        let [over, _] = fills('═', CELL)[..] else {
            panic!("two strokes");
        };
        let (x, y) = (a.right + 1, over.bottom + 1);
        assert!(fills('╔', CELL).iter().all(|rect| {
            !((rect.left..rect.right).contains(&x) && (rect.top..rect.bottom).contains(&y))
        }));
    }

    #[test]
    fn rounded_corners_bend_from_one_stroke_into_the_other() {
        let [vertical] = fills('│', CELL)[..] else {
            panic!("one stroke");
        };
        let [horizontal] = fills('─', CELL)[..] else {
            panic!("one stroke");
        };
        for (ch, expected) in [
            ('╭', Corner::TopLeft),
            ('╮', Corner::TopRight),
            ('╯', Corner::BottomRight),
            ('╰', Corner::BottomLeft),
        ] {
            let [
                Shape::Arc {
                    outline,
                    corner,
                    radius,
                    width,
                    clip,
                },
            ] = shapes_of(ch, CELL)[..]
            else {
                panic!("{ch} is one arc");
            };
            assert_eq!(corner, expected, "{ch}");
            assert_eq!((width, clip), (LIGHT_STROKE, CELL), "{ch}");
            // The outline's sides lie on the straight strokes...
            let x = if matches!(corner, Corner::TopLeft | Corner::BottomLeft) {
                outline.left
            } else {
                outline.right - width
            };
            let y = if matches!(corner, Corner::TopLeft | Corner::TopRight) {
                outline.top
            } else {
                outline.bottom - width
            };
            assert_eq!((x, y), (vertical.left, horizontal.top), "{ch}");
            // ...and its far sides lie outside the cell, so only the bend shows.
            assert!(
                outline.left < CELL.left || outline.right > CELL.right,
                "{ch}"
            );
            assert!(
                outline.top < CELL.top || outline.bottom > CELL.bottom,
                "{ch}"
            );
            // The bend ends at the nearer far edge of the cell, on the stroke it
            // joins there; a smaller radius would leave a gap before that edge.
            let along = if matches!(corner, Corner::TopLeft | Corner::BottomLeft) {
                CELL.right - outline.left
            } else {
                outline.right - CELL.left
            };
            let down = if matches!(corner, Corner::TopLeft | Corner::TopRight) {
                CELL.bottom - outline.top
            } else {
                outline.bottom - CELL.top
            };
            assert_eq!(radius, along.min(down), "{ch}");
            assert!(radius >= width, "{ch}: an inner radius below zero");
        }
    }

    #[test]
    fn dashes_keep_one_rhythm_into_the_next_cell() {
        for (ch, count) in [('┆', 3), ('┊', 4), ('╎', 2)] {
            let dashes = fills(ch, CELL);
            assert_eq!(dashes.len(), count, "{ch}");
            let gaps: Vec<_> = dashes
                .windows(2)
                .map(|pair| pair[1].top - pair[0].bottom)
                .collect();
            // The gap after the last dash runs into the next cell's first.
            let wrap = (CELL.bottom - dashes[count - 1].bottom) + (dashes[0].top - CELL.top);
            assert!(
                gaps.iter().all(|gap| (gap - wrap).abs() <= 1),
                "{ch}: {gaps:?} then {wrap}"
            );
        }
        let heavy = fills('┅', CELL);
        assert!(
            heavy
                .iter()
                .all(|rect| rect.bottom - rect.top == 2 * LIGHT_STROKE)
        );
    }

    #[test]
    fn blocks_cover_their_share_of_the_whole_cell() {
        assert_eq!(fills('█', CELL), [CELL]);
        let [upper] = fills('▀', CELL)[..] else {
            panic!("one block");
        };
        let [lower] = fills('▄', CELL)[..] else {
            panic!("one block");
        };
        assert_eq!((upper.top, upper.bottom), (CELL.top, lower.top));
        assert_eq!(lower.bottom, CELL.bottom);
        let [right_half] = fills('▐', CELL)[..] else {
            panic!("one block");
        };
        let [left_half] = fills('▌', CELL)[..] else {
            panic!("one block");
        };
        assert_eq!(left_half.right, right_half.left);
        // Eighth slivers survive in a cell too narrow to divide by eight.
        let narrow = Rect {
            right: CELL.left + 4,
            ..CELL
        };
        assert_eq!(
            fills('▏', narrow),
            [Rect {
                right: narrow.left + 1,
                ..narrow
            }]
        );
        assert_eq!(
            fills('▕', narrow),
            [Rect {
                left: narrow.right - 1,
                ..narrow
            }]
        );
        assert_eq!(fills('▁', CELL)[0].bottom, CELL.bottom);
        assert_eq!(fills('▔', CELL)[0].top, CELL.top);
        for (ch, alpha) in [('░', 0.25), ('▒', 0.5), ('▓', 0.75)] {
            assert_eq!(shapes_of(ch, CELL), [Shape::Shade(CELL, alpha)]);
        }
        let diagonal = fills('▚', CELL);
        assert_eq!(diagonal.len(), 2);
        assert_eq!((diagonal[0].left, diagonal[0].top), (CELL.left, CELL.top));
        assert_eq!(
            (diagonal[1].right, diagonal[1].bottom),
            (CELL.right, CELL.bottom)
        );
    }

    #[test]
    fn nothing_is_painted_for_an_empty_cell_or_another_character() {
        let empty = Rect {
            right: CELL.left,
            ..CELL
        };
        assert!(shapes_of('│', empty).is_empty());
        assert!(shapes_of('╱', CELL).is_empty());
        assert!(shapes_of('a', CELL).is_empty());
    }
}
