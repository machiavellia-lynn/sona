package app.sona.notch

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.PlaybackState
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Isi kartu panel dengan dua halaman: Musik (meniru popup media One UI) dan Waktu (timer + stopwatch).
 * Halaman digeser dengan jari (mengikuti sentuhan, lalu mengendap dengan ease-out) dan ditandai dua titik.
 *
 * Tata letak halaman musik memakai koordinat dp yang diukur dari popup One UI di S25 (kartu 335x150dp).
 */
class PanelView(context: Context) : FrameLayout(context) {

    var onOpenListenerSettings: (() -> Unit)? = null

    private val dots = ArrayList<View>()
    private val pages = ArrayList<View>()
    private val content = FrameLayout(context)
    private val dotRow: View

    // Latar halaman musik: cover diburamkan + digelapkan, hanya terlihat di halaman musik.
    private val bgArt = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        scaleX = 1.5f
        scaleY = 1.5f
        setRenderEffect(RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.CLAMP))
        // Warna cover dipertegas supaya latar terasa bertema seperti di popup Samsung.
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.6f) })
    }
    private val scrim = View(context).apply { setBackgroundColor(Color.argb(185, 0, 0, 0)) }
    private var bgReveal = 0f

    // Musik
    private val outputLabel = text(13f, Color.argb(235, 255, 255, 255), bold = true)
    private val outputIcon = ImageView(context).apply { setImageResource(R.drawable.ic_output) }
    private val titleIcon = ImageView(context).apply { setImageResource(R.drawable.ic_media_glyph) }
    private val titleView = TextView(context).apply {
        textSize = 14f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true  // marquee hanya berjalan pada view yang selected
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(context.dp(18f))
    }
    private val seek = SeekBarView(context).apply {
        onSeek = { f -> MediaMonitor.seekTo((f * MediaMonitor.state.duration).toLong()) }
    }
    private val elapsed = text(12f, Color.argb(140, 255, 255, 255))
    private val total = text(12f, Color.argb(140, 255, 255, 255)).apply { gravity = Gravity.END }
    private val likeBtn = actionButton()
    private val shuffleBtn = actionButton()
    private val prevBtn = circleButton(R.drawable.ic_prev) { MediaMonitor.prev() }
    private val playBtn = circleButton(R.drawable.ic_play) { MediaMonitor.playPause() }
    private val nextBtn = circleButton(R.drawable.ic_next) { MediaMonitor.next() }
    private lateinit var musicBody: View
    private lateinit var musicEmpty: View
    private val emptyText = text(13f, Color.argb(200, 255, 255, 255)).apply {
        gravity = Gravity.CENTER
        maxLines = 3
    }
    private val accessBtn = pill("Grant notification access") { onOpenListenerSettings?.invoke() }
    private var shownArt: Bitmap? = null
    private var accent = Color.WHITE
    private var shownActions = ""

    // Waktu: satu halaman, dua mode (timer / stopwatch)
    private val modeChips = ArrayList<TextView>()
    private val timeText = text(38f, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER
        fontFeatureSettings = "tnum"  // angka lebar tetap supaya waktu tidak bergeser
    }
    private val minusBtn = pill("−1m") { Timers.tAdjust(-60_000L); update() }
    private val plusBtn = pill("+1m") { Timers.tAdjust(60_000L); update() }
    private val lapBtn = pill("Lap") { Timers.lap(); update() }
    private val toggleBtn = roundAction(R.drawable.ic_play, Ui.GREEN) {
        if (timeMode == MODE_TIMER) Timers.tToggle() else Timers.swToggle()
        update()
    }
    private val resetBtn = roundAction(R.drawable.ic_reset, 0x33FFFFFF) {
        if (timeMode == MODE_TIMER) Timers.tReset() else Timers.swReset()
        update()
    }

    // Paging: pos = posisi halaman sebagai float (0.4 = 40% menuju halaman berikut)
    private var pos = lastTab.toFloat()
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var velocity: VelocityTracker? = null
    private var settle: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    // Quick page state lives above `init`: the pages are built during init and need these to exist already.
    private val torchBtn = roundAction(R.drawable.ic_torch, 0x33FFFFFF) { Flashlight.toggle(context) }
    private val torchSeek = SeekBar(context).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(0xFFFFD60A.toInt())
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser && Flashlight.adjustable) Flashlight.setLevel(1 + p * (Flashlight.max - 1) / 100)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }
    private val ringerBtns = ArrayList<ImageButton>()
    private var quickState = ""

    init {
        // Harus clickable supaya MOVE/UP setelah DOWN tetap sampai ke kita (swipe).
        isClickable = true
        clipToOutline = true
        outlineProvider = roundOutline(context.dp(NotchLayout.CARD_RADIUS).toFloat())

        addView(bgArt, LayoutParams(-1, -1))
        addView(scrim, LayoutParams(-1, -1))
        pages += buildMusicPage()
        pages += buildTimePage()
        pages += buildQuickPage()
        pages.forEach { content.addView(it, LayoutParams(-1, -1)) }
        addView(content, LayoutParams(-1, -1))

        dotRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            repeat(pages.size) { i ->
                val dot = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.WHITE)
                    }
                }
                dots += dot
                addView(
                    FrameLayout(context).apply {
                        setPadding(context.dp(14f), context.dp(3f), context.dp(14f), context.dp(3f))
                        addView(dot, LayoutParams(context.dp(5f), context.dp(5f)))
                        setOnClickListener { animateTo(i) }
                    },
                )
            }
        }
        addView(dotRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setPos(pos)
        selectMode(timeMode)
    }

    /** Jump to a page without animation (used right before the panel opens); [mode] picks timer/stopwatch. */
    fun goTo(page: Int, mode: Int? = null) {
        settle?.cancel()
        if (mode != null) selectMode(mode)
        setPos(page.toFloat().coerceIn(0f, pages.size - 1f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setPos(pos)
    }

    /** Isi muncul bertahap: halaman dulu, titik menyusul sedikit kemudian, masing-masing naik halus. */
    fun setReveal(r: Float) {
        val lift = context.dp(8f).toFloat()
        val a = ((r - 0.3f) / 0.5f).coerceIn(0f, 1f)
        content.alpha = a
        content.translationY = (1f - a) * lift
        val b = ((r - 0.5f) / 0.5f).coerceIn(0f, 1f)
        dotRow.alpha = b
        dotRow.translationY = (1f - b) * lift
        // Latar blur baru muncul di ujung supaya tidak keluar dari bentuk kartu yang masih membesar.
        bgReveal = ((r - 0.75f) / 0.25f).coerceIn(0f, 1f)
        applyBackground()
    }

    private fun applyBackground() {
        val near = (1f - minOf(1f, abs(pos))) * bgReveal
        val hasArt = shownArt != null
        bgArt.alpha = if (hasArt) near else 0f
        scrim.alpha = if (hasArt) near else 0f
    }

    // ---- Paging ----

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                settle?.cancel()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                if (!dragging && abs(dx) > slop && abs(dx) > abs(ev.y - downY)) {
                    dragging = true
                    lastX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) release()
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                lastX = ev.x
                settle?.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.x - downX) > slop && abs(ev.x - downX) > abs(ev.y - downY)) {
                    dragging = true
                    lastX = ev.x
                }
                if (dragging) {
                    val dx = ev.x - lastX
                    lastX = ev.x
                    var next = pos - dx / pageWidth()
                    // Lewat halaman pertama/terakhir: gerak melambat seperti karet.
                    if (next < 0f || next > pages.size - 1f) next = pos - dx / pageWidth() * 0.3f
                    setPos(next)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) settleFromRelease() else release()
            }
        }
        return true
    }

    private fun settleFromRelease() {
        velocity?.computeCurrentVelocity(1000)
        val vx = velocity?.xVelocity ?: 0f
        val target = when {
            vx < -FLING_V -> ceil(pos).toInt().let { if (it.toFloat() == pos) it + 1 else it }
            vx > FLING_V -> floor(pos).toInt().let { if (it.toFloat() == pos) it - 1 else it }
            else -> pos.roundToInt()
        }.coerceIn(0, pages.size - 1)
        release()
        animateTo(target)
    }

    private fun release() {
        dragging = false
        velocity?.recycle()
        velocity = null
    }

    private fun animateTo(target: Int) {
        settle?.cancel()
        val from = pos
        if (from == target.toFloat()) return
        settle = ValueAnimator.ofFloat(from, target.toFloat()).apply {
            duration = (200 + 160 * abs(target - from)).toLong()
            interpolator = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)
            addUpdateListener { setPos(it.animatedValue as Float) }
            start()
        }
    }

    private fun pageWidth() = width.toFloat().coerceAtLeast(1f)

    /** Menggeser tiap halaman relatif terhadap [p]; halaman yang menjauh memudar dan mengecil sedikit. */
    private fun setPos(p: Float) {
        pos = p
        lastTab = p.roundToInt().coerceIn(0, pages.size - 1)
        val w = pageWidth()
        pages.forEachIndexed { i, page ->
            val d = i - p
            val near = 1f - minOf(1f, abs(d))
            page.translationX = d * w
            page.alpha = near
            page.scaleX = 0.94f + 0.06f * near
            page.scaleY = page.scaleX
            page.visibility = if (near <= 0.01f) INVISIBLE else VISIBLE
        }
        dots.forEachIndexed { i, dot -> dot.alpha = 0.3f + 0.7f * (1f - minOf(1f, abs(i - p))) }
        applyBackground()
    }

    // ---- Halaman musik ----

    private fun at(parent: FrameLayout, v: View, w: Float, h: Float, left: Float, top: Float) {
        parent.addView(
            v,
            LayoutParams(if (w < 0) -1 else context.dp(w), if (h < 0) -1 else context.dp(h), Gravity.TOP or Gravity.START)
                .apply {
                    leftMargin = context.dp(left)
                    topMargin = context.dp(top)
                },
        )
    }

    private fun buildMusicPage(): View {
        val body = FrameLayout(context)
        at(body, outputLabel, 200f, 20f, 28f, 12f)
        at(body, outputIcon, 22f, 22f, 285f, 11f)
        at(body, titleIcon, 15f, 15f, 28f, 38f)
        at(body, titleView, 263f, 24f, 48f, 34f)
        at(body, seek, 299f, 26f, 18f, 60f)
        at(body, elapsed, 100f, 16f, 28f, 83f)
        at(body, total, 100f, 16f, 207f, 83f)
        // Pusat tombol: like 56, prev 108, putar 168, next 228, shuffle 278 dp dari kiri kartu.
        at(body, likeBtn, 40f, 40f, 36f, 97f)
        at(body, prevBtn, 41f, 41f, 87f, 96.5f)
        at(body, playBtn, 41f, 41f, 147f, 96.5f)
        at(body, nextBtn, 41f, 41f, 207f, 96.5f)
        at(body, shuffleBtn, 40f, 40f, 258f, 97f)
        musicBody = body

        musicEmpty = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(emptyText, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(accessBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            })
        }
        return FrameLayout(context).apply {
            addView(musicBody, LayoutParams(-1, -1))
            addView(musicEmpty, LayoutParams(-1, -1))
        }
    }

    private fun updateMusic() {
        val s = MediaMonitor.state
        val hasMedia = MediaMonitor.hasAccess && s.title != null
        musicBody.visibility = if (hasMedia) VISIBLE else GONE
        musicEmpty.visibility = if (hasMedia) GONE else VISIBLE
        accessBtn.visibility = if (MediaMonitor.hasAccess) GONE else VISIBLE
        emptyText.setIfChanged(
            if (MediaMonitor.hasAccess) "No music playing"
            else "Grant notification access to show music and notification pop-ups here",
        )
        if (!hasMedia) {
            if (shownArt != null) {
                shownArt = null
                bgArt.setImageBitmap(null)
                applyBackground()
            }
            return
        }

        outputLabel.setIfChanged(outputName())
        titleView.setIfChanged(
            if (s.artist.isNullOrBlank()) s.title.orEmpty() else "${s.title}  •  ${s.artist}",
        )
        if (shownArt !== s.art) {
            shownArt = s.art
            bgArt.setImageBitmap(s.art)
            accent = accentFrom(s.art)
            seek.accent = accent
            applyBackground()
        }
        playBtn.setImageResource(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play)

        val pos = MediaMonitor.position()
        seek.playing = s.playing
        if (s.duration > 0) seek.fraction = pos.toFloat() / s.duration
        seek.invalidate()
        val shownMs = if (s.duration > 0) (seek.shownFraction * s.duration).toLong() else pos
        elapsed.setIfChanged(formatDuration(shownMs))
        total.setIfChanged(if (s.duration > 0) formatDuration(s.duration) else "")
        bindActions()
    }

    /** Aksi kustom aplikasi musik (mis. like dan shuffle) ditampilkan di kiri dan kanan tombol utama. */
    private fun bindActions() {
        val actions = MediaMonitor.customActions
        val key = MediaMonitor.state.pkg + actions.joinToString { "${it.action}:${it.icon}" }
        if (key == shownActions) return
        shownActions = key
        bindAction(likeBtn, actions.getOrNull(0))
        bindAction(shuffleBtn, actions.getOrNull(1))
    }

    private fun bindAction(button: ImageButton, action: PlaybackState.CustomAction?) {
        val icon = action?.let {
            try {
                val res = context.packageManager.getResourcesForApplication(MediaMonitor.state.pkg.orEmpty())
                res.getDrawable(it.icon, null)?.mutate()
            } catch (e: Exception) {
                null
            }
        }
        button.setImageDrawable(icon)
        button.visibility = if (icon == null) INVISIBLE else VISIBLE
        button.setOnClickListener { action?.let(MediaMonitor::sendCustom) }
    }

    /** Nama perangkat keluaran aktif; "This phone" kalau tidak ada perangkat eksternal. */
    private fun outputName(): String {
        val am = context.getSystemService(AudioManager::class.java)
        val external = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        return external?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "This phone"
    }

    // ---- Halaman waktu ----

    private fun buildTimePage(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, context.dp(10f), 0, context.dp(12f))
        // Pemilih mode: radius luar = radius chip + padding (konsentris).
        addView(
            LinearLayout(context).apply {
                background = roundBg(context, Color.rgb(28, 28, 30), 16f)
                setPadding(context.dp(2f), context.dp(2f), context.dp(2f), context.dp(2f))
                listOf("Timer", "Stopwatch").forEachIndexed { i, label ->
                    val chip = text(12f, Color.WHITE, bold = true).apply {
                        text = label
                        gravity = Gravity.CENTER
                        setPadding(context.dp(14f), context.dp(5f), context.dp(14f), context.dp(5f))
                        setOnClickListener { selectMode(i) }
                        pressScale()
                    }
                    modeChips += chip
                    addView(chip)
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(timeText, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(2f) })
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(minusBtn)
                addView(resetBtn)
                addView(toggleBtn)
                addView(plusBtn)
                addView(lapBtn)
            },
            LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
    }

    /** Start/pause button: green play while stopped, orange pause while running. */
    private fun setToggle(running: Boolean) {
        if (toggleRunning == running) return
        toggleRunning = running
        toggleBtn.setImageResource(if (running) R.drawable.ic_pause else R.drawable.ic_play)
        toggleBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (running) 0xFFFF9F0A.toInt() else Ui.GREEN)
        }
    }

    private var toggleRunning: Boolean? = null

    private fun roundAction(res: Int, color: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        layoutParams = LinearLayout.LayoutParams(context.dp(40f), context.dp(40f)).apply {
            setMargins(context.dp(10f), 0, context.dp(10f), 0)
        }
        setOnClickListener { onClick() }
        pressScale()
    }

    // ---- Quick page: flashlight with brightness, ringer mode ----


    private fun setRinger(mode: Int) {
        try {
            context.getSystemService(AudioManager::class.java).ringerMode = mode
        } catch (e: SecurityException) {
            // Silent mode needs Do Not Disturb access on some versions.
        }
    }

    private fun buildQuickPage(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(torchBtn)
                addView(torchSeek, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = context.dp(14f) })
            },
            LinearLayout.LayoutParams(-1, -2),
        )
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                listOf(
                    R.drawable.ic_ring to AudioManager.RINGER_MODE_NORMAL,
                    R.drawable.ic_vibrate to AudioManager.RINGER_MODE_VIBRATE,
                    R.drawable.ic_mute to AudioManager.RINGER_MODE_SILENT,
                ).forEach { (icon, mode) ->
                    val b = roundAction(icon, 0x33FFFFFF) { setRinger(mode); update() }
                    ringerBtns += b
                    addView(b)
                }
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12f) },
        )
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun updateQuick() {
        val mode = context.getSystemService(AudioManager::class.java).ringerMode
        val state = "${Flashlight.on}|$mode|${Flashlight.adjustable}|${Flashlight.available}"
        if (state == quickState) return
        quickState = state
        torchBtn.background = oval(if (Flashlight.on) 0xFFFFD60A.toInt() else 0x33FFFFFF)
        torchSeek.visibility = if (Flashlight.available && Flashlight.adjustable) VISIBLE else INVISIBLE
        val modes = intArrayOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT)
        ringerBtns.forEachIndexed { i, b -> b.background = oval(if (modes[i] == mode) 0x66FFFFFF else 0x33FFFFFF) }
    }

    private fun selectMode(mode: Int) {
        timeMode = mode
        modeChips.forEachIndexed { i, chip ->
            val on = i == mode
            chip.background = if (on) roundBg(context, Color.rgb(58, 58, 60), 14f) else null
            chip.alpha = if (on) 1f else 0.5f
        }
        val timer = mode == MODE_TIMER
        minusBtn.visibility = if (timer) VISIBLE else GONE
        plusBtn.visibility = if (timer) VISIBLE else GONE
        lapBtn.visibility = if (timer) GONE else VISIBLE
        update()
    }

    /** Dipanggil ticker. Semua halaman diperbarui karena halaman tetangga ikut terlihat saat digeser. */
    fun update() {
        updateMusic()
        updateQuick()
        if (timeMode == MODE_TIMER) {
            timeText.setIfChanged(formatDuration(Timers.tLeft()))
            setToggle(Timers.tRunning)
        } else {
            timeText.setIfChanged(formatDuration(Timers.swElapsed(), tenths = true))
            lapBtn.setIfChanged(Timers.lapLabel())
            lapBtn.alpha = if (Timers.swRunning) 1f else 0.4f
            setToggle(Timers.swRunning)
        }
    }

    // ---- Pembuat view ----

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minHeight = context.dp(36f)
        background = roundBg(context, Color.rgb(44, 44, 46), 18f)
        setPadding(context.dp(14f), 0, context.dp(14f), 0)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(4f), 0, context.dp(4f), 0)
        }
        setOnClickListener { onClick() }
        pressScale()
    }

    /** Tombol bulat dengan latar putih transparan seperti pemutar One UI. */
    private fun circleButton(res: Int, onClick: () -> Unit) = ImageButton(context).apply {
        setImageResource(res)
        setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(34, 255, 255, 255))
        }
        setOnClickListener { onClick() }
        pressScale()
    }

    /** Tombol polos untuk aksi kustom aplikasi musik (ikonnya dari paket aplikasi itu). */
    private fun actionButton() = ImageButton(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(context.dp(8f), context.dp(8f), context.dp(8f), context.dp(8f))
        setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        visibility = INVISIBLE
        pressScale()
    }

    private fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    private companion object {
        const val FLING_V = 600f
        const val MODE_TIMER = 0
        var lastTab = 0
        var timeMode = MODE_TIMER
    }
}
