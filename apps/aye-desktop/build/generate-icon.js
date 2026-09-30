const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function createPNG(width, height, pixels) {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

  function crc32(buf) {
    let crc = 0xffffffff;
    const table = [];
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) {
        c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
      }
      table[n] = c;
    }
    for (let i = 0; i < buf.length; i++) {
      crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
    }
    return (crc ^ 0xffffffff) >>> 0;
  }

  function chunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const typeBuf = Buffer.from(type, 'ascii');
    const crcData = Buffer.concat([typeBuf, data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(crcData), 0);
    return Buffer.concat([len, typeBuf, data, crc]);
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  const rawData = [];
  for (let y = 0; y < height; y++) {
    rawData.push(0);
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      rawData.push(pixels[i], pixels[i + 1], pixels[i + 2], pixels[i + 3]);
    }
  }
  const compressed = zlib.deflateSync(Buffer.from(rawData));

  return Buffer.concat([
    signature,
    chunk('IHDR', ihdr),
    chunk('IDAT', compressed),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function drawRoundedRect(ctx, x, y, w, h, r, color) {
  for (let py = y; py < y + h; py++) {
    for (let px = x; px < x + w; px++) {
      let inside = true;
      let dx = 0, dy = 0;

      if (px < x + r) dx = x + r - px;
      else if (px > x + w - r) dx = px - (x + w - r);
      if (py < y + r) dy = y + r - py;
      else if (py > y + h - r) dy = py - (y + h - r);

      if (dx > 0 && dy > 0) {
        inside = (dx * dx + dy * dy) <= (r * r);
      }

      if (inside) {
        const i = (py * ctx.width + px) * 4;
        ctx.pixels[i] = color[0];
        ctx.pixels[i + 1] = color[1];
        ctx.pixels[i + 2] = color[2];
        ctx.pixels[i + 3] = color[3];
      }
    }
  }
}

function drawLetterT(ctx, cx, cy, size, color, thickness) {
  const half = size / 2;
  const thick = thickness;
  const halfThick = thick / 2;
  const topBarY = cy - half + thick * 0.3;
  const stemBottom = cy + half - thick * 0.5;

  for (let py = Math.floor(cy - half); py < Math.ceil(cy + half); py++) {
    for (let px = Math.floor(cx - half); px < Math.ceil(cx + half); px++) {
      const i = (py * ctx.width + px) * 4;

      const inTopBar = (py >= topBarY && py < topBarY + thick) && (px >= cx - half && px < cx + half);
      const inStem = (px >= cx - halfThick && px < cx + halfThick) && (py >= topBarY && py < stemBottom);

      if (inTopBar || inStem) {
        ctx.pixels[i] = color[0];
        ctx.pixels[i + 1] = color[1];
        ctx.pixels[i + 2] = color[2];
        ctx.pixels[i + 3] = color[3];
      }
    }
  }
}

const SIZES = [16, 32, 48, 64, 128, 256];
const ROUND_RATIO = 0.2;
const T_SIZE_RATIO = 0.55;
const T_THICK_RATIO = 0.18;

SIZES.forEach(size => {
  const pixels = Buffer.alloc(size * size * 4);
  for (let i = 0; i < pixels.length; i += 4) {
    pixels[i] = 0;
    pixels[i + 1] = 0;
    pixels[i + 2] = 0;
    pixels[i + 3] = 0;
  }

  const ctx = { width: size, height: size, pixels };

  const padding = Math.round(size * 0.05);
  const rectSize = size - padding * 2;
  const radius = Math.round(rectSize * ROUND_RATIO);

  drawRoundedRect(ctx, padding, padding, rectSize, rectSize, radius, [23, 23, 23, 255]);

  const tSize = Math.round(size * T_SIZE_RATIO);
  const tThick = Math.round(size * T_THICK_RATIO);
  drawLetterT(ctx, Math.floor(size / 2), Math.floor(size / 2), tSize, [255, 255, 255, 255], tThick);

  const png = createPNG(size, size, pixels);
  fs.writeFileSync(path.join(__dirname, `icon-${size}.png`), png);
});

fs.copyFileSync(path.join(__dirname, 'icon-256.png'), path.join(__dirname, 'icon.png'));

console.log('Icons generated successfully!');
