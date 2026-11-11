import {
  getChinaqEpisodeStreams,
  getChinaqEpisodes,
  getChinaqItem,
  searchChinaq,
} from "./chinaq.js";
import {
  getChinaqTwEpisodeStreams,
  getChinaqTwEpisodes,
  getChinaqTwItem,
  searchChinaqTw,
} from "./chinaqtw.js";
import {
  getDramasqEpisodeStreams,
  getDramasqEpisodes,
  getDramasqItem,
  searchDramasq,
} from "./dramasq.js";
import {
  getMovieffmEpisodeStreams,
  getMovieffmItem,
  getMovieffmSeasonEpisodes,
  searchMovieffm,
} from "./movieffm.js";
import {
  get777tvEpisodeStreams,
  get777tvEpisodes,
  get777tvItem,
  search777tv,
} from "./seventv.js";

export const providers = {
  movieffm: {
    // Moved to mvffm.net; links saved under the old host 301 there, path intact.
    domains: ["mvffm.net", "movieffm.net"],
    search: searchMovieffm,
    getItem: getMovieffmItem,
    getEpisodes: getMovieffmSeasonEpisodes,
    getEpisodeStreams: getMovieffmEpisodeStreams,
  },
  "777tv": {
    domains: ["777tv.ai"],
    search: search777tv,
    getItem: get777tvItem,
    getEpisodes: get777tvEpisodes,
    getEpisodeStreams: get777tvEpisodeStreams,
  },
  dramasq: {
    domains: ["dramasq.io"],
    search: searchDramasq,
    getItem: getDramasqItem,
    getEpisodes: getDramasqEpisodes,
    getEpisodeStreams: getDramasqEpisodeStreams,
  },
  chinaq: {
    domains: ["chinaq.fun"],
    search: searchChinaq,
    getItem: getChinaqItem,
    getEpisodes: getChinaqEpisodes,
    getEpisodeStreams: getChinaqEpisodeStreams,
  },
  chinaqtw: {
    // Moved to chinaq.net. The old host is kept because library rows saved
    // under it are still the provider's own URLs; chinaqtw.js rewrites them
    // to the new host before fetching.
    domains: ["chinaq.net", "chinaq.net.tw"],
    search: searchChinaqTw,
    getItem: getChinaqTwItem,
    getEpisodes: getChinaqTwEpisodes,
    getEpisodeStreams: getChinaqTwEpisodeStreams,
  },
};

/**
 * Whether a URL belongs to the provider it was sent with.
 *
 * Every item and season URL a client holds came out of that provider's own
 * pages. The scrapers run regular expressions over what comes back and hand
 * what they find to players, so they are only pointed at the provider's site.
 */
export function isProviderUrl(provider, rawUrl) {
  let url;
  try {
    url = new URL(rawUrl);
  } catch {
    return false;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") return false;
  // A list, because providers move: each still answers for links saved under
  // the host it used to have. Matching is exact per label, so adding
  // "chinaq.net" does not admit "chinaq.net.tw" or the other way round.
  return provider.domains.some((domain) => url.hostname === domain || url.hostname.endsWith(`.${domain}`));
}
