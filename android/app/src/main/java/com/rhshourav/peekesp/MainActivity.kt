package com.rhshourav.peekesp

import android.appwidget.AppWidgetManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.updateAll
import com.rhshourav.peekesp.data.FetchResult
import com.rhshourav.peekesp.data.Pairing
import com.rhshourav.peekesp.data.RelayClient
import com.rhshourav.peekesp.data.Setup
import com.rhshourav.peekesp.data.SetupStore
import com.rhshourav.peekesp.data.WidgetCache
import com.rhshourav.peekesp.widget.Palette
import com.rhshourav.peekesp.widget.PeekWidget
import com.rhshourav.peekesp.widget.PeekWidgetReceiver
import com.rhshourav.peekesp.widget.RefreshWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(Palette.CYAN), onPrimary = Color(Palette.BG),
                    background = Color(Palette.BG), onBackground = Color(Palette.TEXT),
                    surface = Color(Palette.PANEL), onSurface = Color(Palette.TEXT),
                    outline = Color(Palette.DIM), outlineVariant = Color(Palette.TRACK),
                    error = Color(Palette.RED),
                ),
            ) {
                // Without a Surface, Text falls back to LocalContentColor's default (black):
                // unreadable on the dark background.
                Surface(color = MaterialTheme.colorScheme.background) { Screen() }
            }
        }
    }
}

// ----------------------------------------------------------------------------
//  State
// ----------------------------------------------------------------------------

/** Two ways in: type the code a device already shows, or make one for a new setup. */
private enum class Mode { ENTER, CREATE }

private enum class Tone { INFO, OK, WARN, ERROR }

private data class Notice(val text: String, val tone: Tone = Tone.INFO)

