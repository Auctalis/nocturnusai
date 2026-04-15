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
        // Homepage gets top priority; blog and docs get slightly less.
        if (item.url === 'https://nocturnus.ai/') {
          item.priority = 1.0;
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
    // Sharp fails to build from source in some environments, so we stay on
    // `noop`. Blog cover images come from Unsplash which already serves
    // optimized WebP via their CDN — we pass `?w=…&q=…` params.
    // Swap to the default service + `npm i sharp` once we host our own images.
    service: { entrypoint: 'astro/assets/services/noop' },
    domains: ['images.unsplash.com'],
  },
  vite: {
    plugins: [tailwindcss()]
  }
});
