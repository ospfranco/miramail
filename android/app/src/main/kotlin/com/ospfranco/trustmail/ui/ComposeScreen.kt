package com.ospfranco.trustmail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ospfranco.trustmail.R
import com.ospfranco.trustmail.security.Verdict
import kotlinx.coroutines.launch

/**
 * Write and send an email. Reached from the pencil on the inbox (empty), or from the reply button (with the
 * recipient, subject and thread filled in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(model: ComposeViewModel, session: SessionViewModel, onBack: () -> Unit) {
    val colors = Theme.colors
    val scope = rememberCoroutineScope()
    val bodyFocus = remember { FocusRequester() }
    val toFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (model.to.isEmpty()) toFocus else bodyFocus).requestFocus() }
    val sentNotice = stringResource(R.string.notice_sent)

    Column(modifier = Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        TopAppBar(
            title = { Text(stringResource(if (model.isReply) R.string.action_reply else R.string.new_message), color = colors.text) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = colors.text) }
            },
            actions = {
                if (model.isSending) {
                    CircularProgressIndicator(modifier = Modifier.padding(Spacing.two))
                } else {
                    IconButton(
                        enabled = model.canSend,
                        onClick = {
                            scope.launch {
                                if (model.send()) {
                                    session.showNotice(sentNotice)
                                    onBack()
                                }
                            }
                        },
                        modifier = Modifier.testTag("compose-send"),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send)) }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
        )

        Field(stringResource(R.string.compose_to), model.to, { model.to = it }, "compose-to", toFocus, KeyboardOptions(keyboardType = KeyboardType.Email, capitalization = KeyboardCapitalization.None))
        HorizontalDivider(color = colors.backgroundSelected)
        Field(stringResource(R.string.compose_subject), model.subject, { model.subject = it }, "compose-subject", null, KeyboardOptions.Default)
        HorizontalDivider(color = colors.backgroundSelected)
        TextField(
            value = model.body,
            onValueChange = { model.body = it },
            modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(bodyFocus).testTag("compose-body"),
            colors = transparentFieldColors(),
        )
        model.error?.let { AppText(it, TextKind.Small, color = Verdict.Danger.color, modifier = Modifier.padding(Spacing.three)) }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    tag: String,
    focus: FocusRequester?,
    keyboard: KeyboardOptions,
) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = keyboard,
        modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).testTag(tag),
        colors = transparentFieldColors(),
    )
}

@Composable
private fun transparentFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Theme.colors.background,
    unfocusedContainerColor = Theme.colors.background,
    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    focusedTextColor = Theme.colors.text,
    unfocusedTextColor = Theme.colors.text,
)
