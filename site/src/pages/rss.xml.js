import rss from '@astrojs/rss';
import { getCollection } from 'astro:content';

export async function GET(context) {
  const posts = (await getCollection('blog', ({ data }) => !data.draft))
    .sort((a, b) => b.data.date.valueOf() - a.data.date.valueOf());

  return rss({
    title: 'Nocturnus.AI — Blog',
    description:
      'Essays, announcements, and benchmarks from the team building Nocturnus — the context engineering engine for AI agents.',
    site: context.site,
    items: posts.map((post) => ({
      title: post.data.title,
      description: post.data.subtitle ?? '',
      pubDate: post.data.date,
      author: post.data.author,
      categories: post.data.tags,
      link: `/blog/${post.id}/`,
    })),
    customData: `<language>en-us</language>`,
    stylesheet: false,
  });
}
