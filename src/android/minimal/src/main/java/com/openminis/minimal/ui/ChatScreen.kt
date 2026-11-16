package com.openminis.minimal.ui

// [T-minimal] The chat box.
//
// This is the app. A header with a back arrow, a scrolling transcript, and a
// composer — nothing else, because everything else in the previous app was
// reachable from a menu and a menu is where a chat box stops being one.
//
// Two behaviours are worth stating because they are what makes it feel right:
// the transcript stays pinned to the newest line only while the reader is
// already at the bottom (scrolling up to read is not an invitation to be yanked
// back down), and Stop cancels the HTTP call rather than merely hiding the
// spinner.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.openminis.minimal.R
import com.openminis.minimal.data.Message

@Composable
fun ChatScreen(
    title: String,
    messages: List<Message>,
    streaming: Boolean,
    errorText: String?,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the stream, but only while the reader is already at the bottom.
    // Yanking someone back down mid-read is the single most common way a
    // streaming chat app becomes unpleasant.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0 || last >= total - 2) {
            if (total > 0) listState.animateScrollToItem(total - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .imePadding(),
    ) {
        // Header: back, title, nothing. The previous app had a ⋮ menu here;
        // its contents are what this app deleted.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Ink)
            }
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(48.dp))
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp,
            ),
        ) {
            items(messages, key = { it.id }) { m ->
                when (m.role) {
                    Message.ROLE_USER -> UserBubble(m.text)
                    else -> AssistantRow(m.text)
                }
            }
            if (errorText != null) {
                item(key = "error") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(errorText, fontSize = 13.sp, color = ErrorRed, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRetry) {
                            Text(stringResource(R.string.chat_retry), color = Terracotta)
                        }
                    }
                }
            }
        }

        Composer(
            draft = draft,
            onDraftChange = { draft = it },
            streaming = streaming,
            onSend = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    draft = ""
                    onSend(text)
                }
            },
            onStop = onStop,
        )
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFFEDE9E2),
            modifier = Modifier.fillMaxWidth(0.86f),
        ) {
            Text(
                text = text,
                fontSize = 16.sp,
                lineHeight = 23.sp,
                color = Ink,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantRow(text: String) {
    // Assistant output is markdown rendered in full width. A bubble around it
    // would spend horizontal space on padding that the text needs — code
    // blocks in particular.
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        // The one-argument form on purpose. Colours and typography are
        // parameters of this library whose names have moved between versions;
        // taking the defaults costs a slightly different type scale and removes
        // a build-breaking dependency on that surface. Styling can come back once
        // there is something on screen worth styling.
        Markdown(content = text)
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFF2EFE9))
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (draft.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_placeholder),
                    fontSize = 16.sp,
                    color = InkMuted.copy(alpha = 0.7f),
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = TextStyle(fontSize = 16.sp, color = Ink),
                cursorBrush = SolidColor(Terracotta),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        // One button, two meanings: while a reply is streaming the same target
        // stops it. A separate stop control would be a second thing to find.
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (streaming || draft.isNotBlank()) Ink else Ink.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = { if (streaming) onStop() else onSend() },
                enabled = streaming || draft.isNotBlank(),
            ) {
                Icon(
                    if (streaming) Icons.Default.Stop else Icons.Default.ArrowUpward,
                    contentDescription = stringResource(
                        if (streaming) R.string.chat_stop else R.string.chat_send,
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
