package com.ben.karmaasistan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ben.karmaasistan.ui.theme.KarmaAsistanTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prompts = PromptRepository.load(this)
        setContent {
            KarmaAsistanTheme {
                PilotRecorderScreen(prompts)
            }
        }
    }
}

// ------------------------------------------------------------------ palette
private val Teal = Color(0xFF2F7F86)
private val TealDeep = Color(0xFF0D5C63)
private val Coral = Color(0xFFF2876F)
private val Peach = Color(0xFFF6B09C)
private val Mist = Color(0xFFE4ECEC)
private val Muted = Color(0xFF8FAAAD)

private enum class Phase { Idle, Recording, Review }

/**
 * Pilot data collection screen (İP2/İP3).
 * Idle → tap mic → Recording → tap → Review (play / retry / send).
 * "Send" saves recordings/<speaker>/<condition>/<promptId>.wav and logs it in manifest.csv.
 */
@Composable
fun PilotRecorderScreen(prompts: List<Prompt>) {
    val context = LocalContext.current
    val recordingsDir = remember { File(context.getExternalFilesDir(null), "recordings") }
    val takeFile = remember { File(context.cacheDir, "take.wav") }

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    var speakerId by rememberSaveable { mutableStateOf("S01") }
    var condition by rememberSaveable { mutableStateOf("quiet") }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    val levels = remember { mutableStateListOf<Float>() }
    var isPlaying by remember { mutableStateOf(false) }
    var playProgress by remember { mutableFloatStateOf(0f) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    val recorder = remember { WavRecorder() }
    val player = remember { TakePlayer() }
    val scope = rememberCoroutineScope()

    // ---- on-device ASR (whisper.cpp) ----
    var models by remember { mutableStateOf(WhisperEngine.availableModels(context)) }
    var modelIndex by rememberSaveable { mutableIntStateOf(0) }
    var engine by remember { mutableStateOf<WhisperEngine?>(null) }
    var engineStatus by remember { mutableStateOf("") }
    var asr by remember { mutableStateOf<AsrResult?>(null) }
    var asrBusy by remember { mutableStateOf(false) }
    var asrNote by remember { mutableStateOf<String?>(null) }  // shown instead of a transcript
    var windowIndex by rememberSaveable { mutableIntStateOf(0) }  // index into AUDIO_CTX_OPTIONS
    var cpuInfo by remember { mutableStateOf("") }
    var batchJob by remember { mutableStateOf<Job?>(null) }
    var batchStatus by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            recorder.cancel()
            player.release()
            engine?.close()
        }
    }

    // (re)load the selected model off the main thread
    LaunchedEffect(modelIndex, models) {
        val file = models.getOrNull(modelIndex)
        val old = engine
        engine = null
        if (file == null) {
            withContext(Dispatchers.IO) { old?.close() }
            engineStatus = "Model yok: ggml-*.bin dosyasını files/models klasörüne kopyalayın"
            return@LaunchedEffect
        }
        engineStatus = "Model yükleniyor: ${WhisperEngine.displayName(file)}…"
        val loaded = withContext(Dispatchers.IO) {
            old?.close()
            runCatching {
                WhisperEngine.load(file).also { eng ->
                    val window = WhisperEngine.AUDIO_CTX_OPTIONS[windowIndex]
                    if (window != eng.audioCtx) eng.setAudioCtx(window)
                }
            }
        }
        cpuInfo = runCatching { WhisperEngine.cpuFeatures() }.getOrDefault("")
        loaded.onSuccess { engine = it; engineStatus = "" }
            .onFailure { engineStatus = it.message ?: "Model yüklenemedi" }
    }

    // live waveform + timer while recording
    LaunchedEffect(phase) {
        while (phase == Phase.Recording) {
            levels.add(recorder.level)
            elapsed = recorder.elapsedSeconds
            delay(60)
        }
    }
    // playback progress
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            playProgress = player.progress
            if (!player.isPlaying) {
                isPlaying = false
                playProgress = 0f
            }
            delay(50)
        }
    }

    if (prompts.isEmpty()) {
        Text("prompts.csv not found or empty", Modifier.padding(32.dp))
        return
    }

    val prompt = prompts[index]
    val outFile = File(recordingsDir, "$speakerId/$condition/${prompt.id}.wav")
    val alreadyRecorded = remember(outFile.path, refreshKey) { outFile.exists() }

    fun resetTake() {
        player.stop()
        isPlaying = false
        playProgress = 0f
        takeFile.delete()
        levels.clear()
        elapsed = 0.0
        asr = null
        asrNote = null
        phase = Phase.Idle
    }

    fun transcribeTake() {
        asr = null
        val eng = engine
        if (eng == null) {
            // make the missing model visible instead of silently skipping transcription
            asrNote = engineStatus.ifEmpty { "Model yüklü değil" } + " – yukarıdaki ASR düğmesine dokunun"
            return
        }
        asrNote = null
        val audio = runCatching { WavIO.readMono16(takeFile) }.getOrNull()
        if (audio == null) {
            asrNote = "Kayıt okunamadı"
            return
        }
        asrBusy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { runCatching { eng.transcribe(audio) } }
            result.onSuccess { asr = it }.onFailure { asrNote = "ASR hatası: ${it.message}" }
            asrBusy = false
        }
    }

    /** Tap the model pill: rescan the models folder if there is nothing to switch to, else switch. */
    fun cycleModel() {
        if (models.size <= 1) {
            models = WhisperEngine.availableModels(context)
        } else {
            modelIndex = (modelIndex + 1) % models.size
        }
    }

    /** Tap the window pill: 10 s -> 15 s -> 30 s encoder window (smaller = faster). */
    fun cycleWindow() {
        windowIndex = (windowIndex + 1) % WhisperEngine.AUDIO_CTX_OPTIONS.size
        val eng = engine ?: return
        val window = WhisperEngine.AUDIO_CTX_OPTIONS[windowIndex]
        asrBusy = true
        engineStatus = "Pencere ayarlanıyor…"
        scope.launch {
            withContext(Dispatchers.Default) { runCatching { eng.setAudioCtx(window) } }
            engineStatus = ""
            asrBusy = false
        }
    }

    fun toggleBatch() {
        batchJob?.let {
            it.cancel()
            batchJob = null
            batchStatus = "Toplu test durduruldu"
            return
        }
        val eng = engine ?: run { message = engineStatus.ifEmpty { "Model yüklü değil" }; return }
        batchStatus = "Toplu test başlıyor…"
        batchJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    BatchAsr.run(
                        engine = eng,
                        recordingsDir = recordingsDir,
                        onProgress = { done, total -> batchStatus = "Toplu test (${eng.modelName}): $done / $total" },
                        isActive = { isActive },
                    )
                }
            }
            result.onSuccess { batchStatus = "Bitti → ${it.name}" }
                .onFailure { batchStatus = "Toplu test hatası: ${it.message}" }
            batchJob = null
        }
    }

    fun onCenterClick() {
        when (phase) {
            Phase.Idle -> {
                if (batchJob != null) {
                    message = "Toplu test sürerken kayıt yapılamaz"
                    return
                }
                if (!hasMicPermission) {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
                runCatching { recorder.start() }
                    .onSuccess {
                        levels.clear()
                        elapsed = 0.0
                        message = null
                        phase = Phase.Recording
                    }
                    .onFailure { message = "Mikrofon açılamadı: ${it.message}" }
            }
            Phase.Recording -> {
                elapsed = recorder.stop(takeFile)
                phase = Phase.Review
                if (elapsed < 0.5) message = "Kayıt çok kısa, tekrar dene" else transcribeTake()
            }
            Phase.Review -> {
                if (isPlaying) {
                    player.stop()
                    isPlaying = false
                    playProgress = 0f
                } else {
                    runCatching { player.play(takeFile) }
                        .onSuccess { isPlaying = true }
                        .onFailure { message = "Oynatılamadı: ${it.message}" }
                }
            }
        }
    }

    fun onSend() {
        player.stop()
        outFile.parentFile?.mkdirs()
        takeFile.copyTo(outFile, overwrite = true)
        appendManifest(recordingsDir, speakerId, condition, prompt, outFile, elapsed)
        message = "✓ ${prompt.id} kaydedildi"
        refreshKey++
        resetTake()
        if (index < prompts.lastIndex) index++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Peach, Color(0xFF9DB0AC), TealDeep)))
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------- top area
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Header(
                    speakerId = speakerId,
                    onSpeakerChange = { new -> speakerId = new.filter { it.isLetterOrDigit() }.take(6) },
                    condition = condition,
                    onToggleCondition = { condition = if (condition == "quiet") "noisy" else "quiet" },
                    editable = phase == Phase.Idle,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val idle = phase == Phase.Idle && batchJob == null && !asrBusy
                    Pill(onClick = if (idle) ({ cycleModel() }) else null) {
                        Text(
                            "ASR: " + (models.getOrNull(modelIndex)?.let { WhisperEngine.displayName(it) } ?: "model yok"),
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Pill(onClick = if (idle) ({ cycleWindow() }) else null) {
                        Text(
                            "⏱ " + WhisperEngine.windowLabel(WhisperEngine.AUDIO_CTX_OPTIONS[windowIndex]),
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Pill(onClick = if (phase == Phase.Idle && engine != null) ({ toggleBatch() }) else null) {
                        Text(
                            if (batchJob != null) "Durdur" else "Toplu test",
                            color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        )
                    }
                }
                if (cpuInfo.isNotEmpty()) {
                    Text("CPU: $cpuInfo", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
                listOf(engineStatus, batchStatus).filter { it.isNotEmpty() }.forEach {
                    Text(it, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.weight(1f))
                PromptBubble(prompt, alreadyRecorded)
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { index-- }, enabled = phase == Phase.Idle && index > 0) {
                        Icon(painterResource(R.drawable.ic_chevron_left), "Önceki", tint = Color.White)
                    }
                    Text(
                        "${index + 1} / ${prompts.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    IconButton(
                        onClick = { index++ },
                        enabled = phase == Phase.Idle && index < prompts.lastIndex,
                    ) {
                        Icon(painterResource(R.drawable.ic_chevron_right), "Sonraki", tint = Color.White)
                    }
                }
                message?.let {
                    Text(
                        it,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            // ---------------------------------------------------- bottom sheet
            RecorderSheet(
                phase = phase,
                elapsed = elapsed,
                levels = levels,
                isPlaying = isPlaying,
                playProgress = playProgress,
                asr = asr,
                asrBusy = asrBusy,
                asrNote = asrNote,
                onCenterClick = { onCenterClick() },
                onRetry = {
                    resetTake()
                    message = null
                },
                onSend = { onSend() },
            )
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun Header(
    speakerId: String,
    onSpeakerChange: (String) -> Unit,
    condition: String,
    onToggleCondition: () -> Unit,
    editable: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Pilot kayıt",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Pill {
            Text("Konuşmacı ", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            BasicTextField(
                value = speakerId,
                onValueChange = onSpeakerChange,
                enabled = editable,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.width(44.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Pill(onClick = if (editable) onToggleCondition else null) {
            Text(
                if (condition == "quiet") "Sessiz" else "Gürültülü",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun Pill(onClick: (() -> Unit)? = null, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.22f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun PromptBubble(prompt: Prompt, alreadyRecorded: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(prompt.id, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 20.dp))
                    .background(Color(0x40203F42))
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Text(
                    prompt.text,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            Text(
                buildString {
                    append(prompt.langType).append(" · ").append(prompt.intent)
                    if (alreadyRecorded) append("   ✓ kaydedildi")
                },
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun RecorderSheet(
    phase: Phase,
    elapsed: Double,
    levels: List<Float>,
    isPlaying: Boolean,
    playProgress: Float,
    asr: AsrResult?,
    asrBusy: Boolean,
    asrNote: String?,
    onCenterClick: () -> Unit,
    onRetry: () -> Unit,
    onSend: () -> Unit,
) {
    val reviewing = phase == Phase.Review
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
            .background(Color.White)
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(44.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(Mist)
        )
        Spacer(Modifier.height(12.dp))

        MicButton(
            phase = phase,
            isPlaying = isPlaying,
            level = levels.lastOrNull() ?: 0f,
            onClick = onCenterClick,
        )

        val shownSeconds = if (reviewing && isPlaying) elapsed * playProgress else elapsed
        Text(
            formatTime(shownSeconds),
            color = Teal,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            when (phase) {
                Phase.Idle -> "Kayda başlamak için dokun"
                Phase.Recording -> "Bitirmek için dokun"
                Phase.Review -> "Dinle · tekrar kaydet · gönder"
            },
            color = Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        if (reviewing) {
            Spacer(Modifier.height(10.dp))
            AsrCard(asr, asrBusy, asrNote)
        }
        Spacer(Modifier.height(14.dp))

        Waveform(
            levels = levels,
            phase = phase,
            playProgress = if (isPlaying) playProgress else 0f,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        )
        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onRetry, enabled = reviewing) {
                Icon(
                    painterResource(R.drawable.ic_refresh),
                    contentDescription = "Tekrar kaydet",
                    tint = if (reviewing) Teal else Mist,
                    modifier = Modifier.size(30.dp),
                )
            }
            Box(
                Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (reviewing) Coral else Mist)
                    .clickable(enabled = reviewing, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_send),
                    contentDescription = "Kaydet ve sonraki",
                    tint = Color.White,
                    modifier = Modifier
                        .size(26.dp)
                        .rotate(-30f),
                )
            }
        }
    }
}

@Composable
private fun AsrCard(asr: AsrResult?, busy: Boolean, note: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Mist.copy(alpha = 0.6f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (note != null && !busy && asr == null) {
            Text(note, color = Coral, fontSize = 13.sp)
        } else if (busy || asr == null) {
            Text("Yazıya dökülüyor…", color = Muted, fontSize = 14.sp)
        } else {
            Text(
                asr.text.ifEmpty { "(boş)" },
                color = TealDeep, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                String.format(
                    Locale.US, "%s · %.2f s · pencere %s · p(tr)=%.2f p(en)=%.2f · güven %.2f",
                    asr.language, asr.latencyMs / 1000.0, WhisperEngine.windowLabel(asr.audioCtx),
                    asr.pTr, asr.pEn, kotlin.math.exp(asr.meanLogProb),
                ),
                color = Muted, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun MicButton(phase: Phase, isPlaying: Boolean, level: Float, onClick: () -> Unit) {
    val recording = phase == Phase.Recording
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseScale",
    )
    val levelScale by animateFloatAsState(
        targetValue = if (recording) 1f + level * 0.25f else 1f,
        label = "levelScale",
    )
    val haloScale = if (recording) pulse * levelScale else 1f

    Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
        // soft halo
        Box(
            Modifier
                .size(160.dp)
                .scale(haloScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf((if (recording) Coral else Teal).copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )
        // button
        Box(
            Modifier
                .size(108.dp)
                .shadow(10.dp, CircleShape)
                .background(
                    if (recording) Brush.verticalGradient(listOf(Peach, Coral))
                    else Brush.verticalGradient(listOf(Color(0xFFF3ECEA), Mist))
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val (icon, label) = when {
                recording -> R.drawable.ic_stop to "Durdur"
                phase == Phase.Review && isPlaying -> R.drawable.ic_stop to "Durdur"
                phase == Phase.Review -> R.drawable.ic_play to "Dinle"
                else -> R.drawable.ic_mic to "Kayda başla"
            }
            Icon(
                painterResource(icon),
                contentDescription = label,
                tint = if (recording) Color.White else Teal,
                modifier = Modifier.size(42.dp),
            )
        }
    }
}

@Composable
private fun Waveform(levels: List<Float>, phase: Phase, playProgress: Float, modifier: Modifier) {
    val barCount = 40
    val bars: List<Float> = when (phase) {
        Phase.Idle -> List(barCount) { 0f }
        Phase.Recording -> {
            val recent = levels.takeLast(barCount)
            List(barCount - recent.size) { 0f } + recent
        }
        Phase.Review -> downsample(levels, barCount)
    }
    Canvas(modifier) {
        val step = size.width / barCount
        val barWidth = step * 0.45f
        val minHeight = 4.dp.toPx()
        bars.forEachIndexed { i, v ->
            val h = max(minHeight, v * size.height)
            val x = i * step + step / 2
            val t = i / (barCount - 1f)
            val base = lerp(Teal, Coral, t)
            val color = when {
                phase == Phase.Idle -> base.copy(alpha = 0.3f)
                phase == Phase.Review && playProgress > 0f && t > playProgress -> base.copy(alpha = 0.35f)
                else -> base
            }
            drawLine(
                color = color,
                start = Offset(x, center.y - h / 2),
                end = Offset(x, center.y + h / 2),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun downsample(levels: List<Float>, n: Int): List<Float> {
    if (levels.isEmpty()) return List(n) { 0f }
    return List(n) { i ->
        val from = i * levels.size / n
        val to = min(max(from + 1, (i + 1) * levels.size / n), levels.size)
        levels.subList(from, to).maxOrNull() ?: 0f
    }
}

private fun formatTime(seconds: Double): String {
    val s = seconds.toInt()
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

/** Appends one row per saved take; re-recordings add a new row (analysis keeps the last one). */
private fun appendManifest(
    dir: File,
    speaker: String,
    condition: String,
    prompt: Prompt,
    file: File,
    seconds: Double,
) {
    dir.mkdirs()
    val manifest = File(dir, "manifest.csv")
    val isNew = !manifest.exists()
    manifest.appendText(buildString {
        if (isNew) append("speaker,condition,prompt_id,file,duration_s,timestamp\n")
        append(speaker).append(',')
        append(condition).append(',')
        append(prompt.id).append(',')
        append(file.relativeTo(dir).invariantSeparatorsPath).append(',')
        append(String.format(Locale.US, "%.2f", seconds)).append(',')
        append(System.currentTimeMillis()).append('\n')
    })
}
