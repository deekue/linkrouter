#!/usr/bin/env node
// Regenerates the Google Play feature graphic (1024x500 PNG) from the
// co-located SVG. Requires Node and the `sharp` package:
//
//   npm install sharp        # once, in any working dir
//   node playstore-feature-graphic.mjs
//
// Output: playstore-feature-graphic-1024x500.png (written next to this file).
//
// Brand palette (from app/src/main/res/values/colors.xml):
//   #FAF9FD  background / surface
//   #D9E2F8  primary container (accent disc)
//   #3B4A6B  primary (routing diagram + dots)
//
// The routing diagram is the exact geometry of ic_launcher_foreground.svg,
// scaled up and recolored to the brand primary.

import sharp from 'sharp';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const svg = join(here, 'playstore-feature-graphic.svg');
const out = join(here, 'playstore-feature-graphic-1024x500.png');

const info = await sharp(svg, { density: 96 })
  .resize(1024, 500, { fit: 'cover' })
  .png()
  .toFile(out);

console.log(`Wrote ${out} (${info.width}x${info.height}, ${info.size} bytes)`);
