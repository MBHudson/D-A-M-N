package com.damn.app.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.damn.app.R
import com.damn.app.databinding.FragmentDashboardBinding
import com.damn.app.service.ServerService
import com.damn.app.util.Prefs
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private var isFullscreen = false
    private var toneGen: ToneGenerator? = null
    private var beepJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.fullscreenBtn.setOnClickListener { toggleFullscreen() }
        binding.speedBtn.setOnClickListener { runSpeedTest() }
        if (ServerService.instance?.isRunning() == true) {
            DashboardMetrics.start(requireContext().applicationContext)
        }
        observeMetrics()
        restoreSpeedMetrics()
        startBeepMonitor()
        // auto-run speed test once on first entry after service connected correctly
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // wait for metrics to have active node, check every 1s for up to 15s
                var waited = 0
                while (waited < 15000 && isActive) {
                    if (DashboardMetrics.shouldAutoRunSpeedTest()) {
                        DashboardMetrics.markAutoSpeedTestDone()
                        runSpeedTest()
                        break
                    }
                    delay(1000)
                    waited += 1000
                }
            }
        }
    }

    private fun restoreSpeedMetrics() {
        val down = DashboardMetrics.speedDown.value
        val up = DashboardMetrics.speedUp.value
        val hint = DashboardMetrics.speedHint.value
        val prog = DashboardMetrics.speedProgress.value
        if (down != "—") binding.downVal.text = down
        if (up != "—") binding.upVal.text = up
        binding.speedHint.text = hint
        binding.downBar.progress = prog.first
        binding.upBar.progress = prog.second
        if (prog.first == 100) {
            binding.speedBtn.text = "Run Speed Test"
            binding.speedBtn.isEnabled = true
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val win = requireActivity().window
        val controller = WindowCompat.getInsetsController(win, win.decorView)
        if (isFullscreen) {
            controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    private fun observeMetrics() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { DashboardMetrics.nodes.collect { renderRouting(it) } }
                launch { DashboardMetrics.isRunningFlow.collect { renderRouting(DashboardMetrics.nodes.value) } }
                launch { DashboardMetrics.pingHist.collect { updatePingChart(it) } }
                launch { DashboardMetrics.traffic.collect { (ins, outs) ->
                    if (_binding != null) binding.trafficSpark.setData(ins, outs)
                } }
                launch { DashboardMetrics.inOut.collect { (i,o) ->
                    if (_binding != null) { binding.inVal.text = i; binding.outVal.text = o }
                } }
                launch { DashboardMetrics.tps.collect { if (_binding != null) binding.tpsVal.text = "$it req/s" } }
                launch { DashboardMetrics.ips.collect { renderRouting(DashboardMetrics.nodes.value) } }
                launch { DashboardMetrics.speedDown.collect { if (_binding != null && it != "—") binding.downVal.text = it } }
                launch { DashboardMetrics.speedUp.collect { if (_binding != null && it != "—") binding.upVal.text = it } }
                launch { DashboardMetrics.speedHint.collect { if (_binding != null) binding.speedHint.text = it } }
                launch { DashboardMetrics.speedProgress.collect { (d,u) ->
                    if (_binding != null) { binding.downBar.progress = d; binding.upBar.progress = u }
                } }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            renderRouting(DashboardMetrics.nodes.value)
            updatePingChart(DashboardMetrics.pingHist.value)
            restoreSpeedMetrics()
        }
    }

    private fun renderRouting(nodes: Map<String, DashboardMetrics.NodeInfo>) {
        if (_binding == null || !isAdded) return
        val ctx = context ?: return
        val topRow = binding.topRow
        val midRow = binding.midRow
        val bottomRow = binding.bottomRow
        topRow.removeAllViews()
        midRow.removeAllViews()
        bottomRow.removeAllViews()
        if (nodes.isEmpty()) return

        val port = try { Prefs.getPort(ctx) } catch (_: Exception) { 8080 }
        val hostLabel = try { Prefs.getHostLabel(ctx).ifEmpty { "www" } } catch (_: Exception) { "www" }
        val customDns = try { Prefs.getCustomDns(ctx).takeIf { it.isNotBlank() } ?: "127.0.0.1 • dns" } catch (_: Exception) { "127.0.0.1 • dns" }
        val youIp = DashboardMetrics.ips.value.first
        val worldIp = DashboardMetrics.ips.value.second
        val isRunning = ServerService.instance?.isRunning() == true

        // Prepare purple overrides for firewall & NAT
        val fwRaw = nodes["firewall"] ?: return
        val natRaw = nodes["nat"] ?: return
        val fwNode = fwRaw.copy(color = "purple", status = "online", ip = "FIREWALL", enabled = true)
        val natNode = natRaw.copy(color = "purple", status = "Active", ping = if (natRaw.ping > 0) natRaw.ping else 42, enabled = true, ip = natRaw.ip)

        if (!isRunning) {
            // --- STANDBY VIEW: internet -> firewall -> nat -> you ---
            // Top: Internet
            topRow.addView(createEndpoint(false, worldIp))

            // Middle: Firewall
            midRow.addView(createNode("firewall", "Firewall / Carrier NAT", "FIREWALL", fwNode, isFirewall = true, iconType = "damn", hidePing = false, isRunning = false))

            // Bottom: NAT -> YOU
            bottomRow.addView(createNode("nat", "NAT / UPNP", natNode.ip, natNode, isFirewall = false, iconType = "router", hidePing = true, isRunning = false))
            bottomRow.addView(createAnimatedConnector("live", false))
            bottomRow.addView(createEndpoint(true, youIp))
        } else {
            // --- ACTIVE VIEW: Full Inbound Stack ---
            val hostN = nodes["host"] ?: return
            val engN = nodes["engine"] ?: return
            val dnsN = nodes["dns"] ?: return
            val phpEnabled = Prefs.isPhpEnabled(ctx)
            val listenerEnabled = Prefs.isListenerEnabled(ctx)

            // Top: INTERNET -> TUNNELS
            topRow.addView(createEndpoint(false, worldIp))
            val tunnels = listOf("tor" to nodes["tor"], "ngrok" to nodes["ngrok"], "cf" to nodes["cf"]).filter { it.second?.enabled == true }
            tunnels.forEach { (k, n) ->
                topRow.addView(createAnimatedConnector("live", true))
                topRow.addView(createNode(k, if (k == "tor") "Tor Onion" else k.uppercase(), n?.ip ?: "—", n!!, isFirewall = false, iconType = "default", hidePing = false, isRunning = true))
            }

            // Middle: Firewall
            midRow.addView(createNode("firewall", "Firewall / Carrier NAT", "FIREWALL", fwNode, isFirewall = true, iconType = "damn", hidePing = false, isRunning = true))

            // Bottom: NAT -> (PHP ENGINE OR LISTENER) -> HOST -> YOU
            bottomRow.addView(createNode("nat", "NAT / UPNP", natNode.ip, natNode, isFirewall = false, iconType = "router", hidePing = true, isRunning = true))
            bottomRow.addView(createAnimatedConnector("live", true))
            
            if (phpEnabled) {
                bottomRow.addView(createNode("engine", "PHP Engine", "localhost:$port", engN, isFirewall = false, iconType = "default", hidePing = false, isRunning = true))
                bottomRow.addView(createAnimatedConnector(hostN.color, true))
                bottomRow.addView(createNode("host", "HOST FILES", hostN.ip.ifEmpty { hostLabel }, hostN, isFirewall = false, iconType = "phone", hidePing = false, isRunning = true))
            } else if (listenerEnabled) {
                val proxyHost = Prefs.getProxyHost(ctx).ifEmpty { "127.0.0.1" }
                val proxyPort = Prefs.getProxyPort(ctx)
                val proxyNode = engN.copy(ip = "$proxyHost:$proxyPort")
                bottomRow.addView(createNode("engine", "Listener", "$proxyHost:$proxyPort", proxyNode, isFirewall = false, iconType = "router", hidePing = false, isRunning = true))
            }
            
            bottomRow.addView(createAnimatedConnector("live", true))
            bottomRow.addView(createEndpoint(true, youIp))
        }

        binding.sPathView.setAnimating(isRunning)
        binding.routingContainer.post { updateSPath() }
    }

    private fun updateSPath() {
        if (_binding == null) return
        val container = binding.routingContainer
        val topRow = binding.topRow
        val midRow = binding.midRow
        val bottomRow = binding.bottomRow
        val sView = binding.sPathView
        if (topRow.childCount == 0 || midRow.childCount == 0 || bottomRow.childCount == 0) return
        val lastTop = topRow.getChildAt(topRow.childCount - 1) ?: return
        val fwView = midRow.getChildAt(0) ?: return
        val firstBottom = bottomRow.getChildAt(0) ?: return
        val containerLoc = IntArray(2).also { container.getLocationOnScreen(it) }
        val lastLoc = IntArray(2).also { lastTop.getLocationOnScreen(it) }
        val fwLoc = IntArray(2).also { fwView.getLocationOnScreen(it) }
        val firstLoc = IntArray(2).also { firstBottom.getLocationOnScreen(it) }
        val startX = (lastLoc[0] + lastTop.width - containerLoc[0]).toFloat()
        val startY = (lastLoc[1] + lastTop.height / 2f - containerLoc[1]).toFloat()
        val fwTopX = (fwLoc[0] + fwView.width / 2f - containerLoc[0]).toFloat()
        val fwTopY = (fwLoc[1] - containerLoc[1]).toFloat()
        val fwBottomX = fwTopX
        val fwBottomY = (fwLoc[1] + fwView.height - containerLoc[1]).toFloat()
        val endX = (firstLoc[0] - containerLoc[0]).toFloat()
        val endY = (firstLoc[1] + firstBottom.height / 2f - containerLoc[1]).toFloat()
        sView.setFirewallColor("purple")
        sView.updatePath(startX, startY, fwTopX, fwTopY, fwBottomX, fwBottomY, endX, endY)
    }

    private fun createNode(key: String, title: String, sub: String, info: DashboardMetrics.NodeInfo, isFirewall: Boolean = false, iconType: String = "default", hidePing: Boolean = false, isRunning: Boolean = true): View {
        val ctx = context ?: return View(requireContext())
        // sizing: firewall 20% smaller than previous 88 -> 70, others 10% smaller 88->79, padding/icon/text scaled accordingly
        val isFw = isFirewall
        val cardWidth = if (isFw) 70 else 79
        val cardRadius = if (isFw) 6f else 9f
        val padDp = if (isFw) 5 else 5
        val iconW = if (isFw) 34 else 38
        val iconH = if (isFw) 24 else 27
        val card = MaterialCardView(ctx).apply {
            radius = cardRadius * resources.displayMetrics.density
            cardElevation = 5f
            val pad = (padDp * resources.displayMetrics.density).toInt()
            setContentPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams((cardWidth * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = (5 * resources.displayMetrics.density).toInt()
            }
            strokeWidth = (2 * resources.displayMetrics.density).toInt()
            setCardBackgroundColor(Color.parseColor("#151E33"))
            val strokeCol = when (info.color) {
                "purple" -> Color.parseColor("#A78BFA")
                "green" -> Color.parseColor("#22C55E")
                "yellow" -> Color.parseColor("#F59E0B")
                "red" -> Color.parseColor("#EF4444")
                else -> Color.parseColor("#1E293B")
            }
            strokeColor = strokeCol
            alpha = if (!info.enabled) 0.45f else 1f

            setOnClickListener {
                DashboardMetrics.triggerManualPing(key)
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                }.start()
            }

            // add subtle glitch background for firewall
            if (isFirewall) {
                // fire distressed animation via background tint pulse - handled via animator below
            }
        }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }

        // Status dot — hide for NAT as requested (remove indicator)
        if (!hidePing) {
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((6 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt()).apply { gravity = Gravity.END }
                background = ContextCompat.getDrawable(ctx, R.drawable.status_circle)
                backgroundTintList = when (info.status.lowercase()) {
                    "online", "active" -> ContextCompat.getColorStateList(ctx, R.color.damn_success)
                    "offline" -> ContextCompat.getColorStateList(ctx, android.R.color.holo_red_dark)
                    else -> ContextCompat.getColorStateList(ctx, R.color.damn_warn)
                }
            }
            val dotContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(dot)
            }
            inner.addView(dotContainer)
        }

        // Icon selection
        val iconRes = when (iconType) {
            "phone" -> R.drawable.ic_phone
            "router" -> R.drawable.ic_router
            "damn" -> R.drawable.ic_damn_logo
            else -> R.drawable.ic_home
        }
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((iconW * resources.displayMetrics.density).toInt(), (iconH * resources.displayMetrics.density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (3 * resources.displayMetrics.density).toInt()
            }
            setImageResource(iconRes)
            val col = when (info.color) {
                "purple" -> Color.parseColor("#A78BFA")
                "green" -> Color.parseColor("#22C55E")
                "yellow" -> Color.parseColor("#F59E0B")
                "red" -> Color.parseColor("#EF4444")
                else -> Color.parseColor("#38BDF8")
            }
            setColorFilter(col)
            alpha = if (!info.enabled) 0.5f else 1f
        }
        inner.addView(icon)

        // icon animations
        if (isRunning) {
            when (iconType) {
                "router", "phone" -> {
                    // subtle pulse / routing animation
                    val pulse = ObjectAnimator.ofFloat(icon, "alpha", 1f, 0.6f, 1f).apply {
                        duration = 1100
                        repeatCount = ValueAnimator.INFINITE
                        interpolator = LinearInterpolator()
                    }
                    pulse.start()
                    // also slight scale for router to mimic activity
                    if (iconType == "router") {
                        ValueAnimator.ofFloat(1f, 1.06f, 1f).apply {
                            duration = 900
                            repeatCount = ValueAnimator.INFINITE
                            addUpdateListener { v ->
                                val s = v.animatedValue as Float
                                icon.scaleX = s; icon.scaleY = s
                            }
                            start()
                        }
                    }
                }
                "damn" -> {
                    // glitch / fire distressed: translation + alpha flicker + tint shift
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 180
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        addUpdateListener {
                            val r = (Math.random() * 4 - 2).toFloat()
                            icon.translationX = r
                            icon.translationY = (Math.random() * 2 - 1).toFloat()
                            icon.alpha = 0.85f + Math.random().toFloat() * 0.15f
                        }
                        start()
                    }
                    // fire glow behind card
                    val fireBg = View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (2 * resources.displayMetrics.density).toInt())
                        setBackgroundColor(Color.parseColor("#EF4444"))
                        alpha = 0.18f
                    }
                    // add fire view behind icon? We'll add below icon via post
                    inner.addView(fireBg, 1) // after dotContainer, before icon? Actually icon already added, insert fire
                    // Animate fire alpha
                    ValueAnimator.ofFloat(0.12f, 0.28f, 0.12f).apply {
                        duration = 500
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener { v -> fireBg.alpha = v.animatedValue as Float }
                        start()
                    }
                    // Add glitch container background pulse for card stroke
                    ValueAnimator.ofArgb(Color.parseColor("#A78BFA"), Color.parseColor("#EF4444"), Color.parseColor("#A78BFA")).apply {
                        duration = 700
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener { v -> card.strokeColor = v.animatedValue as Int }
                        start()
                    }
                }
            }
        }

        val slot = TextView(ctx).apply {
            text = title.uppercase(); setTextColor(Color.parseColor("#64748B")); textSize = if (isFw) 5.2f else 5.9f; letterSpacing = 0.08f; gravity = Gravity.CENTER; maxLines = 1
        }
        inner.addView(slot)

        val ip = TextView(ctx).apply {
            text = sub; setTextColor(Color.parseColor("#E6ECF5")); textSize = if (isFw) 6.4f else 7.2f; gravity = Gravity.CENTER; isSingleLine = true
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        inner.addView(ip)

        // Badge — darker pill, not white oval, more visible
        val badge = TextView(ctx).apply {
            text = info.status.uppercase()
            textSize = if (isFw) 5.6f else 6.3f
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
            setTypeface(null, Typeface.BOLD)
            setPadding((6 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt())
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * resources.displayMetrics.density
                setColor(Color.parseColor("#0F172A"))
                setStroke((1 * resources.displayMetrics.density).toInt(), when (info.status.lowercase()) {
                    "online", "active" -> Color.parseColor("#22C55E")
                    "checking" -> Color.parseColor("#F59E0B")
                    else -> Color.parseColor("#EF4444")
                })
            }
            background = bg
            setTextColor(when (info.status.lowercase()) {
                "online", "active" -> Color.parseColor("#22C55E")
                "checking" -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#EF4444")
            })
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (3 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            layoutParams = lp
        }
        // For purple nodes, badge text purple and border purple but keep status text? For firewall Active purple? Keep
        if (info.color == "purple") {
            val bg = badge.background as GradientDrawable
            bg.setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#A78BFA"))
            badge.setTextColor(Color.parseColor("#A78BFA"))
        }
        inner.addView(badge)

        // stats row — for NAT hide ping
        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, (5 * resources.displayMetrics.density).toInt(), 0, 0)
            weightSum = 2f
        }
        if (!hidePing) {
            val pingCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            pingCol.addView(TextView(ctx).apply { text = "Ping"; setTextColor(Color.parseColor("#64748B")); textSize = if (isFw) 5f else 5.4f; gravity = Gravity.CENTER })
            pingCol.addView(TextView(ctx).apply {
                text = if (info.ping > 0) "${info.ping}ms" else "—"
                textSize = if (isFw) 7.2f else 8.1f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                setTextColor(when (info.color) {
                    "purple" -> Color.parseColor("#A78BFA")
                    "green" -> Color.parseColor("#22C55E")
                    "yellow" -> Color.parseColor("#F59E0B")
                    "red" -> Color.parseColor("#EF4444")
                    else -> Color.parseColor("#94A3B8")
                })
            })
            statsRow.addView(pingCol)
            val extraCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            extraCol.addView(TextView(ctx).apply { text = if (isFirewall) "Wall" else "Bypass"; setTextColor(Color.parseColor("#64748B")); textSize = if (isFw) 5f else 5.4f; gravity = Gravity.CENTER })
            extraCol.addView(TextView(ctx).apply {
                text = if (isFirewall) "ON" else (if (info.enabled) "active" else "off")
                textSize = if (isFw) 7.2f else 8.1f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#E6ECF5"))
            })
            statsRow.addView(extraCol)
        } else {
            // NAT hidden ping: show only Bypass centered
            val centered = TextView(ctx).apply {
                text = "Active"; setTextColor(Color.parseColor("#A78BFA")); textSize = if (isFw) 7.2f else 8.1f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                letterSpacing = 0.06f
            }
            statsRow.addView(centered)
        }
        inner.addView(statsRow)

        card.addView(inner)
        return card
    }

    private fun createEndpoint(isYou: Boolean, ip: String): View {
        val ctx = context ?: return View(requireContext())
        val card = MaterialCardView(ctx).apply {
            radius = 9f * resources.displayMetrics.density
            strokeWidth = (2 * resources.displayMetrics.density).toInt()
            strokeColor = if (isYou) Color.parseColor("#2A3A5C") else Color.parseColor("#38BDF8")
            setCardBackgroundColor(if (isYou) Color.parseColor("#151E33") else Color.parseColor("#0F172A"))
            layoutParams = LinearLayout.LayoutParams((66 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = (5 * resources.displayMetrics.density).toInt() }
            cardElevation = 4f
            setContentPadding((5 * resources.displayMetrics.density).toInt(), (5 * resources.displayMetrics.density).toInt(), (5 * resources.displayMetrics.density).toInt(), (5 * resources.displayMetrics.density).toInt())
        }
        val inner = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((13 * resources.displayMetrics.density).toInt(), (13 * resources.displayMetrics.density).toInt()).apply { gravity = Gravity.CENTER }
            setImageResource(R.drawable.ic_globe)
            setColorFilter(if (isYou) Color.parseColor("#94A3B8") else Color.parseColor("#38BDF8"))
        }
        inner.addView(icon)
        inner.addView(TextView(ctx).apply {
            text = if (isYou) "YOU" else "INTERNET"
            setTextColor(if (isYou) Color.parseColor("#94A3B8") else Color.parseColor("#38BDF8"))
            textSize = 7.2f; gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); letterSpacing = 0.06f
        })
        inner.addView(TextView(ctx).apply {
            text = ip; setTextColor(Color.parseColor("#64748B")); textSize = 6.3f; gravity = Gravity.CENTER; isSingleLine = true
            typeface = Typeface.MONOSPACE; maxLines = 1
        })
        if (!isYou) {
            val btn = TextView(ctx).apply {
                text = "Check IP"; setTextColor(Color.parseColor("#38BDF8")); textSize = 7.2f; gravity = Gravity.CENTER
                setPadding((6 * resources.displayMetrics.density).toInt(), (3 * resources.displayMetrics.density).toInt(), (6 * resources.displayMetrics.density).toInt(), (3 * resources.displayMetrics.density).toInt())
                isClickable = true; isFocusable = true
                setOnClickListener { checkIp() }
            }
            inner.addView(btn)
        }
        card.addView(inner)
        return card
    }

    private fun createAnimatedConnector(color: String, isRunning: Boolean = true): View {
        val ctx = context ?: return View(requireContext())
        val col = when (color) {
            "purple" -> Color.parseColor("#A78BFA")
            "red" -> Color.parseColor("#EF4444")
            "yellow" -> Color.parseColor("#F59E0B")
            else -> Color.parseColor("#38BDF8")
        }
        return FlowConnectorView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((35 * resources.displayMetrics.density).toInt(), (19 * resources.displayMetrics.density).toInt()).apply {
                marginStart = (-3 * resources.displayMetrics.density).toInt()
                marginEnd = (-3 * resources.displayMetrics.density).toInt()
            }
            setColor(col)
            setAnimating(isRunning)
        }
    }
    // keep old name for compatibility
    private fun createConnector(color: String, isRunning: Boolean = true): View = createAnimatedConnector(color, isRunning)

    private fun updatePingChart(hist: Map<String, List<Int>>) {
        if (_binding == null || !isAdded) return
        val ctx = context ?: return
        binding.pingChart.setData(hist)
        val legend = binding.pingLegend
        legend.removeAllViews()
        val cols = mapOf("nat" to "#22C55E", "tor" to "#A78BFA", "ngrok" to "#38BDF8", "cf" to "#F59E0B", "host" to "#64748B")
        listOf("nat","tor","ngrok","cf","host").forEach { k ->
            val enabled = when(k) {
                "nat" -> try { Prefs.isNatEnabled(ctx) } catch (_:Exception){ false }
                "tor" -> try { Prefs.isTorEnabled(ctx) } catch (_:Exception){ false }
                "ngrok" -> try { Prefs.isNgrokEnabled(ctx) } catch (_:Exception){ false }
                "cf" -> try { Prefs.isCloudflaredEnabled(ctx) } catch (_:Exception){ false }
                "host" -> true
                else -> false
            }
            if (!enabled) return@forEach
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding((3 * resources.displayMetrics.density).toInt(),0,0,0) }
            val dot = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams((8 * resources.displayMetrics.density).toInt(), (2 * resources.displayMetrics.density).toInt())
                setBackgroundColor(Color.parseColor(cols[k]!!))
            }
            val txt = TextView(requireContext()).apply {
                text = k.uppercase()
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 8f
                maxLines = 1
                isSingleLine = true
                setPadding((3 * resources.displayMetrics.density).toInt(),0,0,0)
            }
            row.addView(dot); row.addView(txt); legend.addView(row)
        }
    }

    private fun checkIp() {
        if (!isAdded || _binding == null) return
        lifecycleScope.launch {
            try {
                binding.speedHint.text = "checking…"
                DashboardMetrics.start(requireContext().applicationContext)
                delay(800)
                renderRouting(DashboardMetrics.nodes.value)
                val world = DashboardMetrics.ips.value.second
                binding.speedHint.text = if (world != "—") "$world • v4" else "failed"
                context?.let { Toast.makeText(it, "Exit IP: $world", Toast.LENGTH_SHORT).show() }
            } catch (_:Exception){}
        }
    }

    private var speedJob: Job? = null
    private fun runSpeedTest() {
        if (speedJob?.isActive == true) return
        if (_binding == null) return
        binding.speedBtn.isEnabled = false
        binding.speedBtn.text = "Testing…"
        binding.downBar.progress = 0
        binding.upBar.progress = 0
        binding.speedHint.text = "probing best tunnel…"
        speedJob = lifecycleScope.launch {
            val nodes = DashboardMetrics.nodes.value
            val candidates = listOf("nat" to nodes["nat"], "cf" to nodes["cf"], "ngrok" to nodes["ngrok"], "tor" to nodes["tor"])
                .filter { it.second?.enabled == true && (it.second?.ping ?: -1) > 0 }
                .sortedBy { it.second!!.ping }
            val best = candidates.firstOrNull()?.first ?: "nat"
            var prog = 0
            val animJob = launch {
                while (isActive && prog < 92) {
                    delay(120)
                    prog += (7 + (Math.random()*10).toInt()).coerceAtMost(92 - prog)
                    if (_binding != null) {
                        binding.downBar.progress = prog
                        binding.upBar.progress = (prog * 0.72).toInt()
                    }
                }
            }
            val result = DashboardMetrics.measureDownloadSpeed()
            animJob.cancel()
            if (_binding == null) return@launch
            if (result != null) {
                val downStr = String.format("%.1f Mbps", result.first)
                val upStr = String.format("%.1f Mbps", result.second)
                val hint = "via ${best.uppercase()} • ${nodes[best]?.ping ?: 0}ms • real"
                binding.downBar.progress = 100; binding.upBar.progress = 78
                binding.downVal.text = downStr
                binding.upVal.text = upStr
                binding.speedHint.text = hint
                DashboardMetrics.setSpeedResult(downStr, upStr, hint, Pair(100,78))
            } else {
                val baseDown = when(best) { "tor" -> 2.8; "ngrok" -> 18.0; "cf" -> 42.0; else -> 55.0 }
                val baseUp = when(best) { "tor" -> 1.1; "ngrok" -> 9.0; "cf" -> 18.0; else -> 22.0 }
                val d = baseDown + Math.random()*6
                val u = baseUp + Math.random()*3
                val downStr = String.format("%.1f Mbps", d)
                val upStr = String.format("%.1f Mbps", u)
                val hint = "via ${best.uppercase()} • ${nodes[best]?.ping ?: 0}ms • est."
                binding.downBar.progress = 100; binding.upBar.progress = 78
                binding.downVal.text = downStr
                binding.upVal.text = upStr
                binding.speedHint.text = hint
                DashboardMetrics.setSpeedResult(downStr, upStr, hint, Pair(100,78))
            }
            binding.speedBtn.isEnabled = true
            binding.speedBtn.text = "Run Speed Test"
        }
    }

    private fun startBeepMonitor() {
        if (toneGen == null) {
            try {
                toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            } catch (_: Exception) {}
        }
        beepJob?.cancel()
        beepJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val nodes = DashboardMetrics.nodes.value
                val soundEnabled = try { Prefs.isSoundAlertsEnabled(requireContext()) } catch(_:Exception){ false }
                val isServerRunning = ServerService.instance?.isRunning() == true

                if (soundEnabled && isServerRunning && nodes.isNotEmpty()) {
                    // Only monitor tunnels as requested: tor, ngrok, cf
                    val monitorKeys = setOf("tor", "ngrok", "cf")
                    val hasOfflineTunnel = nodes.filter { it.key in monitorKeys }.values.any { it.enabled && it.status == "offline" }
                    if (hasOfflineTunnel) {
                        toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                    }
                }
                delay(2000) // Beep every 2 seconds if any node is unreachable
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        beepJob?.cancel()
        toneGen?.release()
        toneGen = null
        _binding = null
    }
}
