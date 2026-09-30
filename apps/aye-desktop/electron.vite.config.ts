import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import { loadConfigFromFile } from 'vite'
import { resolve } from 'path'

const frontendConfigPath = resolve(__dirname, '../aye-frontend-v2/vite.config.ts')
const frontendRoot = resolve(__dirname, '../aye-frontend-v2')

export default defineConfig(async ({ command, mode }) => {
  const loaded = await loadConfigFromFile({ command, mode }, frontendConfigPath, frontendRoot)
  const frontendConfig = loaded?.config ?? {}

  return {
    main: {
      plugins: [externalizeDepsPlugin()],
      build: {
        lib: {
          entry: resolve(__dirname, 'src/main/index.ts'),
          formats: ['cjs'],
          fileName: () => 'index.js'
        },
        rollupOptions: {
          external: ['electron']
        }
      }
    },
    preload: {
      plugins: [externalizeDepsPlugin()],
      build: {
        lib: {
          entry: resolve(__dirname, 'src/preload/index.ts'),
          formats: ['cjs'],
          fileName: () => 'index.js'
        }
      }
    },
    renderer: {
      root: frontendRoot,
      publicDir: false,
      plugins: frontendConfig.plugins ?? [],
      resolve: {
        alias: {
          ...(frontendConfig.resolve?.alias ?? {}),
          '@': resolve(frontendRoot, 'src')
        }
      },
      base: frontendConfig.base ?? './',
      build: {
        outDir: resolve(__dirname, 'out/renderer'),
        emptyOutDir: true,
        rollupOptions: {
          input: resolve(frontendRoot, 'index.html')
        }
      }
    }
  }
})
