package com.streamhub.tv.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamhub.core.model.ProviderResults
import com.streamhub.tv.AppContainer
import com.streamhub.tv.MediaSelection
import com.streamhub.tv.ui.PosterCard
import com.streamhub.tv.ui.SectionTitle
import com.streamhub.tv.ui.Tv
import com.streamhub.tv.ui.dpadEscapes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: List<ProviderResults> = emptyList(),
    val searched: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(query = query, searching = true, error = null) }
            try {
                val response = container.api.search(query.trim())
                _state.update {
                    it.copy(searching = false, searched = true, results = response.results)
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(searching = false, searched = true, error = "Could not reach the server.")
                }
            }
        }
    }
}

/**
 * Search, grouped by provider.
 *
 * No provider filter here, unlike the phone. Narrowing the sources is a
 * tinkering control, and every extra control on a television costs d-pad
 * presses that stand between someone and a video. A provider that failed is
 * still shown as a row with its error, because /api/search answers 200 either
 * way and silence would read as "no results" instead of "this one is down".
 */
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    posterUrl: (String) -> String?,
    onOpen: (MediaSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = Tv.OverscanH,
            end = Tv.OverscanH,
            top = Tv.OverscanV,
            bottom = Tv.OverscanV,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    onSubmit = { viewModel.search(query) },
                    modifier = Modifier.width(680.dp).focusRequester(field),
                )
                if (state.searching) {
                    Text(
                        "Searching…",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.error != null) {
            item {
                Text(
                    text = state.error!!,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        items(state.results, key = { it.provider }) { group ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionTitle(group.provider)
                    if (group.error != null) {
                        Text(
                            text = group.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = "${group.items.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (group.items.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Tv.RowGap),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        items(group.items, key = { it.url }) { item ->
                            PosterCard(
                                title = item.title,
                                posterUrl = item.posterUrl?.let(posterUrl),
                                onClick = {
                                    onOpen(
                                        MediaSelection(
                                            provider = item.provider,
                                            itemUrl = item.url,
                                            title = item.title,
                                            mediaType = item.mediaType,
                                            posterUrl = item.posterUrl,
                                        )
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        if (state.searched && state.results.all { it.items.isEmpty() } && state.error == null) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Nothing matched \"${state.query}\".",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .dpadEscapes()
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 1.dp,
                // White, like every other focus indicator here. Red is
                // reserved for what is selected, not for where the remote is.
                color = if (focused) androidx.compose.ui.graphics.Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (value.isEmpty()) {
            Text(
                "Search films and series",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            textStyle = MaterialTheme.typography.titleSmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
    }
}
