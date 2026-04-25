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
};
