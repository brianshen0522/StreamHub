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
  streamMetadata: new LRUCache({
    max: 1000,
    ttl: 1000 * 60 * 10,
  }),
  mediaType: new LRUCache({
    max: 500,
    ttl: 1000 * 60 * 10,
  }),
  // A cleaned playlist and the cut positions come from the same parse, and the
  // player asks for both when it starts. Sharing one entry means one fetch.
  // Content only, no credentials, so it is safe across users.
  cleanedManifest: new LRUCache({
    max: 200,
    ttl: 1000 * 60 * 5,
  }),
};
