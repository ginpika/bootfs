# README screenshot notes

Captured on 2026-11-04 from the actual BootFS application with an isolated temporary node and synthetic content.

| File | View |
| --- | --- |
| [preview2.png](./preview2.png) | Media grid; the README hero image |
| [preview1.png](./preview1.png) | File list with image, video, audio and text resources |
| [preview3.png](./preview3.png) | Resource list beside a local `Studio imports` directory |

## Capture and framing

- Application viewport: **1440 × 1024**, device scale factor **1**, actual light theme and Chinese UI.
- Output: **1568 × 1152 PNG**, with **64 px** of background on each side and an **18 px** window radius.
- Background: lavender at the upper left, sky blue at the upper right and mint at the lower right, using overlapping CSS radial gradients on `#d8e8f7`.
- Shadow: `0 24px 48px -12px rgba(30,55,75,.35), 0 6px 16px rgba(30,55,75,.1)`.
- The frame is rendered in HTML/CSS around the captured image at its native size. `image-rendering: pixelated` preserves source pixels at this 1:1 scale. The interior of each output was compared with its raw screenshot: zero changed pixels in the checked area, excluding the rounded corners.
- Fonts, thumbnails and finite animations were settled before capture. File names, labels, controls and metadata are rendered by BootFS itself.

## Demonstration data

The temporary node contains **15 resources**: seven generated photos or illustrations, four native SVG design studies rendered to PNG, one short video made from the coastal image, one synthetic audio clip, Markdown notes and a JSON palette. Uploads, renames, tags and public image flags use the application's APIs. The dual-pane screenshot uses a real browser OPFS directory populated with demonstration files.

The node uses a separate temporary data root and a loopback-only HTTP listener. Media processing uses the installed FFmpeg build with WebP encoding; the lightweight Homebrew FFmpeg build lacks that encoder. No production storage or existing user files are used.

## Generated media

Mode: **built-in `image_gen.imagegen`**, seven independent generation calls, with no reference images or CLI fallback. The exact prompt set follows.

## Alpine morning

Use case: photorealistic-natural. Asset type: original demo media for a self-hosted file library screenshot. A beautiful editorial travel photograph of an Alpine turquoise emerald lake at dawn, layered distant mountains and light drifting mist, clean evergreen shore. Calm expansive composition, refined cyan green and blue-gray palette, soft natural morning light, realistic water and stone texture. Landscape format, no people, no text, no logos, no watermark. Create one photograph, not a collage or UI.

## Quiet coast

Use case: photorealistic-natural. Asset type: original demo media for a self-hosted file library screenshot. A beautiful aerial photograph of a quiet Mediterranean coastal curve, cream sand and sculptural pale rock meeting deep turquoise blue sea, subtle foam and clear shallow-water gradients. Simple graceful composition, warm understated editorial travel photography, natural sunlight. Landscape format, no people, no buildings, no boats, no text, no logos, no watermark. Create one photograph, not a collage or UI.

## Kyoto after rain

Use case: illustration-story. Asset type: original demo media for a self-hosted file library screenshot. An exquisite hand-painted animated film background of a narrow Kyoto lane just after rain at blue-hour dusk, glowing warm amber lanterns in wooden doorways, damp stone pavement reflecting teal and gold, quiet foliage and softly layered depth. Serene cinematic composition, detailed but clean painterly craft, jewel teal and navy with warm golden lights. Portrait format, no people, no readable signs or text, no logos, no watermark. Create one illustration, not a collage or UI.

## Still life

Use case: photorealistic-natural. Asset type: original demo media for a self-hosted file library screenshot. A refined minimalist editorial still life: bright golden lemons and a matte off-white handmade ceramic vase on a warm cream linen surface against a soft beige wall, one slender olive branch, gentle natural window light and elegant subtle shadows. Beautiful tactile organic textures, quiet airy composition with negative space, premium food and interior photography. Portrait format, no people, no text, no logos, no watermark. Create one photograph, not a collage or UI.


## misty-pines

Use case: photorealistic-natural. Asset type: original demo media for a self-hosted file library screenshot. A serene editorial photograph looking into tall evergreen pine trees layered in soft morning mist, cool muted forest green, sage, blue-gray and silver fog. Vertical portrait composition, elegant quiet atmosphere, realistic soft needles and trunks, gentle diffused natural light. Coordinate with calm Alpine lake travel photography. No people, no text, no logos, no watermark. Create one photograph, not a collage or UI.

## desert-light

Use case: photorealistic-natural. Asset type: original demo media for a self-hosted file library screenshot. A refined minimalist landscape photograph of gently flowing golden desert dunes, sculptural curves and delicate wind-carved sand ripples, warm honey and cream tones, one graceful diagonal shadow, late afternoon natural light. Wide horizontal composition, elegant abstract geography, realistic fine sand texture, calm clean editorial travel photography. No people, no animals, no buildings, no text, no logos, no watermark. Create one photograph, not a collage or UI.

## wildflowers

Use case: illustration-story. Asset type: original demo media for a self-hosted file library screenshot. An exquisite botanical watercolor illustration of a loose airy cluster of delicate blue and white wildflowers with slender muted sage-green stems and leaves on warm cream handmade paper, subtle paper texture, luminous translucent pigment, graceful fine details. Vertical portrait composition, understated botanical art, generous breathing room, calm refined palette coordinating with teal Alpine travel photography and warm ceramic still life. No text, no labels, no people, no logos, no watermark. Create one illustration, not a collage or UI.