private fun Tone.color(): Color = Color(
    when (this) {
        Tone.INFO -> Palette.DIM
        Tone.OK -> Palette.GREEN
        Tone.WARN -> Palette.AMBER
        Tone.ERROR -> Palette.RED
    },
)

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val store = remember { SetupStore(ctx) }
    val cache = remember { WidgetCache(ctx) }
    val scope = rememberCoroutineScope()

    var setup by remember { mutableStateOf(store.load().firstOrNull()) }
    var mode by rememberSaveable { mutableStateOf(Mode.ENTER) }
    var input by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable { mutableStateOf(Pairing.generate()) }
    var notice by remember { mutableStateOf<Notice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }

    /**
     * One path for both modes. The difference is what a bad answer means:
     *  - ENTER: the code came from a person, so an unverifiable one is not saved.
     *  - CREATE: the code came from us, so there is no typo to catch. It is saved
     *    even offline and the worker retries; "nothing pushing yet" is simply the
     *    expected state of a brand-new code.
     */
    fun connect(code: String, created: Boolean) {
        if (busy) return
        busy = true
        notice = Notice("Checking the relay\u2026")
        scope.launch {
            val keys = Pairing.derive(code)
            val base = RelayClient.DEFAULT_BASE
            val result = withContext(Dispatchers.IO) { RelayClient.fetch(base, keys) }
            val now = System.currentTimeMillis()

            fun save() {
                store.save(listOf(Setup("My setup", base, keys.code)))
                setup = store.load().firstOrNull()
                input = ""
                revealed = created
                RefreshWorker.schedule(ctx)
            }

            notice = when (result) {
                is FetchResult.Ok -> {
                    save()
                    cache.saveOk(result.raw, result.machines.size, result.latencyMs, now)
                    val n = result.machines.size
                    Notice(
                        if (created) "Code saved. $n machine(s) are already pushing to it."
                        else "Found $n machine(s). Add the widget below.",
                        Tone.OK,
                    )
                }
                FetchResult.Empty -> {
                    save()
                    cache.saveOk("{}", 0, 0, now)
                    if (created) {
                        Notice(
                            "Code saved. Enter it on the device or agent you want to watch. " +
                                "Readings appear as soon as it pushes.",
                            Tone.OK,
                        )
                    } else {
                        // The relay claims a token on first use, so it cannot tell these apart.
                        Notice(
                            "Nothing is pushing to this code yet. That is expected if no agent is " +
                                "installed, and it is also exactly what a typo looks like.",
                            Tone.WARN,
                        )
                    }
                }
                FetchResult.AuthRejected -> {
                    if (created) draft = Pairing.generate()   // a collision: take another
                    Notice(
                        if (created) "That code was already taken, so a new one was made. Try again."
                        else "The relay rejected this code's token. Check it for typos.",
                        Tone.ERROR,
                    )
                }
                is FetchResult.Failed -> {
                    if (created) {
                        save()
                        RefreshWorker.refreshNow(ctx)
                        Notice("Code saved. The relay is unreachable right now; the widget will retry.", Tone.WARN)
                    } else {
                        Notice("Could not reach the relay: ${result.reason}", Tone.ERROR)
                    }
                }
            }
            PeekWidget().updateAll(ctx)
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(Palette.BG))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("PeekESP", style = MaterialTheme.typography.headlineMedium, color = Color(Palette.CYAN))

        val current = setup
        if (current == null) {
            ModeToggle(mode = mode, enabled = !busy, onChange = { mode = it; notice = null })
            when (mode) {
                Mode.ENTER -> EnterCodeForm(
                    input = input,
                    busy = busy,
                    onInput = { input = Pairing.normalise(it).take(Pairing.CODE_LEN) },
                    onSubmit = { if (Pairing.isValid(input)) connect(input, created = false) },
                )
                Mode.CREATE -> CreateCodeForm(
                    code = draft,
                    busy = busy,
                    onRegenerate = { draft = Pairing.generate() },
                    onCopy = { copyCode(ctx, draft); notice = Notice("Code copied.", Tone.OK) },
                    onShare = { shareCode(ctx, draft) },
                    onUse = { connect(draft, created = true) },
                )
            }
        } else {
            PairedPanel(
                code = current.code,
                revealed = revealed,
                onToggleReveal = { revealed = !revealed },
                onCopy = { copyCode(ctx, current.code); notice = Notice("Code copied.", Tone.OK) },
                onShare = { shareCode(ctx, current.code) },
                onRefresh = {
                    RefreshWorker.refreshNow(ctx)
                    notice = Notice("Refreshing the widget\u2026")
                },
                onForget = { confirmForget = true },
            )
        }

        notice?.let {
            Text(
                it.text,
                color = it.tone.color(),
                style = MaterialTheme.typography.bodyMedium,
                // TalkBack announces status changes without the user hunting for them.
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this setup?") },
            text = {
                Text(
                    "The code is removed from this phone and the widget goes blank. " +
                        "Your devices keep their copy, so you can enter the same code again later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    store.clear(); cache.clear(); RefreshWorker.cancel(ctx)
                    setup = null
                    revealed = false
                    draft = Pairing.generate()
                    notice = Notice("Setup forgotten.")
                    scope.launch { PeekWidget().updateAll(ctx) }
                }) { Text("Forget", color = Color(Palette.RED)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Keep") } },
        )
    }
}

// ----------------------------------------------------------------------------
//  Pairing forms
// ----------------------------------------------------------------------------

@Composable
private fun ModeToggle(mode: Mode, enabled: Boolean, onChange: (Mode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .background(Color(Palette.PANEL), RoundedCornerShape(14.dp))
            .padding(4.dp),
    ) {
        @Composable
        fun Option(target: Mode, label: String) {
            val selected = mode == target
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)                       // touch target
                    .background(
                        if (selected) Color(Palette.CYAN) else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .selectable(
                        selected = selected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onChange(target) },
                    ),
            ) {
                Text(
                    label,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color(Palette.BG) else Color(Palette.TEXT),
                )
            }
        }
        Option(Mode.ENTER, "I have a code")
        Option(Mode.CREATE, "Create a code")
    }
}

