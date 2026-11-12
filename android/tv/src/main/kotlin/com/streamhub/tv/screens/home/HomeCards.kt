package com.streamhub.tv.screens.home

import com.streamhub.core.labels.Labels
import com.streamhub.core.model.ContinueItem
import com.streamhub.core.model.Favorite
import com.streamhub.core.model.WatchHistoryEntry
import com.streamhub.tv.nav.DetailTarget
import com.streamhub.tv.screens.common.FavoriteKey
import kotlin.math.ceil
import kotlin.math.max

/*
 * Home's data, as `tizen/app/src/screens/home.rs:28-194` builds it: one shape
 * for the three library lists, and the card, hero, target and favourite key
 * each row makes. Pure, so it is unit-tested.
 */

/** `HISTORY_LIMIT`. */
const val HISTORY_LIMIT = 40

/** Waits between automatic retries of a failed load (`RETRY_BACKOFF_MS`). */
val RETRY_BACKOFF_MS = longArrayOf(2_000, 4_000, 8_000, 15_000, 30_000)

/** Quiet time after a library event before Home refetches (`LIBRARY_DEBOUNCE_MS`). */
const val LIBRARY_DEBOUNCE_MS = 1_000L

/** Wait after the last hero change before swapping the backdrop (`BACKDROP_DEBOUNCE_MS`). */
const val BACKDROP_DEBOUNCE_MS = 200L

enum class RailKind { Continue, Favorites, History }

/** A rail's state (`Load<Vec<Value>>`). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * One library row, whichever list it came from: the fields `home.rs` reads
 * with `s()` / `num()` / `flag()`, absent as "" / 0 / false. [source] is the
 * original `:core` object, carried into [DetailTarget.row].
 */
data class LibraryRow(
    val id: String = "",
    val providerKey: String,
    val itemUrl: String,
    val title: String,
    val posterUrl: String = "",
    val mediaType: String = "",
    val seasonUrl: String = "",
    val seasonLabel: String = "",
    val episodeLabel: String = "",
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val progressPercent: Double = 0.0,
    val nextUp: Boolean = false,
    val watchedAt: String = "",
    val isUnavailable: Boolean = false,
    val unavailableReason: String = "",
    val source: Any? = null,
)

fun ContinueItem.toRow() = LibraryRow(
    id = id.orEmpty(), providerKey = providerKey, itemUrl = itemUrl, title = title, posterUrl = posterUrl.orEmpty(),
    mediaType = mediaType, seasonUrl = seasonUrl.orEmpty(), seasonLabel = seasonLabel.orEmpty(),
    episodeLabel = episodeLabel.orEmpty(), positionSeconds = positionSeconds.toDouble(),
    durationSeconds = durationSeconds.toDouble(), progressPercent = progressPercent, nextUp = nextUp,
    isUnavailable = isUnavailable, unavailableReason = unavailableReason.orEmpty(), source = this,
)

fun Favorite.toRow() = LibraryRow(
    id = id, providerKey = providerKey, itemUrl = itemUrl, title = title, posterUrl = posterUrl.orEmpty(),
    mediaType = mediaType, seasonUrl = seasonUrl.orEmpty(), seasonLabel = seasonLabel.orEmpty(),
    episodeLabel = episodeLabel.orEmpty(), isUnavailable = isUnavailable,
    unavailableReason = unavailableReason.orEmpty(), source = this,
)

fun WatchHistoryEntry.toRow() = LibraryRow(
    id = id, providerKey = providerKey, itemUrl = itemUrl, title = title, posterUrl = posterUrl.orEmpty(),
    mediaType = mediaType, seasonUrl = seasonUrl.orEmpty(), seasonLabel = seasonLabel.orEmpty(),
    episodeLabel = episodeLabel.orEmpty(), positionSeconds = positionSeconds.toDouble(),
    durationSeconds = durationSeconds.toDouble(), watchedAt = watchedAt.orEmpty(),
    isUnavailable = isUnavailable, unavailableReason = unavailableReason.orEmpty(), source = this,
)

/** What a card shows (`CardData`). [poster] is the provider's raw URL; [provider] is the subtitle fallback key. */
data class HomeCardData(
    val id: String,
    val title: String,
    val subtitle: String,
    val poster: String,
    /** 0–100, or null for no bar. */
    val progress: Double?,
    val badge: String?,
    val chip: String?,
    val provider: String?,
)

/** The text over the backdrop (`Hero`). [meta] is a provider key, shown by display name. */
data class Hero(
    val title: String,
    val line: String,
    val meta: String,
    val poster: String,
    val badge: String?,
    val progress: Double?,
    val hint: String?,
)

/** `unavailable_label`. */
fun unavailableLabel(row: LibraryRow): String? = when {
    !row.isUnavailable -> null
    row.unavailableReason == "no-episodes" -> "已無集數"
    else -> "已下架"
}

