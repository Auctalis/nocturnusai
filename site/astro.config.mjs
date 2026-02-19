// @ts-check
import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';

// When deploying to GitHub Pages without a custom domain, the site lives at
// https://auctalis.github.io/nocturnusai — set GITHUB_PAGES=true in CI to
// enable the base path. Local dev works at http://localhost:4321 without it.
const isGitHubPages = process.env.GITHUB_PAGES === 'true';

export default defineConfig({
  site: 'https://auctalis.github.io',
  base: isGitHubPages ? '/nocturnusai' : '/',
  vite: {
    plugins: [tailwindcss()]
  }
});