@Composable
private fun EnterCodeForm(
    input: String,
    busy: Boolean,
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val bad = input.filter { it !in Pairing.ALPHABET }.toSet()
    val valid = Pairing.isValid(input)

    Text("Enter the code your PeekESP device or agent shows.", style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = input,
        onValueChange = onInput,
        label = { Text("Pairing code") },
        placeholder = { Text("XXXX-XXXX-XX") },
        visualTransformation = CodeTransformation,
        isError = bad.isNotEmpty(),
        supportingText = {
            Text(
                if (bad.isNotEmpty()) "${bad.sorted().joinToString(", ")} never appear in a code. I, O, 0 and 1 are left out."
                else "${input.length} of ${Pairing.CODE_LEN} characters",
            )
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { if (valid && !busy) onSubmit() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = onSubmit,
        enabled = !busy && valid,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { ButtonLabel("Pair", busy) }
}

@Composable
private fun CreateCodeForm(
    code: String,
    busy: Boolean,
    onRegenerate: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onUse: () -> Unit,
) {
    Text(
        "Make a new code, then enter it on the device or agent you want to watch. " +
            "It works in either order.",
        style = MaterialTheme.typography.bodyLarge,
    )
    CodeBlock(code = code, revealed = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onCopy, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Copy") }
        OutlinedButton(onClick = onShare, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Share") }
        OutlinedButton(onClick = onRegenerate, enabled = !busy, modifier = Modifier.weight(1f)) { Text("New code") }
    }
    Button(
        onClick = onUse,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { ButtonLabel("Use this code", busy) }
    Text(
        "Anyone with this code can read your readings. Share it only with your own devices.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(Palette.DIM),
    )
}

@Composable
private fun PairedPanel(
    code: String,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRefresh: () -> Unit,
    onForget: () -> Unit,
) {
    val ctx = LocalContext.current
    Text("Paired", style = MaterialTheme.typography.titleMedium, color = Color(Palette.GREEN))

    // The code is the credential: masked by default, shown on request.
    CodeBlock(code = code, revealed = revealed)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onToggleReveal, modifier = Modifier.weight(1f)) {
            Text(if (revealed) "Hide" else "Show")
        }
        OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy") }
        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share") }
    }

    val mgr = AppWidgetManager.getInstance(ctx)
    if (mgr.isRequestPinAppWidgetSupported) {
        Button(
            onClick = {
                mgr.requestPinAppWidget(ComponentName(ctx, PeekWidgetReceiver::class.java), null, null)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Add widget to home screen") }
    } else {
        Text("Long-press the home screen, choose Widgets, then PeekESP.")
    }
    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh widget now") }
    TextButton(onClick = onForget, modifier = Modifier.fillMaxWidth()) {
        Text("Forget this setup", color = Color(Palette.RED))
    }
}

// ----------------------------------------------------------------------------
//  Pieces
// ----------------------------------------------------------------------------

@Composable
private fun ButtonLabel(text: String, busy: Boolean) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = Color(Palette.DIM),
        )
        Box(Modifier.width(10.dp))
    }
    Text(text)
}

/** The code, large and monospaced so I/1 and O/0 cannot be misread when copying by eye. */
@Composable
private fun CodeBlock(code: String, revealed: Boolean) {
    val shown = if (revealed) Pairing.format(code) else "\u2022\u2022\u2022\u2022-\u2022\u2022\u2022\u2022-\u2022\u2022"
    // TalkBack reads a code one character at a time, or says nothing while it is hidden.
    val spoken = if (revealed) "Pairing code: " + Pairing.normalise(code).toList().joinToString(" ")
    else "Pairing code, hidden"
    Surface(
        color = Color(Palette.PANEL),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(Palette.TRACK)),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(
            shown,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            letterSpacing = 3.sp,
            color = Color(if (revealed) Palette.CYAN else Palette.DIM),
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 28.dp),
        )
    }
}

/** Shows K7M2P4QX9R as K7M2-P4QX-9R while the field still holds the bare code. */
private object CodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val out = buildString {
            raw.forEachIndexed { i, ch ->
                if (i == 4 || i == 8) append('-')
                append(ch)
            }
        }
        val map = object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = when {
                offset <= 4 -> offset
                offset <= 8 -> offset + 1
                else -> offset + 2
            }

            override fun transformedToOriginal(offset: Int) = when {
                offset <= 4 -> offset
                offset <= 9 -> offset - 1
                else -> offset - 2
            }.coerceIn(0, raw.length)
        }
        return TransformedText(AnnotatedString(out), map)
    }
}

// ----------------------------------------------------------------------------
//  Clipboard and share
// ----------------------------------------------------------------------------

private fun copyCode(ctx: Context, code: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("PeekESP code", Pairing.format(code))
    if (Build.VERSION.SDK_INT >= 33) {
        // Keeps the code out of the keyboard's clipboard preview and suggestions.
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    cm.setPrimaryClip(clip)
}

private fun shareCode(ctx: Context, code: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "PeekESP pairing code: ${Pairing.format(code)}")
    }
    ctx.startActivity(Intent.createChooser(send, "Share pairing code").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}