package com.davidedicillo.portalroutine

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setMargins
import androidx.lifecycle.lifecycleScope
import com.davidedicillo.portalroutine.admin.NetworkAddress
import com.davidedicillo.portalroutine.data.BoardTask
import com.davidedicillo.portalroutine.data.BoardState
import com.davidedicillo.portalroutine.data.ChildBoardState
import com.davidedicillo.portalroutine.data.RewardConfig
import com.davidedicillo.portalroutine.sync.HubMigrationDirection
import com.davidedicillo.portalroutine.sync.PortalMode
import com.davidedicillo.portalroutine.sync.PortalRuntimePolicy
import com.davidedicillo.portalroutine.sync.SyncStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class HomeActivity : AppCompatActivity() {
    private val app: PortalKidsApplication
        get() = application as PortalKidsApplication

    private var refreshJob: Job? = null
    private var lastCelebrationKey: String? = null

    private val runtimePolicy: PortalRuntimePolicy
        get() = PortalRuntimePolicy(app.deviceSettings.portalMode)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        lifecycleScope.launch {
            applyRuntimePolicy()
            app.repository.ensureSeedData()
            if (runtimePolicy.mode == PortalMode.StandalonePortal && !app.repository.hasParentPin()) {
                showPinSetup()
            } else {
                app.syncRepository.initialize()
                startBoardLoop()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    private fun startBoardLoop() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                if (runtimePolicy.syncsWithHub) {
                    app.syncRepository.syncOnce()
                }
                renderBoard(app.repository.boardState(LocalDateTime.now()))
                delay(5_000)
            }
        }
    }

    private fun showPinSetup() {
        refreshJob?.cancel()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(64), dp(48), dp(64), dp(48))
            setBackgroundColor(Color.rgb(245, 243, 237))
        }
        root.addView(text("PortalKids Setup", 34f, Color.rgb(18, 52, 59), Typeface.BOLD))
        root.addView(text("Set a parent PIN before the routine board and local admin page are available.", 20f, Color.rgb(56, 68, 78)).withTopMargin(12))

        val pin = EditText(this).pinField("Parent PIN")
        val confirm = EditText(this).pinField("Confirm PIN")
        root.addView(pin.withTopMargin(28))
        root.addView(confirm.withTopMargin(12))
        root.addView(actionButton("Save PIN", Color.rgb(31, 138, 112)).apply {
            setOnClickListener {
                val first = pin.text.toString()
                val second = confirm.text.toString()
                if (first.length < 4 || first != second) {
                    Toast.makeText(this@HomeActivity, "Enter matching PINs with at least 4 digits.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    app.repository.setParentPin(first)
                    startBoardLoop()
                }
            }
        }.withTopMargin(16))
        setContentView(root)
    }

    private fun renderBoard(board: BoardState) {
        val root = FrameLayout(this)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(245, 243, 237))
            setPadding(dp(18), dp(14), dp(18), dp(18))
        }
        root.addView(shell, FrameLayout.LayoutParams(match(), match()))
        shell.addView(header(board))
        shell.addView(childrenArea(board).withTopMargin(12), LinearLayout.LayoutParams(match(), 0, 1f))
        setContentView(root)

        val celebrationKey = "${board.routineDate}:${board.activeWindow.id}"
        if (board.allComplete && lastCelebrationKey != celebrationKey) {
            lastCelebrationKey = celebrationKey
            showCelebration(root)
        } else if (!board.allComplete) {
            lastCelebrationKey = null
        }
    }

    private fun header(board: BoardState): LinearLayout {
        val formatter = DateTimeFormatter.ofPattern("EEE, MMM d  h:mm a")
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(board.activeWindow.name, 32f, Color.rgb(18, 52, 59), Typeface.BOLD))
                addView(text(LocalDateTime.now().format(formatter), 17f, Color.rgb(73, 85, 96)))
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(actionButton("Parent", Color.rgb(18, 52, 59)).apply {
                setOnClickListener { promptParentPin() }
            })
        }
    }

    private fun childrenArea(board: BoardState): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            board.children.forEach { child ->
                addView(childColumn(child, board), LinearLayout.LayoutParams(0, match(), 1f).apply {
                    setMargins(dp(7))
                })
            }
        }
    }

    private fun childColumn(childState: ChildBoardState, board: BoardState): LinearLayout {
        val accent = Color.parseColor(childState.child.color)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Color.WHITE, dp(8), Color.rgb(217, 214, 206))
            addView(childHeaderPanel(childState, accent))
            addView(actionButton("Prize Shelf", Color.rgb(49, 84, 93)).apply {
                setOnClickListener { showRewardsDialog(childState, board.rewards) }
            }.withTopMargin(8))
            val taskList = LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                childState.tasks.forEach { boardTask ->
                    val card = taskCard(boardTask, accent)
                    if (!boardTask.task.repeatable) {
                        card.setOnClickListener {
                            lifecycleScope.launch {
                                setTaskCount(boardTask.task.id, if (boardTask.completed) 0 else 1, LocalDateTime.now())
                                renderBoard(app.repository.boardState(LocalDateTime.now()))
                            }
                        }
                    }
                    addView(card.withTopMargin(12))
                }
            }
            addView(ScrollView(this@HomeActivity).apply {
                clipToPadding = false
                setPadding(0, 0, 0, dp(8))
                addView(taskList, FrameLayout.LayoutParams(match(), wrap()))
            }, LinearLayout.LayoutParams(match(), 0, 1f).apply { setMargins(0, dp(8), 0, 0) })
        }
    }

    private fun childHeaderPanel(childState: ChildBoardState, accent: Int): LinearLayout {
        val progress = childState.progress
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(childState.child.displayName, 30f, accent, Typeface.BOLD))
                addView(text("${progress.completed} of ${progress.total} quests", 18f, Color.rgb(56, 68, 78), Typeface.BOLD).withTopMargin(2))
                addView(ProgressBar(this@HomeActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = progress.total.coerceAtLeast(1)
                    this.progress = progress.completed
                }, LinearLayout.LayoutParams(match(), dp(16)).apply { setMargins(0, dp(7), 0, 0) })
                addView(pointChipRow(childState).withTopMargin(8))
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(PointPocketView(this@HomeActivity, childState.points.wallet, accent), LinearLayout.LayoutParams(dp(150), dp(112)).apply {
                setMargins(dp(14), 0, 0, 0)
            })
        }
    }

    private fun pointChipRow(childState: ChildBoardState): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pointChip("☀", childState.points.daily, "today", Color.rgb(255, 247, 218), Color.rgb(166, 111, 0)))
            addView(pointChip("★", childState.points.weekly, "week", Color.rgb(232, 248, 243), Color.rgb(31, 112, 92)).apply {
                layoutParams = LinearLayout.LayoutParams(wrap(), wrap()).apply { setMargins(dp(8), 0, 0, 0) }
            })
        }
    }

    private fun pointChip(icon: String, value: Int, label: String, fill: Int, foreground: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(fill, dp(8), foreground)
            addView(text(icon, 18f, foreground, Typeface.BOLD))
            addView(text("$value $label", 15f, foreground, Typeface.BOLD).apply {
                setPadding(dp(6), 0, 0, 0)
            })
        }
    }

    private fun changeRepeatableCount(boardTask: BoardTask, nextCount: Int) {
        lifecycleScope.launch {
            setTaskCount(boardTask.task.id, nextCount.coerceAtLeast(0), LocalDateTime.now())
            renderBoard(app.repository.boardState(LocalDateTime.now()))
        }
    }

    private fun taskCard(boardTask: BoardTask, accent: Int): LinearLayout {
        val task = boardTask.task
        val completed = boardTask.completed
        val foreground = if (completed) Color.WHITE else Color.rgb(18, 52, 59)
        val cardFill = if (completed) accent else Color.rgb(255, 252, 241)
        val cardStroke = if (completed) accent else Color.rgb(230, 206, 132)
        val earned = boardTask.count * task.pointValue
        val pointText = if (task.repeatable) {
            "${task.pointValue} each" + if (boardTask.count > 0) " · $earned earned" else ""
        } else {
            "${task.pointValue} point${if (task.pointValue == 1) "" else "s"}"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(112)
            setPadding(dp(16), dp(10), dp(14), dp(10))
            background = rounded(cardFill, dp(8), cardStroke)
            isClickable = !task.repeatable
            isFocusable = !task.repeatable
            addView(text(if (completed) "✓ ${task.visualCue}" else task.visualCue, 38f, foreground, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = rounded(if (completed) Color.argb(70, 255, 255, 255) else Color.WHITE, dp(8), if (completed) Color.TRANSPARENT else Color.rgb(242, 220, 154))
            }, LinearLayout.LayoutParams(dp(88), dp(82)))
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(task.title, 26f, foreground, Typeface.BOLD))
                addView(text("🪙 $pointText", 16f, foreground, Typeface.BOLD).apply { alpha = 0.86f })
            }, LinearLayout.LayoutParams(0, match(), 1f).apply { setMargins(dp(14), 0, dp(10), 0) })
            if (task.repeatable) {
                addView(repeatCounter(boardTask, accent, completed))
            }
        }
    }

    private fun repeatCounter(boardTask: BoardTask, accent: Int, completed: Boolean): LinearLayout {
        val buttonFill = if (completed) Color.WHITE else Color.rgb(18, 52, 59)
        val buttonText = if (completed) accent else Color.WHITE
        val countFill = if (completed) Color.argb(56, 255, 255, 255) else Color.WHITE
        val countText = if (completed) Color.WHITE else Color.rgb(18, 52, 59)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(counterButton("-", buttonFill, buttonText, boardTask.count > 0).apply {
                setOnClickListener {
                    if (boardTask.count > 0) changeRepeatableCount(boardTask, boardTask.count - 1)
                }
            })
            addView(text(boardTask.count.toString(), 30f, countText, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                background = rounded(countFill, dp(8), if (completed) Color.argb(90, 255, 255, 255) else Color.rgb(230, 206, 132))
            }, LinearLayout.LayoutParams(dp(64), dp(58)).apply { setMargins(dp(6), 0, dp(6), 0) })
            addView(counterButton("+", buttonFill, buttonText, true).apply {
                setOnClickListener { changeRepeatableCount(boardTask, boardTask.count + 1) }
            })
        }
    }

    private fun counterButton(label: String, fill: Int, foreground: Int, enabled: Boolean): TextView {
        return text(label, 30f, foreground, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            minWidth = dp(54)
            minHeight = dp(54)
            alpha = if (enabled) 1f else 0.42f
            isEnabled = enabled
            isClickable = enabled
            isFocusable = enabled
            background = rounded(fill, dp(8), fill)
        }
    }

    private suspend fun setTaskCount(taskId: String, count: Int, now: LocalDateTime) {
        if (runtimePolicy.syncsWithHub) {
            app.syncRepository.setTaskCompletionCount(taskId, count, now)
        } else {
            app.repository.setTaskCompletionCount(taskId, count, now)
        }
    }

    private fun showRewardsDialog(childState: ChildBoardState, rewards: List<RewardConfig>) {
        if (rewards.isEmpty()) {
            Toast.makeText(this, "No rewards yet.", Toast.LENGTH_SHORT).show()
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        container.addView(text("Stash: ${childState.points.wallet} points", 20f, Color.rgb(18, 52, 59), Typeface.BOLD))
        rewards.forEach { reward ->
            val affordable = childState.points.wallet >= reward.pointCost
            container.addView(actionButton("🎁 ${reward.title} · ${reward.pointCost} pts", if (affordable) Color.rgb(31, 138, 112) else Color.rgb(145, 151, 154)).apply {
                isEnabled = affordable
                alpha = if (affordable) 1f else 0.55f
                setOnClickListener {
                    if (affordable) confirmRewardRedemption(childState, reward)
                }
            }.withTopMargin(10))
            reward.note?.takeIf { it.isNotBlank() }?.let { note ->
                container.addView(text(note, 15f, Color.rgb(73, 85, 96)).withTopMargin(2))
            }
        }
        AlertDialog.Builder(this)
            .setTitle("${childState.child.displayName}'s Prize Shelf")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmRewardRedemption(childState: ChildBoardState, reward: RewardConfig) {
        AlertDialog.Builder(this)
            .setTitle(reward.title)
            .setMessage("Trade ${reward.pointCost} points for this prize?")
            .setPositiveButton("Trade") { _, _ ->
                lifecycleScope.launch {
                    val applied = if (runtimePolicy.syncsWithHub) {
                        app.syncRepository.redeemReward(childState.child.id, reward.id, LocalDateTime.now())
                    } else {
                        app.repository.redeemReward(childState.child.id, reward.id, LocalDateTime.now())
                    }
                    Toast.makeText(
                        this@HomeActivity,
                        if (applied) "Prize redeemed." else "Not enough points.",
                        Toast.LENGTH_SHORT,
                    ).show()
                    renderBoard(app.repository.boardState(LocalDateTime.now()))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptParentPin() {
        val input = EditText(this).pinField("Parent PIN")
        AlertDialog.Builder(this)
            .setTitle("Parent")
            .setView(input)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        lifecycleScope.launch {
                            val pin = input.text.toString()
                            if (runtimePolicy.parentActionsUseLocalRepository) {
                                if (app.repository.verifyParentPin(pin)) {
                                    dialog.dismiss()
                                    showParentSettings(parentToken = null)
                                } else {
                                    Toast.makeText(this@HomeActivity, "Invalid PIN", Toast.LENGTH_SHORT).show()
                                }
                                return@launch
                            }

                            val token = try { app.syncRepository.loginParent(pin) } catch (_: Exception) { null }
                            if (token != null) {
                                dialog.dismiss()
                                showParentSettings(token)
                            } else {
                                Toast.makeText(this@HomeActivity, "Invalid PIN", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .show()
    }

    private fun showParentSettings(parentToken: String?) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val policy = runtimePolicy
        container.addView(text("Mode", 16f, Color.rgb(73, 85, 96), Typeface.BOLD))
        container.addView(text(if (policy.mode == PortalMode.StandalonePortal) "Standalone Portal" else "Mac Hub Client", 20f, Color.rgb(18, 52, 59), Typeface.BOLD).withTopMargin(4))
        if (policy.mode == PortalMode.StandalonePortal) {
            container.addView(text("Admin URL", 16f, Color.rgb(73, 85, 96), Typeface.BOLD).withTopMargin(12))
            container.addView(text(localAdminUrl(), 20f, Color.rgb(18, 52, 59), Typeface.BOLD).withTopMargin(4))
            container.addView(actionButton("Connect to Mac Hub", Color.rgb(49, 84, 93)).apply {
                setOnClickListener { showConnectHubDialog() }
            }.withTopMargin(12))
        } else {
            container.addView(text("Hub URL", 16f, Color.rgb(73, 85, 96), Typeface.BOLD).withTopMargin(12))
            container.addView(text(app.syncRepository.hubUrl, 20f, Color.rgb(18, 52, 59), Typeface.BOLD).withTopMargin(4))
            container.addView(text(syncStatusText(), 16f, Color.rgb(73, 85, 96)).withTopMargin(6))
            lifecycleScope.launch {
                container.addView(text("Queued taps: ${app.syncRepository.pendingCompletionCount()}", 16f, Color.rgb(73, 85, 96)).withTopMargin(4))
            }
            container.addView(actionButton("Change Hub URL", Color.rgb(49, 84, 93)).apply {
                setOnClickListener { showHubUrlDialog() }
            }.withTopMargin(12))
            container.addView(actionButton("Switch to Standalone", Color.rgb(91, 104, 113)).apply {
                setOnClickListener {
                    app.syncRepository.switchToStandalone()
                    applyRuntimePolicy()
                    startBoardLoop()
                }
            }.withTopMargin(8))
        }
        container.addView(actionButton("Manual Reset Today", Color.rgb(180, 35, 24)).apply {
            setOnClickListener {
                lifecycleScope.launch manualReset@{
                    if (policy.parentActionsUseLocalRepository) {
                        app.repository.resetDay(LocalDateTime.now())
                    } else if (parentToken != null) {
                        app.syncRepository.resetDay(parentToken)
                    } else {
                        Toast.makeText(this@HomeActivity, "Hub login required while online.", Toast.LENGTH_SHORT).show()
                        return@manualReset
                    }
                    startBoardLoop()
                }
            }
        }.withTopMargin(18))
        container.addView(text("Show Window", 16f, Color.rgb(73, 85, 96), Typeface.BOLD).withTopMargin(16))
        lifecycleScope.launch {
            app.repository.snapshot().windows.forEach { window ->
                container.addView(actionButton(window.name, Color.rgb(31, 138, 112)).apply {
                    setOnClickListener {
                        lifecycleScope.launch windowOverride@{
                            if (policy.parentActionsUseLocalRepository) {
                                app.repository.setManualWindowOverride(window.id, LocalDateTime.now())
                            } else if (parentToken != null) {
                                app.syncRepository.setManualWindowOverride(parentToken, window.id)
                            } else {
                                Toast.makeText(this@HomeActivity, "Hub login required while online.", Toast.LENGTH_SHORT).show()
                                return@windowOverride
                            }
                            startBoardLoop()
                        }
                    }
                }.withTopMargin(8))
            }
        }
        container.addView(actionButton("Clear Window Override", Color.rgb(18, 52, 59)).apply {
            setOnClickListener {
                lifecycleScope.launch clearOverride@{
                    if (policy.parentActionsUseLocalRepository) {
                        app.repository.clearManualWindowOverride()
                    } else if (parentToken != null) {
                        app.syncRepository.clearManualWindowOverride(parentToken)
                    } else {
                        Toast.makeText(this@HomeActivity, "Hub login required while online.", Toast.LENGTH_SHORT).show()
                        return@clearOverride
                    }
                    startBoardLoop()
                }
            }
        }.withTopMargin(8))
        container.addView(text("Maintenance", 16f, Color.rgb(73, 85, 96), Typeface.BOLD).withTopMargin(16))
        listOf(
            "KISS Launcher" to "fr.neamar.kiss",
            "F-Droid" to "org.fdroid.fdroid",
            "Fennec" to "org.mozilla.fennec_fdroid",
        ).forEach { (label, pkg) ->
            container.addView(actionButton(label, Color.rgb(91, 104, 113)).apply {
                setOnClickListener { launchPackage(pkg) }
            }.withTopMargin(8))
        }

        AlertDialog.Builder(this)
            .setTitle("Parent Settings")
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showConnectHubDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val url = EditText(this).apply {
            hint = "http://mac-mini.local:8080"
            setText(app.syncRepository.hubUrl)
            textSize = 18f
            setSingleLine()
            minWidth = dp(420)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pin = EditText(this).pinField("Hub Parent PIN")
        container.addView(text("Hub URL", 14f, Color.rgb(73, 85, 96), Typeface.BOLD))
        container.addView(url.withTopMargin(4))
        container.addView(text("Parent PIN", 14f, Color.rgb(73, 85, 96), Typeface.BOLD).withTopMargin(12))
        container.addView(pin.withTopMargin(4))

        AlertDialog.Builder(this)
            .setTitle("Connect to Mac Hub")
            .setMessage("Choose whether this Portal should copy its current setup to the hub or replace its local cache with the hub setup.")
            .setView(container)
            .setPositiveButton("Use Hub Data", null)
            .setNeutralButton("Seed Hub From Portal", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        connectToHub(dialog, url.text.toString(), pin.text.toString(), HubMigrationDirection.UseHubData)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        connectToHub(dialog, url.text.toString(), pin.text.toString(), HubMigrationDirection.SeedHubFromPortal)
                    }
                }
            }
            .show()
    }

    private fun connectToHub(dialog: AlertDialog, url: String, pin: String, migration: HubMigrationDirection) {
        lifecycleScope.launch {
            try {
                app.syncRepository.connectToHub(url, pin, migration)
                applyRuntimePolicy()
                dialog.dismiss()
                startBoardLoop()
                Toast.makeText(this@HomeActivity, "Connected to hub.", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                Toast.makeText(this@HomeActivity, error.message ?: "Hub connection failed.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showHubUrlDialog() {
        val input = EditText(this).apply {
            setText(app.syncRepository.hubUrl)
            textSize = 18f
            setSingleLine()
            minWidth = dp(420)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("Hub URL")
            .setView(input)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        lifecycleScope.launch {
                            app.syncRepository.updateHubUrl(input.text.toString())
                            Toast.makeText(this@HomeActivity, syncStatusText(), Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            startBoardLoop()
                        }
                    }
                }
            }
            .show()
    }

    private fun showCelebration(root: FrameLayout) {
        val overlay = CelebrationOverlayView(this).apply {
            setOnClickListener {
                (parent as? FrameLayout)?.removeView(this)
            }
        }
        root.addView(overlay, FrameLayout.LayoutParams(match(), match()))
        playCelebrationChime()
        lifecycleScope.launch {
            delay(4_500)
            if (overlay.parent === root) {
                root.removeView(overlay)
            }
        }
    }

    private fun playCelebrationChime() {
        val tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (_: RuntimeException) {
            return
        }
        if (!tone.startTone(ToneGenerator.TONE_PROP_ACK, 220)) {
            tone.release()
            return
        }
        lifecycleScope.launch {
            delay(320)
            tone.release()
        }
    }

    private class PointPocketView(
        context: Context,
        private val points: Int,
        private val accent: Int,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 247, 218)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val widthF = width.toFloat()
            val heightF = height.toFloat()
            if (widthF <= 0f || heightF <= 0f) return

            val coinColor = Color.rgb(255, 210, 90)
            val coinStroke = Color.rgb(178, 122, 13)
            val darkAccent = shade(accent, 0.72f)
            val lightAccent = shade(accent, 1.16f)

            paint.style = Paint.Style.FILL
            paint.color = coinColor
            canvas.drawCircle(widthF * 0.28f, heightF * 0.27f, heightF * 0.16f, paint)
            canvas.drawCircle(widthF * 0.48f, heightF * 0.21f, heightF * 0.18f, paint)
            canvas.drawCircle(widthF * 0.69f, heightF * 0.27f, heightF * 0.15f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = heightF * 0.026f
            paint.color = coinStroke
            canvas.drawCircle(widthF * 0.28f, heightF * 0.27f, heightF * 0.16f, paint)
            canvas.drawCircle(widthF * 0.48f, heightF * 0.21f, heightF * 0.18f, paint)
            canvas.drawCircle(widthF * 0.69f, heightF * 0.27f, heightF * 0.15f, paint)

            paint.style = Paint.Style.FILL
            rect.set(widthF * 0.08f, heightF * 0.31f, widthF * 0.92f, heightF * 0.92f)
            paint.color = accent
            canvas.drawRoundRect(rect, heightF * 0.12f, heightF * 0.12f, paint)

            rect.set(widthF * 0.14f, heightF * 0.26f, widthF * 0.86f, heightF * 0.58f)
            paint.color = lightAccent
            canvas.drawRoundRect(rect, heightF * 0.11f, heightF * 0.11f, paint)

            paint.color = darkAccent
            canvas.drawCircle(widthF * 0.5f, heightF * 0.48f, heightF * 0.055f, paint)

            val pointsText = points.toString()
            numberPaint.textSize = (heightF * if (pointsText.length <= 3) 0.28f else 0.22f).coerceIn(28f, 48f)
            labelPaint.textSize = (heightF * 0.105f).coerceIn(11f, 16f)
            canvas.drawText(pointsText, widthF * 0.5f, heightF * 0.73f, numberPaint)
            canvas.drawText("POINTS", widthF * 0.5f, heightF * 0.86f, labelPaint)
        }

        private fun shade(color: Int, factor: Float): Int {
            return Color.rgb(
                (Color.red(color) * factor).toInt().coerceIn(0, 255),
                (Color.green(color) * factor).toInt().coerceIn(0, 255),
                (Color.blue(color) * factor).toInt().coerceIn(0, 255),
            )
        }
    }

    private class CelebrationOverlayView(context: Context) : View(context) {
        private val colors = intArrayOf(
            Color.rgb(255, 209, 102),
            Color.rgb(239, 71, 111),
            Color.rgb(6, 214, 160),
            Color.rgb(17, 138, 178),
            Color.rgb(255, 255, 255),
        )
        private val random = Random(SystemClock.uptimeMillis())
        private val particles = List(150) {
            CelebrationParticle(
                x = random.nextFloat(),
                offset = random.nextFloat(),
                durationMs = random.nextInt(2_200, 3_900).toFloat(),
                sway = random.nextInt(24, 92).toFloat(),
                sizeDp = random.nextInt(7, 18).toFloat(),
                rotation = random.nextInt(0, 360).toFloat(),
                color = colors[random.nextInt(colors.size)],
                round = random.nextBoolean(),
            )
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 245, 220)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val startTime = SystemClock.uptimeMillis()
        private val rect = RectF()

        init {
            isClickable = true
            isFocusable = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val widthF = width.toFloat()
            val heightF = height.toFloat()
            val elapsed = (SystemClock.uptimeMillis() - startTime).coerceAtLeast(0).toFloat()

            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(18, 52, 59)
            paint.alpha = 215
            canvas.drawRect(0f, 0f, widthF, heightF, paint)
            paint.alpha = 255

            drawFireworks(canvas, widthF, heightF, elapsed)
            drawConfetti(canvas, widthF, heightF, elapsed)

            titlePaint.textSize = (heightF.coerceAtMost(widthF) * 0.105f).coerceIn(42f, 86f)
            subtitlePaint.textSize = (heightF.coerceAtMost(widthF) * 0.043f).coerceIn(22f, 36f)
            canvas.drawText("Great job!", widthF / 2f, heightF * 0.46f, titlePaint)
            canvas.drawText("Routine complete", widthF / 2f, heightF * 0.54f, subtitlePaint)

            postInvalidateOnAnimation()
        }

        private fun drawConfetti(canvas: Canvas, widthF: Float, heightF: Float, elapsed: Float) {
            val density = resources.displayMetrics.density
            particles.forEach { particle ->
                val phase = ((elapsed / particle.durationMs) + particle.offset) % 1f
                val x = particle.x * widthF + sin((phase * 2f * PI) + particle.offset).toFloat() * particle.sway
                val y = -heightF * 0.18f + phase * heightF * 1.35f
                val size = particle.sizeDp * density

                paint.style = Paint.Style.FILL
                paint.color = particle.color
                paint.alpha = (100 + ((1f - phase) * 155f)).toInt().coerceIn(90, 255)

                canvas.save()
                canvas.rotate(particle.rotation + phase * 360f, x, y)
                if (particle.round) {
                    canvas.drawCircle(x, y, size * 0.45f, paint)
                } else {
                    rect.set(x - size * 0.55f, y - size * 0.28f, x + size * 0.55f, y + size * 0.28f)
                    canvas.drawRoundRect(rect, size * 0.16f, size * 0.16f, paint)
                }
                canvas.restore()
            }
            paint.alpha = 255
        }

        private fun drawFireworks(canvas: Canvas, widthF: Float, heightF: Float, elapsed: Float) {
            val centers = listOf(
                0.22f to 0.25f,
                0.78f to 0.27f,
                0.34f to 0.72f,
                0.70f to 0.70f,
            )
            centers.forEachIndexed { index, center ->
                val phase = ((elapsed / 1_500f) + index * 0.23f) % 1f
                val radius = (28f + widthF.coerceAtMost(heightF) * 0.13f * phase)
                val alpha = ((1f - phase) * 190f).toInt().coerceIn(0, 190)
                val cx = widthF * center.first
                val cy = heightF * center.second

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.color = colors[index % colors.size]
                paint.alpha = alpha
                canvas.drawCircle(cx, cy, radius, paint)

                paint.strokeWidth = 3f
                repeat(10) { ray ->
                    val angle = ((ray * 36f) + phase * 70f) * (PI.toFloat() / 180f)
                    val inner = radius * 0.58f
                    val outer = radius * 0.86f
                    canvas.drawLine(
                        cx + kotlin.math.cos(angle) * inner,
                        cy + kotlin.math.sin(angle) * inner,
                        cx + kotlin.math.cos(angle) * outer,
                        cy + kotlin.math.sin(angle) * outer,
                        paint,
                    )
                }
            }
            paint.alpha = 255
        }
    }

    private data class CelebrationParticle(
        val x: Float,
        val offset: Float,
        val durationMs: Float,
        val sway: Float,
        val sizeDp: Float,
        val rotation: Float,
        val color: Int,
        val round: Boolean,
    )

    private fun launchPackage(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Toast.makeText(this, "App is not installed.", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun syncStatusText(): String {
        return when (val status = app.syncRepository.lastStatus) {
            SyncStatus.Standalone -> "Sync: standalone on this Portal"
            SyncStatus.Unknown -> "Sync: not checked yet"
            is SyncStatus.Online -> "Sync: online"
            is SyncStatus.Offline -> "Sync: offline - ${status.reason}"
        }
    }

    private fun applyRuntimePolicy() {
        if (runtimePolicy.startsLocalAdminServer) {
            app.startAdminServer()
        } else {
            app.stopAdminServer()
        }
    }

    private fun localAdminUrl(): String = "http://${NetworkAddress.localIpv4Address() ?: "127.0.0.1"}:8080"

    private fun text(value: String, sizeSp: Float, color: Int, style: Int = Typeface.NORMAL): TextView {
        return TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            typeface = Typeface.DEFAULT_BOLD.takeIf { style == Typeface.BOLD } ?: Typeface.DEFAULT
            includeFontPadding = true
        }
    }

    private fun actionButton(label: String, color: Int): TextView {
        return text(label, 18f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(color, dp(8), color)
            isClickable = true
            isFocusable = true
        }
    }

    private fun EditText.pinField(hintText: String): EditText = apply {
        hint = hintText
        textSize = 22f
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine()
        minWidth = dp(320)
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun <T : View> T.withTopMargin(top: Int): T {
        layoutParams = LinearLayout.LayoutParams(match(), wrap()).apply {
            setMargins(0, top, 0, 0)
        }
        return this
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun match(): Int = LinearLayout.LayoutParams.MATCH_PARENT
    private fun wrap(): Int = LinearLayout.LayoutParams.WRAP_CONTENT
}
