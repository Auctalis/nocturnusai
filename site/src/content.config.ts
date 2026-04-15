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
     * Unsplash cover image. Used in four places automatically:
     *   1. Blog post hero
     *   2. Blog index card thumbnail
     *   3. og:image for social shares
     *   4. JSON-LD BlogPosting.image
     *
     * `src` should include Unsplash sizing params, e.g.
     *   https://images.unsplash.com/photo-...?w=1600&h=840&fit=crop&q=80
     *
     * Photographer attribution is required by the Unsplash license —
     * we render it in the post footer automatically.
     */
    cover: z
      .object({
        src: z.string().url(),
        alt: z.string(),
        photographer: z.string(),
        photographerUrl: z.string().url(),
        // Link to the Unsplash photo page (not the raw image).
        unsplashUrl: z.string().url(),
      })
      .optional(),
  }),
});

export const collections = { blog };
