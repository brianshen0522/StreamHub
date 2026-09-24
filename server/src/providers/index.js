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
    search: searchMovieffm,
    getItem: getMovieffmItem,
    getEpisodes: getMovieffmSeasonEpisodes,
    getEpisodeStreams: getMovieffmEpisodeStreams,
  },
  "777tv": {
    search: search777tv,
    getItem: get777tvItem,
    getEpisodes: get777tvEpisodes,
    getEpisodeStreams: get777tvEpisodeStreams,
  },
  dramasq: {
    search: searchDramasq,
    getItem: getDramasqItem,
    getEpisodes: getDramasqEpisodes,
    getEpisodeStreams: getDramasqEpisodeStreams,
  },
  chinaq: {
    search: searchChinaq,
    getItem: getChinaqItem,
    getEpisodes: getChinaqEpisodes,
    getEpisodeStreams: getChinaqEpisodeStreams,
  },
  chinaqtw: {
    search: searchChinaqTw,
    getItem: getChinaqTwItem,
    getEpisodes: getChinaqTwEpisodes,
    getEpisodeStreams: getChinaqTwEpisodeStreams,
  },
};
