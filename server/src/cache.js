import { LRUCache } from "lru-cache";

export const caches = {
  search: new LRUCache({
    max: 200,
    ttl: 1000 * 60 * 5,
  }),
  detail: new LRUCache({
    max: 500,
    ttl: 1000 * 60 * 10,
  }),
  streamCheck: new LRUCache({
    max: 1000,
    ttl: 1000 * 60 * 3,
  }),
  mediaType: new LRUCache({
    max: 500,
    ttl: 1000 * 60 * 10,
  }),
};
