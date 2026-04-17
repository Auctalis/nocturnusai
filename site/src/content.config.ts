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

/**
 * Examples collection — one MDX per framework. Schema drives both the
 * index card and the detail page so the hand-written content stays
 * focused on the code samples.
 */
const examples = defineCollection({
  loader: glob({ pattern: '**/[^_]*.{md,mdx}', base: './src/content/examples' }),
  schema: z.object({
    title: z.string(),
    framework: z.string(),
    /** One-sentence positioning — shown on the index card and page hero. */
    problem: z.string(),
    /** Naive tokens per turn (site benchmark default: 1259). */
    tokensBefore: z.number().default(1259),
    /** NocturnusAI tokens per turn (site benchmark default: 221). */
    tokensAfter: z.number().default(221),
    /** Multi-line install snippet (fenced as bash). */
    install: z.string(),
    /** Path inside the repo, e.g. "examples/langchain". */
    githubPath: z.string(),
    /** Path to the placeholder/GIF under /public, e.g. "/examples/langchain-demo.png". */
    demoGif: z.string(),
    /** Short descriptor for the demo ("15-turn support ticket", "3-agent research crew"). */
    demoDescription: z.string(),
    /** Order in the index grid (lower = earlier). */
    order: z.number().default(100),
    draft: z.boolean().default(false),
  }),
});

export const collections = { blog, examples };
