package com.streamhub.mobile.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamhub.core.model.SearchItem
import com.streamhub.mobile.ui.EmptyState
import com.streamhub.mobile.ui.ErrorState
import com.streamhub.mobile.ui.LoadingState
import com.streamhub.mobile.ui.Poster

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    posterUrl: (String) -> String?,
    onOpen: (SearchItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = state.query,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = { viewModel.search() },
                    expanded = false,
                    onExpandedChange = {},
                    placeholder = { Text("Search films and series") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                )
            },
            expanded = false,
            onExpandedChange = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            content = {},
        )

        when {
            state.loading -> LoadingState()

            state.error != null -> ErrorState(state.error!!, onRetry = viewModel::retry)

            !state.hasRun -> EmptyState(
                title = "Search across every provider at once",
                detail = "Results are grouped by where they came from.",
            )

            state.totalItems == 0 -> EmptyState(
                title = "Nothing found for “${state.submitted}”",
                detail = "Try a shorter title, or the original-language name.",
            )

            else -> Results(state, posterUrl, onOpen)
        }
    }
}

@Composable
private fun Results(
    state: SearchUiState,
    posterUrl: (String) -> String?,
    onOpen: (SearchItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 110.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for (group in state.results) {
            if (group.items.isEmpty() && group.error == null) continue

            item(span = { GridItemSpan(maxLineSpan) }, key = "header-${group.provider}") {
                ProviderHeader(group.provider, group.items.size, group.error)
            }

            items(group.items, key = { "${group.provider}:${it.url}" }) { item ->
                ResultCard(item, posterUrl(item.posterUrl.orEmpty()), onOpen)
            }
        }
    }
}

@Composable
private fun ProviderHeader(provider: String, count: Int, error: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(provider, style = MaterialTheme.typography.titleMedium)
            if (error == null) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (error != null) {
            // A provider failing does not fail the search — say which one, and
            // leave the rest of the results usable.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(item: SearchItem, poster: String?, onOpen: (SearchItem) -> Unit) {
    Column(
        modifier = Modifier.clickable { onOpen(item) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Poster(poster, modifier = Modifier.fillMaxWidth())
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