/** `row_id`: stable across reloads, it is the focus id route memory returns to. */
fun rowId(prefix: String, row: LibraryRow): String =
    if (row.id.isNotEmpty()) "$prefix:${row.id}"
    else "$prefix:${row.providerKey}:${row.itemUrl}:${row.seasonUrl}:${row.episodeLabel}"

private fun joinParts(vararg parts: String) = parts.filter { it.isNotEmpty() }.joinToString(" · ")

fun continueCard(row: LibraryRow): HomeCardData {
    val ep = Labels.episodeCaption(row.episodeLabel)
    val remaining = max(0.0, row.durationSeconds - row.positionSeconds)
    val subtitle = if (row.nextUp) {
        "$ep 已看完 · 接著看下一集"
    } else {
        joinParts(ep, if (remaining > 0) "剩 ${ceil(remaining / 60.0).toLong()} 分鐘" else "")
    }
    return HomeCardData(
        id = rowId("cw", row),
        title = row.title,
        subtitle = subtitle,
        poster = row.posterUrl,
        // A finished episode carries no progress into the next one.
        progress = if (row.nextUp) null else row.progressPercent,
        badge = unavailableLabel(row),
        chip = if (row.nextUp) "下一集" else null,
        provider = null,
    )
}

fun favoriteCard(row: LibraryRow): HomeCardData {
    val season = if (row.seasonLabel.isEmpty()) "" else Labels.seasonParts(row.seasonLabel).main
    return HomeCardData(
        id = rowId("fav", row),
        title = row.title,
        subtitle = joinParts(season, Labels.episodeCaption(row.episodeLabel)),
        poster = row.posterUrl,
        progress = null,
        badge = unavailableLabel(row),
        chip = null,
        provider = row.providerKey,
    )
}

fun historyCard(row: LibraryRow): HomeCardData {
    val date = row.watchedAt.take(10).replace('-', '/')
    return HomeCardData(
        id = rowId("his", row),
        title = row.title,
        subtitle = joinParts(Labels.episodeCaption(row.episodeLabel), date),
        poster = row.posterUrl,
        progress = if (row.durationSeconds > 0) row.positionSeconds / row.durationSeconds * 100.0 else null,
        badge = unavailableLabel(row),
        chip = null,
        provider = null,
    )
}

fun cardOf(kind: RailKind, row: LibraryRow): HomeCardData = when (kind) {
    RailKind.Continue -> continueCard(row)
    RailKind.Favorites -> favoriteCard(row)
    RailKind.History -> historyCard(row)
}

/** `dedupe_history`: one card per episode, the newest first, at most [HISTORY_LIMIT]. */
fun dedupeHistory(rows: List<LibraryRow>): List<LibraryRow> {
    val seen = HashSet<String>()
    return rows.filter { seen.add("${it.providerKey}|${it.itemUrl}|${it.seasonUrl}|${it.episodeLabel}") }.take(HISTORY_LIMIT)
}

/** `hero_of`. */
fun heroOf(card: HomeCardData, row: LibraryRow, kind: RailKind) = Hero(
    title = card.title,
    line = card.subtitle,
    meta = row.providerKey,
    poster = card.poster,
    badge = card.badge,
    progress = card.progress?.takeIf { it > 0 },
    hint = if (kind == RailKind.Continue) (if (row.nextUp) "按 OK 播放下一集" else "按 OK 繼續播放") else null,
)

/** `target_from_row`: only history pins its season and episode. */
fun targetFromRow(row: LibraryRow, fromHistory: Boolean) = DetailTarget(
    provider = row.providerKey,
    url = row.itemUrl,
    title = row.title,
    posterUrl = row.posterUrl,
    mediaType = row.mediaType,
    seasonUrl = row.seasonUrl.ifEmpty { null },
    episodeLabel = row.episodeLabel.ifEmpty { null },
    fromHistory = fromHistory,
    row = row.source,
)

/** `favorite_key`. */
fun favoriteKeyOf(row: LibraryRow) = FavoriteKey(
    provider = row.providerKey,
    itemUrl = row.itemUrl,
    title = row.title,
    poster = row.posterUrl,
    mediaType = row.mediaType,
    seasonUrl = row.seasonUrl,
    episodeLabel = row.episodeLabel,
)

/** The initial hero: the first thing to continue, else the first favourite (`home.rs:416-427`). */
fun initialHero(cont: Load<List<LibraryRow>>, favs: Load<List<LibraryRow>>): Hero? {
    if (cont is Load.Ready && cont.value.isNotEmpty()) {
        val r = cont.value[0]
        return heroOf(continueCard(r), r, RailKind.Continue)
    }
    if (favs is Load.Ready && favs.value.isNotEmpty()) {
        val r = favs.value[0]
        return heroOf(favoriteCard(r), r, RailKind.Favorites)
    }
    return null
}
