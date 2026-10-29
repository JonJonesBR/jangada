package io.github.jonjonesbr.jangada

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Sobre: versão, privacidade, licenças e créditos. */
class AboutActivity : JangadaActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(32), dp(28), dp(32))
        }
        content.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(88), dp(88)))
        content.addView(text(getString(R.string.app_name), 26f, R.color.setup_text, bold = true))
        content.addView(text(getString(R.string.about_version, version), 14f, R.color.setup_muted))
        content.addView(text(getString(R.string.about_tagline), 16f, R.color.setup_text, top = 16))
        content.addView(text(getString(R.string.about_privacy), 14f, R.color.setup_muted, top = 12))
        content.addView(link(getString(R.string.about_repo), REPO))
        content.addView(link(getString(R.string.about_licenses), "$REPO/blob/main/THIRD_PARTY_NOTICES.md"))
        content.addView(text(getString(R.string.about_credits), 12f, R.color.setup_muted, top = 20, center = false))

        // A Sobre só abre a partir dos Ajustes, então o botão volta para eles.
        val back = TextView(this).apply {
            setText(R.string.about_back)
            setTextColor(getColor(R.color.bar_text))
            textSize = 15f
            minHeight = dp(48)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { finish() }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(LinearLayout(this).apply {
            setBackgroundColor(getColor(R.color.bar_bg))
            addView(back)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, top: Int = 4, center: Boolean = true) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(getColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = if (center) Gravity.CENTER else Gravity.START
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(top), 0, 0)
        }

    private fun link(label: String, url: String) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        setTextColor(getColor(R.color.accent_text))
        setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    }

    private fun dp(value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private companion object {
        const val REPO = "https://github.com/JonJonesBR/jangada"
    }
}
