// @ts-check
import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';
import mdx from '@astrojs/mdx';
import sitemap from '@astrojs/sitemap';

// Deployed to GitHub Pages with a custom apex domain (nocturnus.ai),
// so the site lives at the root path. public/CNAME tells Pages which
// domain to serve the build from.
export default defineConfig({
  site: 'https://nocturnus.ai',
  base: '/',
  trailingSlash: 'ignore',
  integrations: [
    mdx(),
    sitemap({
      // Keep the sitemap tight: only public, stable routes.
      filter: (page) =>
        !page.includes('/rss.xml') &&
        !page.includes('/404'),
      changefreq: 'weekly',
      priority: 0.7,
      serialize(item) {
        // Homepage is the entry point; examples are the highest-converting
        // pages for developers and deserve top priority below the home.
        if (item.url === 'https://nocturnus.ai/') {
          item.priority = 1.0;
          item.changefreq = 'weekly';
        } else if (item.url === 'https://nocturnus.ai/examples/') {
          item.priority = 0.95;
          item.changefreq = 'weekly';
        } else if (item.url.includes('/examples/')) {
          item.priority = 0.9;
          item.changefreq = 'weekly';
        } else if (item.url === 'https://nocturnus.ai/benchmark/') {
          item.priority = 0.9;
          item.changefreq = 'weekly';
        } else if (item.url === 'https://nocturnus.ai/how-it-works/') {
          item.priority = 0.85;
          item.changefreq = 'weekly';
        } else if (item.url === 'https://nocturnus.ai/integrations/') {
          item.priority = 0.85;
          item.changefreq = 'weekly';
        } else if (item.url.includes('/blog/')) {
          item.priority = 0.8;
          item.changefreq = 'monthly';
        } else if (item.url.includes('/docs/')) {
          item.priority = 0.7;
          item.changefreq = 'monthly';
        }
        return item;
      },
    }),
  ],
  image: {
    // sharp@0.33.5 installed — Astro's default image service now handles
    // local images (resize, WebP/AVIF conversion) and remote Unsplash images.
    domains: ['images.unsplash.com'],
  },
  vite: {
    plugins: [tailwindcss()]
  }
});
