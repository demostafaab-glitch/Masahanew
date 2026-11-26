package com.masahanew.dxfsurvey

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.security.MessageDigest

/**
 * Login screen. On first launch the user creates a password (stored only as a SHA-256 hash).
 * Later launches ask for that password.
 */
class LoginActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("auth", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasPassword = prefs.contains("hash")

        val title = TextView(this).apply {
            text = "DXF Survey"
            textSize = 28f
            gravity = Gravity.CENTER
        }
        val designer = TextView(this).apply {
            text = "تصميم المهندس مصطفى عبدو"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        }
        val version = TextView(this).apply {
            text = "الإصدار ${BuildConfig.VERSION_NAME}"
            textSize = 13f
            gravity = Gravity.CENTER
        }
        val password = EditText(this).apply {
            hint = "كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirm = EditText(this).apply {
            hint = "تأكيد كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            visibility = if (hasPassword) View.GONE else View.VISIBLE
        }
        val message = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        val button = Button(this).apply {
            text = if (hasPassword) "دخول" else "إنشاء كلمة المرور"
            setOnClickListener {
                val entered = password.text.toString()
                if (hasPassword) {
                    if (hash(entered) == prefs.getString("hash", null)) {
                        enterApp()
                    } else {
                        message.text = "كلمة المرور غير صحيحة"
                    }
                } else {
                    when {
                        entered.length < 4 -> message.text = "كلمة المرور يجب أن تكون 4 أحرف على الأقل"
                        entered != confirm.text.toString() -> message.text = "كلمتا المرور غير متطابقتين"
                        else -> {
                            prefs.edit().putString("hash", hash(entered)).apply()
                            enterApp()
                        }
                    }
                }
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(title)
            addView(designer)
            addView(version)
            addView(password)
            addView(confirm)
            addView(button)
            addView(message)
        }
        setContentView(content)
    }

    private fun enterApp() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
