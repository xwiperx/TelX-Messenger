package com.telx.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.util.PatternsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.telx.app.R
import com.telx.app.data.ApiErr
import com.telx.app.data.Prefs
import com.telx.app.data.optStringOrNull
import com.telx.app.net.Api
import com.telx.app.svc.PollService
import com.telx.app.ui.common.Helpers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Authentication hub: login, register, forgot password, 2FA — one flow machine.
 */
class AuthActivity : AppCompatActivity() {

    private enum class Mode { LOGIN, REGISTER, FORGOT_1, FORGOT_2, TWOFACODE }

    private var mode = Mode.LOGIN
    private val ui = CoroutineScope(Dispatchers.Main)
    private var checkJob: Job? = null
    private var pendingLoginUser = ""
    private var pendingLoginPass = ""

    private lateinit var logo: ImageView
    private lateinit var titleView: TextView
    private lateinit var subtitle: TextView
    private lateinit var tilFirst: TextInputLayout
    private lateinit var tilLast: TextInputLayout
    private lateinit var tilUsername: TextInputLayout
    private lateinit var tilEmail: TextInputLayout
    private lateinit var tilPassword: TextInputLayout
    private lateinit var tilCode: TextInputLayout
    private lateinit var etFirst: TextInputEditText
    private lateinit var etLast: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etCode: TextInputEditText
    private lateinit var checkIcon: ImageView
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnSwitch: TextView
    private lateinit var btnForgot: TextView
    private lateinit var progress: View
    private var avatarUri: Uri? = null
    private lateinit var avatarWrap: View
    private lateinit var avatarView: com.telx.app.ui.common.AvatarView

