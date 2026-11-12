package com.streamhub.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TextFieldHooks
import com.streamhub.tv.focus.TvFocusTarget
import com.streamhub.tv.focus.rememberTvFocusTarget
import com.streamhub.tv.focus.tvFocusable
import com.streamhub.tv.ui.theme.LocalTvFontFamily
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvShapes
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType

/**
 * The text field `.tv-field` / `.tv-input` (PARITY §3.0, §4.13).
 *
 * The engine focuses it like anything else and also gives it real Compose
 * focus, so the IME opens. While it is focused: Left/Right move the caret and
 * leave only from the ends; Up/Down leave; Backspace edits; OK (and the IME's
 * Search/Done) calls [onSubmit] — the `<form>` submit. Keys are ignored while
 * the IME is composing.
 *
 * Focused: white border and `--surface-2`, no scale, no transition.
 */
@Composable
fun TvTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    focusId: String? = null,
    action: String? = null,
    group: String? = null,
    autofocus: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    target: TvFocusTarget? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val engine = LocalTvFocus.current
    val own = rememberTvFocusTarget(focusId = focusId, action = action, group = group, autofocus = autofocus)
    val t = target ?: own
    val requester = remember { FocusRequester() }
    val latestValue by rememberUpdatedState(value)
    val latestSubmit by rememberUpdatedState(onSubmit)
    t.textField = remember(t) { TextFieldHooks(requester, { latestValue }, { latestSubmit() }) }
    val focused = t.isFocused
    val style = TvType.Input.copy(fontFamily = LocalTvFontFamily.current)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (label != null) TvText(label, TvType.FieldLabel, Modifier.padding(start = 14.dp))
        Box(
            Modifier
                .tvFocusable(t)
                .fillMaxWidth()
                .height(38.dp)
                .clip(TvShapes.Pill)
                .background(if (focused) TvColors.Surface2 else TvColors.InputBg)
                .border(1.dp, if (focused) TvColors.Focus else TvColors.InputBorder, TvShapes.Pill)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            leading?.invoke()
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .onFocusChanged { s ->
                        if (s.isFocused) engine.onComposeFocus(t) else if (engine.focused !== t) engine.textHasComposeFocus = false
                    },
                singleLine = true,
                textStyle = style,
                cursorBrush = SolidColor(TvColors.Text),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onDone = { latestSubmit() },
                    onSearch = { latestSubmit() },
                    onGo = { latestSubmit() },
                    onSend = { latestSubmit() },
                ),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty() && placeholder != null) {
                            TvText(placeholder, TvType.Input, color = TvColors.Text3)
                        }
                        inner()
                    }
                },
            )
        }
    }
}
