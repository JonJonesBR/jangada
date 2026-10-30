package io.github.jonjonesbr.jangada

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * Chaves de API (Modo Pro): para quem usa os agentes com chave em vez de
 * assinatura. Cada chave vira uma variável de ambiente no Linux do app.
 */
class ApiKeysActivity : JangadaActivity() {

    private lateinit var store: SecretStore
    private lateinit var list: LinearLayout

    /** Variáveis conhecidas e para que servem (o usuário também pode criar outras). */
    private val presets by lazy {
        listOf(
            "ANTHROPIC_API_KEY" to getString(R.string.keys_anthropic),
            "OPENAI_API_KEY" to getString(R.string.keys_openai),
            "GEMINI_API_KEY" to getString(R.string.keys_gemini),
            "OPENROUTER_API_KEY" to getString(R.string.keys_openrouter),
            "DEEPSEEK_API_KEY" to "DeepSeek",
            "DASHSCOPE_API_KEY" to getString(R.string.keys_dashscope),
            "GROQ_API_KEY" to "Groq",
            "MISTRAL_API_KEY" to "Mistral",
            "GITHUB_TOKEN" to getString(R.string.keys_github),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecretStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.setup_bg))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bar_bg))
        }
        bar.addView(TextView(this).apply {
            setText(R.string.terminal_home_arrow)
            setTextColor(getColor(R.color.bar_text))
            textSize = 15f
            minHeight = dp(48)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            setText(R.string.keys_title)
            setTextColor(getColor(R.color.setup_text))
            textSize = 16f
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(list) })
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        list.addView(TextView(this).apply {
            setText(R.string.keys_intro)
            setTextColor(getColor(R.color.setup_muted))
            textSize = 14f
            setPadding(0, 0, 0, dp(12))
        })
        val custom = store.names().filter { name -> presets.none { it.first == name } }
        for ((name, description) in presets + custom.map { it to getString(R.string.keys_custom) }) {
            list.addView(row(name, description))
        }
        list.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.keys_add_custom)
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(getColor(R.color.accent_text))
            setOnClickListener { askName() }
        })
    }

    private fun row(name: String, description: String): View {
        // "Salva" só quando dá para ler: um blob que não decifra (ex.: Keystore apagado) não conta.
        val readable = store.get(name) != null
        val stored = store.has(name)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(dp(14), dp(10), dp(14), dp(6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = name
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(getColor(R.color.setup_text))
            textSize = 14f
        })
        card.addView(TextView(this).apply {
            text = description
            setTextColor(getColor(R.color.setup_muted))
            textSize = 13f
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val (statusText, statusColor) = when {
            readable -> R.string.keys_saved to R.color.setup_done
            stored -> R.string.keys_unreadable to R.color.setup_error
            else -> R.string.keys_not_set to R.color.setup_muted
        }
        actions.addView(TextView(this).apply {
            setText(statusText)
            setTextColor(getColor(statusColor))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        actions.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(if (stored) R.string.keys_change else R.string.keys_set)
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(getColor(R.color.accent_text))
            setOnClickListener { askValue(name) }
        })
        if (stored) actions.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(R.string.chat_delete)
            isAllCaps = false
            minHeight = dp(48)
            setTextColor(getColor(R.color.setup_error))
            setOnClickListener { confirmRemove(name) }
        })
        card.addView(actions)
        return card
    }

    /** Pede confirmação antes de apagar a chave salva. */
    private fun confirmRemove(name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.keys_remove_title, name))
            .setMessage(R.string.keys_remove_message)
            .setPositiveButton(R.string.keys_remove_confirm) { _, _ ->
                store.remove(name)
                Toast.makeText(this, R.string.keys_removed, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askValue(name: String) {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.keys_paste_hint)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton(R.string.keys_save) { _, _ ->
                val value = field.text.toString().trim()
                if (value.isEmpty()) return@setPositiveButton
                try {
                    store.put(name, value)
                } catch (e: Exception) {
                    // Falha do Android Keystore: avisa em vez de derrubar o app.
                    Toast.makeText(this, R.string.api_key_save_failed, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, R.string.keys_saved_note, Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askName() {
        val field = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = getString(R.string.keys_name_hint)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.keys_add_custom)
            .setView(LinearLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = field.text.toString().trim().uppercase()
                when {
                    !SecretStore.VALID_NAME.matches(name) ->
                        Toast.makeText(this, R.string.keys_invalid_name, Toast.LENGTH_LONG).show()
                    SecretStore.isReserved(name) ->
                        Toast.makeText(this, getString(R.string.api_key_reserved, name), Toast.LENGTH_LONG).show()
                    else -> askValue(name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