    private val pickAvatar = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra("path") ?: return@registerForActivityResult
        avatarUri = Uri.parse("file://$path")
        avatarView.bind("", avatarUri.toString(), 0xFF3390EC.toInt())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.token.isNotEmpty()) { goMain(); return }
        setContentView(R.layout.activity_auth)

        logo = findViewById(R.id.auth_logo)
        titleView = findViewById(R.id.auth_title)
        subtitle = findViewById(R.id.auth_subtitle)
        tilFirst = findViewById(R.id.til_first)
        tilLast = findViewById(R.id.til_last)
        tilUsername = findViewById(R.id.til_username)
        tilEmail = findViewById(R.id.til_email)
        tilPassword = findViewById(R.id.til_password)
        tilCode = findViewById(R.id.til_code)
        etFirst = findViewById(R.id.et_first)
        etLast = findViewById(R.id.et_last)
        etUsername = findViewById(R.id.et_username)
        etEmail = findViewById(R.id.et_email)
        etPassword = findViewById(R.id.et_password)
        etCode = findViewById(R.id.et_code)
        checkIcon = findViewById(R.id.username_check)
        btnPrimary = findViewById(R.id.btn_primary)
        btnSwitch = findViewById(R.id.btn_switch)
        btnForgot = findViewById(R.id.btn_forgot)
        progress = findViewById(R.id.auth_progress)
        avatarWrap = findViewById(R.id.auth_avatar_wrap)
        avatarView = findViewById(R.id.auth_avatar)
        avatarWrap.setOnClickListener {
            pickAvatar.launch(Intent(this, com.telx.app.ui.common.CropActivity::class.java)
                .putExtra("mode", "circle"))
        }

        etUsername.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (mode == Mode.REGISTER) scheduleUsernameCheck(s?.toString() ?: "")
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        btnPrimary.setOnClickListener { onPrimary() }
        btnSwitch.setOnClickListener {
            setMode(if (mode == Mode.REGISTER) Mode.LOGIN else Mode.REGISTER)
        }
        btnForgot.setOnClickListener {
            when (mode) {
                Mode.LOGIN -> setMode(Mode.FORGOT_1)
                Mode.FORGOT_1, Mode.FORGOT_2 -> setMode(Mode.LOGIN)
                else -> {}
            }
        }
        setMode(Mode.LOGIN)
    }

    private fun scheduleUsernameCheck(u: String) {
        checkJob?.cancel()
        if (u.isEmpty()) { checkIcon.visibility = View.GONE; return }
        checkJob = ui.launch {
            delay(450)
            val okIcon: Int
            var available = false
            try {
                val r = withContext(Dispatchers.IO) { Api.checkUsername(u) }
                available = r.first
                okIcon = if (available) R.drawable.ic_check_circle else R.drawable.ic_cancel
            } catch (e: Exception) {
                checkIcon.visibility = View.GONE
                return@launch
            }
            checkIcon.setImageResource(okIcon)
            checkIcon.setColorFilter(ContextCompat.getColor(this@AuthActivity,
                if (available) R.color.online_green else R.color.error_red))
            checkIcon.visibility = View.VISIBLE
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        checkIcon.visibility = View.GONE
        avatarWrap.visibility = View.GONE
        listOf(tilFirst, tilLast, tilUsername, tilEmail, tilPassword, tilCode).forEach { it.visibility = View.GONE }
        btnForgot.visibility = View.GONE
        when (m) {
            Mode.LOGIN -> {
                titleView.text = "TelX"
                subtitle.text = "Sign in to continue to your messages"
                tilUsername.visibility = View.VISIBLE
                tilUsername.hint = "Username or email"
                tilPassword.visibility = View.VISIBLE
                btnPrimary.text = "Log in"
                btnSwitch.text = "Don't have an account? Sign up"
                btnForgot.visibility = View.VISIBLE
                btnForgot.text = "Forgot password?"
            }
            Mode.REGISTER -> {
                titleView.text = "Sign Up"
                subtitle.text = "Create your TelX account"
                avatarWrap.visibility = View.VISIBLE
                tilFirst.visibility = View.VISIBLE
                tilLast.visibility = View.VISIBLE
                tilUsername.visibility = View.VISIBLE
                tilUsername.hint = "Username (a-z, 0-9, _)"
                tilEmail.visibility = View.VISIBLE
                tilPassword.visibility = View.VISIBLE
                btnPrimary.text = "Create account"
                btnSwitch.text = "Already have an account? Log in"
            }
            Mode.FORGOT_1 -> {
                titleView.text = "Reset Password"
                subtitle.text = "Enter your username or email to receive a code"
                tilUsername.visibility = View.VISIBLE
                tilUsername.hint = "Username or email"
                btnPrimary.text = "Send code"
                btnSwitch.text = ""
                btnForgot.visibility = View.VISIBLE
                btnForgot.text = "Back to login"
            }
            Mode.FORGOT_2 -> {
                titleView.text = "Reset Password"
                subtitle.text = "Enter the code sent to your email and a new password"
                tilCode.visibility = View.VISIBLE
                tilCode.hint = "6-digit code"
                tilPassword.visibility = View.VISIBLE
                btnPrimary.text = "Reset password"
                btnSwitch.text = ""
                btnForgot.visibility = View.VISIBLE
                btnForgot.text = "Back to login"
            }
            Mode.TWOFACODE -> {
                titleView.text = "Two-Step Verification"
                subtitle.text = "We sent a code to your email. Enter it below."
                tilCode.visibility = View.VISIBLE
                tilCode.hint = "6-digit code"
                btnPrimary.text = "Verify"
                btnSwitch.text = ""
            }
        }
    }

    private fun busy(b: Boolean) {
        progress.visibility = if (b) View.VISIBLE else View.GONE
        btnPrimary.isEnabled = !b
    }

    private fun err(til: TextInputLayout?, e: Exception): Boolean {
        if (e is ApiErr) {
            when (e.code) {
                "username_taken" -> { tilUsername.error = "This username is already taken"; return true }
                "email_taken" -> { tilEmail.error = "This email is already registered"; return true }
                "credentials" -> { tilPassword.error = e.message; return true }
                "code" -> { tilCode.error = e.message; return true }
                "invalid" -> { Helpers.toast(this, e.message); return true }
                else -> { Helpers.toast(this, e.message?.ifEmpty { "Connection error" } ?: "Connection error"); return true }
            }
        }
        Helpers.toast(this, "Network error — check your connection")
        return true
    }

    private fun onPrimary() {
        clearErrors()
        when (mode) {
            Mode.LOGIN -> {
                val u = etUsername.text?.toString()?.trim() ?: ""
                val p = etPassword.text?.toString() ?: ""
                if (u.isEmpty() || p.isEmpty()) { toastEmpty(); return }
                busy(true)
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) { Api.login(u, p, deviceName()) }
                        if (j.optBoolean("needs2fa")) {
                            pendingLoginUser = u; pendingLoginPass = p
                            setMode(Mode.TWOFACODE)
                        } else {
                            onSuccess(j)
                        }
                    } catch (e: Exception) { err(null, e) }
                    busy(false)
                }
            }
            Mode.REGISTER -> {
                val f = etFirst.text?.toString()?.trim() ?: ""
                val l = etLast.text?.toString()?.trim() ?: ""
                val u = etUsername.text?.toString()?.trim() ?: ""
                val em = etEmail.text?.toString()?.trim() ?: ""
                val p = etPassword.text?.toString() ?: ""
                if (f.length < 2) { tilFirst.error = "Enter your first name"; return }
                if (u.length < 5) { tilUsername.error = "Username must be at least 5 characters"; return }
                if (!PatternsCompat.EMAIL_ADDRESS.matcher(em).matches()) { tilEmail.error = "Enter a valid email"; return }
                if (p.length < 6) { tilPassword.error = "At least 6 characters"; return }
                busy(true)
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) { Api.register(f, l, u, em, p, deviceName()) }
                        onSuccess(j)
                    } catch (e: Exception) { err(null, e) }
                    busy(false)
                }
            }
            Mode.FORGOT_1 -> {
                val u = etUsername.text?.toString()?.trim() ?: ""
                if (u.isEmpty()) { toastEmpty(); return }
                busy(true)
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.forgot(u) }
                        Helpers.toast(this@AuthActivity, "Code sent to your email")
                        setMode(Mode.FORGOT_2)
                    } catch (e: Exception) { Helpers.toast(this@AuthActivity, "Network error") }
                    busy(false)
                }
            }
            Mode.FORGOT_2 -> {
                val u = etUsername.text?.toString()?.trim() ?: ""
                val c = etCode.text?.toString()?.trim() ?: ""
                val np = etPassword.text?.toString() ?: ""
                if (c.isEmpty() || np.length < 6) { toastEmpty(); return }
                busy(true)
                ui.launch {
                    try {
                        withContext(Dispatchers.IO) { Api.reset(u, c, np) }
                        Helpers.toast(this@AuthActivity, "Password reset — log in now")
                        setMode(Mode.LOGIN)
                    } catch (e: Exception) { err(null, e) }
                    busy(false)
                }
            }
            Mode.TWOFACODE -> {
                val c = etCode.text?.toString()?.trim() ?: ""
                if (c.length < 4) { toastEmpty(); return }
                busy(true)
                ui.launch {
                    try {
                        val j = withContext(Dispatchers.IO) { Api.verify2fa(pendingLoginUser, pendingLoginPass, c, deviceName()) }
                        onSuccess(j)
                    } catch (e: Exception) { err(null, e) }
                    busy(false)
                }
            }
        }
    }

    private fun onSuccess(j: JSONObject) {
        val token = j.optString("token", "")
        val user = j.optJSONObject("user") ?: JSONObject()
        Prefs.token = token
        Prefs.myId = user.optInt("id")
        Prefs.myName = user.optString("name")
        Prefs.myUsername = user.optString("username")
        Prefs.myAvatar = user.optStringOrNull("avatar")
        Prefs.privacyLock = user.optBoolean("privacy_lock")
        com.telx.app.net.Events.start()
        PollService.start(this)
        if (mode == Mode.REGISTER && avatarUri != null) {
            // upload the chosen profile photo, then continue to the app
            ui.launch {
                val f = copyUriToCache(avatarUri!!)
                if (f != null) {
                    try {
                        val up = withContext(Dispatchers.IO) { Api.upload("avatar", f) }
                        val pj = withContext(Dispatchers.IO) {
                            Api.profileUpdate(avatarRel = up.optString("rel"))
                        }
                        val u = pj.optJSONObject("user")
                        if (u != null) Prefs.myAvatar = u.optStringOrNull("avatar")
                        f.delete()
                    } catch (_: Exception) {}
                }
                goMain()
            }
        } else {
            goMain()
        }
    }

    private fun copyUriToCache(uri: Uri): java.io.File? = try {
        val f = java.io.File(cacheDir, "signup_${System.currentTimeMillis()}.jpg")
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        if (f.exists() && f.length() > 0) f else null
    } catch (e: Exception) { null }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun clearErrors() {
        listOf(tilFirst, tilLast, tilUsername, tilEmail, tilPassword, tilCode).forEach { it.error = null }
    }

    private fun toastEmpty() = Helpers.toast(this, "Please fill all required fields")

    private fun deviceName(): String {
        val m = android.os.Build.MODEL ?: "Android"
        return "TelX Android · $m"
    }
}
