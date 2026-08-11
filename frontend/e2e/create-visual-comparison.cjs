const { mkdirSync, writeFileSync } = require('node:fs')
const { dirname, resolve } = require('node:path')
const sharp = require('sharp')
const pixelmatchModule = require('pixelmatch')

const pixelmatch = pixelmatchModule.default ?? pixelmatchModule
const [prototypeArg, currentArg, outputBaseArg] = process.argv.slice(2)

if (!prototypeArg || !currentArg || !outputBaseArg) {
  throw new Error('用法: node create-visual-comparison.cjs <prototype.png> <current.png> <output-base>')
}

async function readRgba(path) {
  return sharp(path).ensureAlpha().raw().toBuffer({ resolveWithObject: true })
}

async function main() {
  const prototypePath = resolve(prototypeArg)
  const currentPath = resolve(currentArg)
  const outputBase = resolve(outputBaseArg)
  mkdirSync(dirname(outputBase), { recursive: true })

  const [prototype, current] = await Promise.all([readRgba(prototypePath), readRgba(currentPath)])
  if (prototype.info.width !== current.info.width || prototype.info.height !== current.info.height) {
    throw new Error(`图片尺寸不一致: prototype=${prototype.info.width}x${prototype.info.height}, current=${current.info.width}x${current.info.height}`)
  }

  const { width, height } = prototype.info
  const diff = Buffer.alloc(width * height * 4)
  const differentPixels = pixelmatch(prototype.data, current.data, diff, width, height, {
    threshold: 0.1,
    includeAA: false,
    alpha: 0.65,
    diffColor: [239, 68, 68],
    aaColor: [245, 158, 11],
  })

  await Promise.all([
    sharp({ create: { width: width * 2, height, channels: 4, background: '#ffffff' } })
      .composite([
        { input: prototypePath, left: 0, top: 0 },
        { input: currentPath, left: width, top: 0 },
      ])
      .png()
      .toFile(`${outputBase}-side-by-side.png`),
    sharp(diff, { raw: { width, height, channels: 4 } })
      .png()
      .toFile(`${outputBase}-diff.png`),
  ])

  const metrics = {
    prototype: prototypePath,
    current: currentPath,
    width,
    height,
    comparedPixels: width * height,
    differentPixels,
    differenceRatio: differentPixels / (width * height),
    threshold: 0.1,
    note: '差异比例用于定位视觉热区，不是自动高保真通过阈值。',
  }
  writeFileSync(`${outputBase}-metrics.json`, `${JSON.stringify(metrics, null, 2)}\n`)
  process.stdout.write(`${JSON.stringify(metrics)}\n`)
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`)
  process.exitCode = 1
})
