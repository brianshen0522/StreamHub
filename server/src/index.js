import "dotenv/config";
import express from "express";
import morgan from "morgan";
import { PORT } from "./config.js";
import { providers } from "./providers/index.js";
import { checkStream, handlePosterProxy, handleStreamProxy } from "./stream.js";

const app = express();

app.use(morgan("dev"));
app.use(express.json());

function getProvider(name) {
  const provider = providers[name];
  if (!provider) {
    const error = new Error(`Unsupported provider: ${name}`);
    error.statusCode = 400;
    throw error;
  }
  return provider;
}

app.get("/api/health", (_request, response) => {
  response.json({ ok: true });
});

app.get("/api/search", async (request, response, next) => {
  try {
    const q = String(request.query.q || "").trim();
    const providerFilter = String(request.query.provider || "all");
    if (!q) {
      response.status(400).json({ error: "Missing q parameter." });
      return;
    }
    const providerNames = providerFilter === "all" ? Object.keys(providers) : [providerFilter];
    const settled = await Promise.allSettled(
      providerNames.map(async (providerName) => ({
        provider: providerName,
        items: await getProvider(providerName).search(q),
      })),
    );
    const results = settled.map((result, index) => {
      const provider = providerNames[index];
      if (result.status === "fulfilled") {
        return result.value;
      }
      console.error(`Search failed for provider ${provider}:`, result.reason);
      return {
        provider,
        items: [],
        error: result.reason?.message || "Search failed.",
      };
    });
    response.json({ query: q, results });
  } catch (error) {
    next(error);
  }
});

app.get("/api/item", async (request, response, next) => {
  try {
    const providerName = String(request.query.provider || "");
    const title = String(request.query.title || "");
    const mediaType = String(request.query.mediaType || "unknown");
    const posterUrl = String(request.query.posterUrl || "");
    const url = String(request.query.url || "");
    if (!providerName || !url) {
      response.status(400).json({ error: "Missing provider or url." });
      return;
    }
    const provider = getProvider(providerName);
    const item = await provider.getItem({ title, mediaType, posterUrl, url, provider: providerName });
    response.json(item);
  } catch (error) {
    next(error);
  }
});

app.get("/api/episodes", async (request, response, next) => {
  try {
    const providerName = String(request.query.provider || "");
    const sourceUrl = String(request.query.sourceUrl || "");
    if (!providerName || !sourceUrl) {
      response.status(400).json({ error: "Missing provider or sourceUrl." });
      return;
    }
    const provider = getProvider(providerName);
    const episodes = await provider.getEpisodes(sourceUrl);
    response.json({ provider: providerName, sourceUrl, episodes });
  } catch (error) {
    next(error);
  }
});

app.get("/api/sources", async (request, response, next) => {
  try {
    const providerName = String(request.query.provider || "");
    const sourceUrl = String(request.query.sourceUrl || "");
    const episode = String(request.query.episode || "");
    if (!providerName || !sourceUrl) {
      response.status(400).json({ error: "Missing provider or sourceUrl." });
      return;
    }
    const provider = getProvider(providerName);
    const rawStreams = episode
      ? await provider.getEpisodeStreams(sourceUrl, episode)
      : [];
    const checked = await Promise.all(rawStreams.map((stream) => checkStream(stream)));
    const available = checked.filter((stream) => stream.ok);
    response.json({
      provider: providerName,
      episode,
      sourceUrl,
      sources: available.map((stream) => ({
        ...stream,
        directUrl: stream.url,
        proxyUrl: `/api/stream?target=${encodeURIComponent(stream.url)}`,
      })),
    });
  } catch (error) {
    next(error);
  }
});

app.post("/api/check-sources", async (request, response, next) => {
  try {
    const streams = Array.isArray(request.body?.streams) ? request.body.streams : null;
    if (!streams) {
      response.status(400).json({ error: "Missing streams array." });
      return;
    }
    const checked = await Promise.all(streams.map((stream) => checkStream(stream)));
    const available = checked.filter((stream) => stream.ok);
    response.json({
      sources: available.map((stream) => ({
        ...stream,
        directUrl: stream.url,
        proxyUrl: `/api/stream?target=${encodeURIComponent(stream.url)}`,
      })),
    });
  } catch (error) {
    next(error);
  }
});

app.get("/api/stream", async (request, response, next) => {
  try {
    await handleStreamProxy(request, response);
  } catch (error) {
    next(error);
  }
});

app.get("/api/poster", async (request, response, next) => {
  try {
    await handlePosterProxy(request, response);
  } catch (error) {
    next(error);
  }
});

app.use((error, _request, response, _next) => {
  const statusCode = error.statusCode || 500;
  response.status(statusCode).json({
    error: error.message || "Internal Server Error",
  });
});

app.listen(PORT, () => {
  console.log(`StreamHub server listening on port ${PORT}`);
});
