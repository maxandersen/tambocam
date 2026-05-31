# terminalcam

Terminal camera monitor — inspired by [terminalcam](https://gitlab.com/here_forawhile/terminalcam), built with [TamboUI](https://github.com/tamboui/tamboui).

Live camera feed rendered directly in the terminal with ASCII art, half-block, and braille modes. Compact camera-monitor HUD with interactive controls.

## Requirements

- Java 8+
- [JBang](https://www.jbang.dev/)
- [ffmpeg](https://ffmpeg.org/) (for live camera)

## Usage

```bash
# Synthetic test pattern
jbang terminalcam

# Live camera (macOS/Linux)
jbang terminalcam --camera
jbang terminalcam --camera=1

# List available cameras
jbang terminalcam --list-cameras

# Still image / GIF / directory
jbang terminalcam path/to/image.png
jbang terminalcam path/to/anim.gif
jbang terminalcam path/to/frames/
```

## Controls

| Key | Action |
|-----|--------|
| `m` | Cycle render mode (half-block → braille → ascii) |
| `4` | Cycle ASCII color depth (24bit/256c/16c/gray/green/off) |
| `5` | Cycle ASCII ramp (long/short) |
| `p` | Cycle palette (night/amber/ice) |
| `+`/`-` | Adjust brightness |
| `[`/`]` | Adjust contrast |
| `n`/`b` | Next/prev camera (or frame) |
| `SPACE` | Pause/resume |
| `h` | Toggle HUD |
| `q` | Quit |

## Platforms

| Platform | Camera | Discovery |
|----------|--------|-----------|
| macOS | avfoundation | `--list-cameras` ✓ |
| Linux | v4l2 | `--list-cameras` ✓ |
| Windows | dshow | `--camera=N` only |

## Credits

Inspired by [terminalcam](https://gitlab.com/here_forawhile/terminalcam) by here_forawhile.
