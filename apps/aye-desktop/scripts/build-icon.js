const fs = require('fs')
const path = require('path')
const sharp = require('sharp')
const pngToIcoModule = require('png-to-ico')
const pngToIco = pngToIcoModule.default || pngToIcoModule

const root = path.resolve(__dirname, '..')
const svgPath = path.join(root, 'build', 'aye-logo.svg')
const buildDir = path.join(root, 'build')

const sizes = [16, 32, 48, 64, 128, 256, 512]

async function generatePng(size) {
  const output = path.join(buildDir, `icon-${size}.png`)
  await sharp(svgPath, { density: 300 })
    .resize(size, size, { fit: 'contain', background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toFile(output)
  console.log(`Generated ${output}`)
  return output
}

async function main() {
  if (!fs.existsSync(svgPath)) {
    console.error(`SVG logo not found: ${svgPath}`)
    process.exit(1)
  }

  const pngPaths = []
  for (const size of sizes) {
    pngPaths.push(await generatePng(size))
  }

  // Copy largest PNG as main icon.png for Linux / fallback
  fs.copyFileSync(path.join(buildDir, 'icon-512.png'), path.join(buildDir, 'icon.png'))
  console.log('Generated build/icon.png')

  // Generate Windows ICO from multi-size PNGs
  const icoSources = [16, 32, 48, 256].map((s) => path.join(buildDir, `icon-${s}.png`))
  const icoBuffer = await pngToIco(icoSources)
  fs.writeFileSync(path.join(buildDir, 'icon.ico'), icoBuffer)
  console.log('Generated build/icon.ico')

  // Generate macOS ICNS (electron-builder can also generate from PNG, but provide ICNS for compatibility)
  try {
    const { execSync } = require('child_process')
    const iconsetDir = path.join(buildDir, 'icon.iconset')
    if (!fs.existsSync(iconsetDir)) fs.mkdirSync(iconsetDir, { recursive: true })
    const icnsSizes = [16, 32, 128, 256, 512]
    for (const size of icnsSizes) {
      const src = path.join(buildDir, `icon-${size}.png`)
      fs.copyFileSync(src, path.join(iconsetDir, `icon_${size}x${size}.png`))
      if (size <= 256) {
        fs.copyFileSync(src, path.join(iconsetDir, `icon_${size}x${size}@2x.png`))
      }
    }
    execSync(`iconutil -c icns "${iconsetDir}" -o "${path.join(buildDir, 'icon.icns')}"`, { stdio: 'inherit' })
    fs.rmSync(iconsetDir, { recursive: true, force: true })
    console.log('Generated build/icon.icns')
  } catch (err) {
    console.warn('Could not generate icon.icns (macOS iconutil may be unavailable):', err.message)
  }
}

main().catch((err) => {
  console.error(err)
  process.exit(1)
})