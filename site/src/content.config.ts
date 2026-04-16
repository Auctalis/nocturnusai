import { defineCollection, z } from 'astro:content';
import { glob } from 'astro/loaders';

const blog = defineCollection({
  // `[^_]` skips files prefixed with an underscore (handy for drafts-in-progress)
  loader: glob({ pattern: '**/[^_]*.{md,mdx}', base: './src/content/blog' }),
  schema: z.object({
    title: z.string(),
    subtitle: z.string().optional(),
    date: z.coerce.date(),
    updated: z.coerce.date().optional(),
    author: z.string().default('Nocturnus'),
    tags: z.array(z.string()).default([]),
    draft: z.boolean().default(false),
    /**
     * Cover image. Used in four places automatically:
     *   1. Blog post hero
     *   2. Blog index card thumbnail
     *   3. og:image for social shares
     *   4. JSON-LD BlogPosting.image
     *
     * `src` can be an Unsplash URL with sizing params, e.g.
     *   https://images.unsplash.com/photo-...?w=1600&h=840&fit=crop&q=80
     * or a local path in /public, e.g. "/blog-600b-cover.jpg".
     *
     * Photographer attribution is optional — rendered in the post
     * footer when present (required for Unsplash licence).
     */
    cover: z
      .object({
        src: z.string(),
        alt: z.string(),
        photographer: z.string().optional(),
        photographerUrl: z.string().url().optional(),
        unsplashUrl: z.string().url().optional(),
      })
      .optional(),
  }),
});

export const collections = { blog };
