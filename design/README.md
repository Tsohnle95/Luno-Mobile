# Your Library design concepts

Open **[index.html](index.html)** in a browser to compare all ten designs. Each
concept has an **Explore** preview and an **Open full screen** link. Select any
two concepts to view them side by side. Everything runs locally with no package
installation, external fonts or remote artwork.

These are standalone HTML/CSS design prototypes, with sample library data and
local SVG cover illustrations. They do not modify the Android app or access your
library. Search, Songs/Playlists, sort, alphabet navigation, playlist previews,
favorites and the mini-player respond within the prototype. Playback updates the
visual state only; no audio is played. Home and Search in the bottom navigation
are visual references for the existing app chrome.

## Selected direction

**10 / Record shelf** is selected with straight artwork, the current underline
search input and the current ordinary Favorites row. The app uses 10's original
green background and shelf layout. [Preview the current app target](concept.html?concept=10&preview=selected).
All ten numbered concepts retain the original designs shown when you chose;
the app target has its own preview and does not replace concept 10.

## Theme

All concepts use Luno's existing colour tokens from
[`Color.kt`](../app/src/main/java/com/luno/mobile/ui/theme/Color.kt):

| Role | Colour |
| --- | --- |
| Background | `#101010` |
| Surface | `#202020` |
| Elevated surface | `#292929` |
| Accent | `#1ED760` |
| Background green | `#102B1C` |
| Primary / secondary text | `#FFFFFF` / `#B3B3B3` |
| Bottom navigation | `#181818` |

The typography remains a familiar sans serif. The mini-player keeps its artwork,
favorite button beside Play/Pause, progress line and the Home / Search / Your
Library footer. Artwork provides colour; navigation and controls keep Luno green.

## Directions

| # | Concept | Distinct structure |
| --- | --- | --- |
| 01 | [Quiet glow](concept.html?concept=01) | Ambient green light, unified playback toolbar, clean song rows |
| 02 | [Cover stack](concept.html?concept=02) | Contained artwork hero, overlapping covers and round Play action |
| 03 | [Collection grid](concept.html?concept=03) | Playlist-first cover grid and pinned Liked Songs card |
| 04 | [Soft layers](concept.html?concept=04) | Header above a rounded charcoal content sheet |
| 05 | [Library index](concept.html?concept=05) | Compact list, alphabet rail and minimal search line |
| 06 | [Artwork canopy](concept.html?concept=06) | Full-bleed cover wall and floating search field |
| 07 | [Side column](concept.html?concept=07) | Vertical Songs/Playlists rail alongside the list |
| 08 | [Library blocks](concept.html?concept=08) | Large overview tiles that act as the view switch |
| 09 | [One toolbar](concept.html?concept=09) | Search and Play on one surface, spacious song cards |
| 10 | [Record shelf](concept.html?concept=10) | Original playlist sleeves, large artwork and subtle shelf lines |

## Files

- `index.html`, `gallery.css`, `gallery.js`: comparison gallery.
- `concept.html`, `library.css`, `library.js`: responsive mobile prototypes.
- `concepts.js`: concept names, descriptions and layout selection.
- `assets/`: original local SVG cover illustrations.

The designs use shared chrome and controls while each concept owns its page
structure and background. No Android dependencies or build changes are needed.
