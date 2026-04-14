// @ts-check
import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';

// Deployed to GitHub Pages with a custom apex domain (nocturnus.ai),
// so the site lives at the root path. public/CNAME tells Pages which
// domain to serve the build from.
export default defineConfig({
  site: 'https://nocturnus.ai',
  base: '/',
  vite: {
    plugins: [tailwindcss()]
  }
});
