package org.dftz.androidproxy

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {

    // Prox5 palette (from "Five")
    private val bg = Color.parseColor("#DCE8F2")
    private val card = Color.parseColor("#FFFFFF")
    private val navy = Color.parseColor("#20323E")
    private val teal = Color.parseColor("#2BB3C9")
    private val muted = Color.parseColor("#5B6B77")
    private val field = Color.parseColor("#EEF3F7")
    private val stop = Color.parseColor("#D9685F")

    private lateinit var portInput: EditText
    private lateinit var userInput: EditText
    private lateinit var passInput: EditText
    private lateinit var toggle: Button
    private lateinit var status: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        scroll.addView(root)

        // ---- header: mascot + name ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.mascot)
        }, LinearLayout.LayoutParams(dp(60), dp(60)))
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titleCol.addView(TextView(this).apply {
            text = "Prox5"
            textSize = 28f
            setTextColor(navy)
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = "Five · your cyber-scout"
            textSize = 13f
            setTextColor(teal)
        })
        header.addView(titleCol)
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Turns this device into an HTTP + SOCKS5 proxy — routes traffic past jams, sniffers & roadblocks."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        })

        // ---- config card ----
        val cfg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16)
            setPadding(dp(16), dp(16), dp(16), dp(6))
        }
        cfg.addView(label("PORT"))
        portInput = makeField(InputType.TYPE_CLASS_NUMBER, "13178")
        portInput.setText(prefs().getInt("port", ProxyService.DEFAULT_PORT).toString())
        cfg.addView(portInput)
        cfg.addView(label("USERNAME  (blank = open, no auth)"))
        userInput = makeField(InputType.TYPE_CLASS_TEXT, "username")
        userInput.setText(prefs().getString("user", ""))
        cfg.addView(userInput)
        cfg.addView(label("PASSWORD"))
        passInput = makeField(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "password"
        )
        passInput.setText(prefs().getString("pass", ""))
        cfg.addView(passInput)
        val cfgLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        cfgLp.topMargin = dp(18)
        root.addView(cfg, cfgLp)

        // ---- start/stop ----
        toggle = Button(this).apply {
            text = "Start"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(teal, 14)
            stateListAnimator = null
            setOnClickListener { onToggle() }
        }
        val tLp = LinearLayout.LayoutParams(MATCH_PARENT, dp(52))
        tLp.topMargin = dp(16)
        root.addView(toggle, tLp)

        // ---- status card ----
        status = TextView(this).apply {
            background = rounded(card, 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextColor(navy)
            textSize = 13f
        }
        val sLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        sLp.topMargin = dp(16)
        root.addView(status, sLp)

        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(muted)
        letterSpacing = 0.05f
        setPadding(dp(2), dp(8), 0, dp(4))
    }

    private fun makeField(type: Int, hint: String): EditText {
        val e = EditText(this)
        e.inputType = type
        e.hint = hint
        e.setHintTextColor(Color.parseColor("#9AA8B2"))
        e.setTextColor(navy)
        e.textSize = 16f
        e.background = rounded(field, 10)
        e.setPadding(dp(12), dp(12), dp(12), dp(12))
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        e.layoutParams = lp
        return e
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }

    private fun onToggle() {
        if (ProxyService.isRunning) {
            stopService(Intent(this, ProxyService::class.java))
        } else {
            val port = portInput.text.toString().toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                status.text = "Enter a port between 1 and 65535"
                return
            }
            val user = userInput.text.toString().trim()
            val pass = passInput.text.toString()
            if (user.isNotEmpty() && pass.isEmpty()) {
                status.text = "Set a password (or clear the username to run open)"
                return
            }
            prefs().edit()
                .putInt("port", port).putString("user", user).putString("pass", pass).apply()
            val i = Intent(this, ProxyService::class.java)
                .putExtra(ProxyService.EXTRA_PORT, port)
                .putExtra(ProxyService.EXTRA_USER, user)
                .putExtra(ProxyService.EXTRA_PASS, pass)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        }
        handler.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        val running = ProxyService.isRunning
        toggle.text = if (running) "Stop" else "Start"
        toggle.background = rounded(if (running) stop else teal, 14)
        portInput.isEnabled = !running
        userInput.isEnabled = !running
        passInput.isEnabled = !running
        val ip = localIp()
        status.text = if (running) {
            val auth = if (ProxyService.authEnabled)
                "🔒 Auth ON — clients must use the username/password above."
            else
                "⚠️ OPEN — no auth. Anyone who can reach this port can use it."
            "● Five is patrolling\n\n" +
                "Point a browser / device proxy at:\n" +
                "    $ip:${ProxyService.runningPort}\n\n" +
                "Works as an HTTP proxy OR a SOCKS5 proxy.\n$auth\n\n" +
                "last: ${ProxyService.lastLog}"
        } else {
            "○ Five is standing by\n\nThis device's IP: $ip"
        }
    }

    private fun prefs() = getSharedPreferences("cfg", MODE_PRIVATE)

    private fun localIp(): String {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (_: Exception) {}
        return "unknown"
    }
}
