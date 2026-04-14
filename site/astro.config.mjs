// @ts-check
import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';
import mdx from '@astrojs/mdx';

// Deployed to GitHub Pages with a custom apex domain (nocturnus.ai),
// so the site lives at the root path. public/CNAME tells Pages which
// domain to serve the build from.
export default defineConfig({
  site: 'https://nocturnus.ai',
  base: '/',
  integrations: [mdx()],
  image: {
    // Skip sharp — we don't need image optimization yet and it fails to
    // build from source in some environments. Swap to `passthrough` +
    // install `sharp` once we start serving optimized images.
    service: { entrypoint: 'astro/assets/services/noop' },
  },
  vite: {
    plugins: [tailwindcss()]
  }
});
