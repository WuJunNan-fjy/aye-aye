const fs = require('fs');
const path = require('path');

function createICO(pngFiles, outputPath) {
  const imageEntries = pngFiles.map(file => {
    const data = fs.readFileSync(file);
    const size = parseInt(path.basename(file).match(/icon-(\d+)\.png/)[1]);
    return { size, data };
  }).sort((a, b) => a.size - b.size);

  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);
  header.writeUInt16LE(imageEntries.length, 4);

  const dirEntries = [];
  const imageData = [];
  let offset = 6 + (16 * imageEntries.length);

  for (const entry of imageEntries) {
    const size = entry.size >= 256 ? 0 : entry.size;
    const dirEntry = Buffer.alloc(16);
    dirEntry[0] = size;
    dirEntry[1] = size;
    dirEntry[2] = 0;
    dirEntry[3] = 0;
    dirEntry.writeUInt16LE(1, 4);
    dirEntry.writeUInt16LE(32, 6);
    dirEntry.writeUInt32LE(entry.data.length, 8);
    dirEntry.writeUInt32LE(offset, 12);
    dirEntries.push(dirEntry);
    imageData.push(entry.data);
    offset += entry.data.length;
  }

  const ico = Buffer.concat([header, ...dirEntries, ...imageData]);
  fs.writeFileSync(outputPath, ico);
  console.log(`ICO created: ${outputPath}`);
}

const sizes = [16, 32, 48, 64, 128, 256];
const pngFiles = sizes.map(s => path.join(__dirname, `icon-${s}.png`));
createICO(pngFiles, path.join(__dirname, 'icon.ico'));
