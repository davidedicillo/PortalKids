package com.davidedicillo.portalroutine

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setMargins
import androidx.lifecycle.lifecycleScope
import com.davidedicillo.portalroutine.admin.NetworkAddress
import com.davidedicillo.portalroutine.data.BoardState
import com.davidedicillo.portalroutine.data.ChildBoardState
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
            showCelebration()
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
                addView(childColumn(child), LinearLayout.LayoutParams(0, match(), 1f).apply {
                    setMargins(dp(7))
                })
            }
        }
    }

    private fun childColumn(childState: ChildBoardState): LinearLayout {
        val accent = Color.parseColor(childState.child.color)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(Color.WHITE, dp(8), Color.rgb(217, 214, 206))
            addView(text(childState.child.displayName, 30f, accent, Typeface.BOLD))
            addView(progressRow(childState).withTopMargin(8))
            addView(LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                childState.tasks.forEach { boardTask ->
                    addView(taskCard(boardTask.task.visualCue, boardTask.task.title, boardTask.completed, accent).apply {
                        setOnClickListener {
                            lifecycleScope.launch {
                                val now = LocalDateTime.now()
                                if (runtimePolicy.syncsWithHub) {
                                    app.syncRepository.setTaskCompletion(
                                        taskId = boardTask.task.id,
                                        completed = !boardTask.completed,
                                        now = now,
                                    )
                                } else {
                                    app.repository.setTaskCompletion(
                                        taskId = boardTask.task.id,
                                        completed = !boardTask.completed,
                                        now = now,
                                    )
                                }
                                renderBoard(app.repository.boardState(LocalDateTime.now()))
                            }
                        }
                    }.withTopMargin(12))
                }
            }.withTopMargin(8), LinearLayout.LayoutParams(match(), 0, 1f))
        }
    }

    private fun progressRow(childState: ChildBoardState): LinearLayout {
        val progress = childState.progress
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("${progress.completed} of ${progress.total} done", 18f, Color.rgb(56, 68, 78), Typeface.BOLD))
            addView(ProgressBar(this@HomeActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = progress.total.coerceAtLeast(1)
                this.progress = progress.completed
            }, LinearLayout.LayoutParams(match(), dp(18)).apply { setMargins(0, dp(6), 0, 0) })
        }
    }

    private fun taskCard(visualCue: String, title: String, completed: Boolean, accent: Int): LinearLayout {
        val foreground = if (completed) Color.WHITE else Color.rgb(18, 52, 59)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(104)
            setPadding(dp(18), 0, dp(18), 0)
            background = if (completed) rounded(accent, dp(8), accent) else rounded(Color.rgb(250, 250, 247), dp(8), Color.rgb(198, 202, 205))
            addView(text(if (completed) "✓ $visualCue" else visualCue, 38f, foreground, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(96), match()))
            addView(text(title, 26f, foreground, Typeface.BOLD).apply {
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, match(), 1f))
        }
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

    private fun showCelebration() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(text("Routine complete!", 42f, Color.WHITE, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(36), dp(36), dp(36))
            background = rounded(Color.rgb(31, 138, 112), dp(8), Color.rgb(31, 138, 112))
            setOnClickListener { dialog.dismiss() }
        })
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        lifecycleScope.launch {
            delay(3500)
            if (dialog.isShowing) dialog.dismiss()
        }
    }

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
