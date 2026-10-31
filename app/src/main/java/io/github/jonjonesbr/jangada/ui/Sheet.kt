package io.github.jonjonesbr.jangada.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.jonjonesbr.jangada.R
import kotlin.math.min

/**
 * Painel que sobe de baixo, no alcance do polegar: título, mensagem opcional e linhas
 * tocáveis. Usado no lugar das listas em diálogo (setItems, setSingleChoiceItems).
 */
class Sheet(private val activity: Activity) {

    /** Uma linha do painel. Tocar nela fecha o painel e, depois, roda [action]. */
    class Row(
        val title: CharSequence,
        val description: CharSequence? = null,
        val value: CharSequence? = null,
        val icon: String? = null,
        val checked: Boolean = false,
        val enabled: Boolean = true,
        val destructive: Boolean = false,
        val action: () -> Unit,
    )

    private var titleText: CharSequence? = null
    private var messageText: CharSequence? = null
    private val items = mutableListOf<Row>()
    private var onDismissAction: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    fun title(text: CharSequence): Sheet = apply { titleText = text }

    fun message(text: CharSequence): Sheet = apply { messageText = text }

    fun row(row: Row): Sheet = apply { items += row }

    fun rows(list: List<Row>): Sheet = apply { items += list }

    /** Roda quando o painel some sem uma linha escolhida (cancelar, voltar, toque fora). */
    fun onDismiss(action: () -> Unit): Sheet = apply { onDismissAction = action }

    /** Mostra o painel. Devolve null (e não mostra) se a tela está fechando. */
    fun show(): Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        var picked = false
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { if (!picked) onDismissAction?.invoke() }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        // Fecha o painel e só então roda a ação (ela pode abrir outro painel ou diálogo).
        val choose: (Row) -> Unit = { row ->
            picked = true
            dialog.dismiss()
            handler.post { if (!activity.isDestroyed) row.action() }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.sheet_bg)
            setPadding(0, 0, 0, dp(8))
        }
        // Respeita a barra de navegação: o painel fica acima dela.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, dp(8) + bottom)
            insets
        }

        // Alça decorativa: só enfeite, fora da leitura de tela.
        root.addView(
            View(activity).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(2).toFloat()
                    setColor(activity.getColor(R.color.setup_muted))
                }
                alpha = 0.4f
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
                bottomMargin = dp(10)
            },
        )

        titleText?.let { t ->
            root.addView(TextView(activity).apply {
                text = t
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(activity.getColor(R.color.setup_text))
                setPadding(dp(16), 0, dp(16), dp(4))
                if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            })
        }
        messageText?.let { m ->
            root.addView(TextView(activity).apply {
                text = m
                textSize = 14f
                setTextColor(activity.getColor(R.color.setup_muted))
                setPadding(dp(16), 0, dp(16), dp(8))
            })
        }

        if (items.isNotEmpty()) {
            val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            for (row in items) list.addView(rowView(row, choose))
            // As linhas rolam dentro de no máximo ~75% da altura da tela.
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.75f).toInt()
            val scroll = MaxHeightScrollView(activity, maxHeight)
            scroll.addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
        // O título é anunciado ao abrir (depois de mostrar, para o leitor de tela já ter a janela).
        titleText?.let { t ->
            if (Build.VERSION.SDK_INT >= 28) root.accessibilityPaneTitle = t
            else root.announceForAccessibility(t)
        }
        return dialog
    }

    /** Uma linha: ícone, título e descrição à esquerda; valor e ✓ à direita. Vira um alvo só para o leitor de tela. */
    private fun rowView(row: Row, choose: (Row) -> Unit): View {
        val ripple = TypedValue().also { activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundResource(ripple)
            isFocusable = true
            isEnabled = row.enabled
            isClickable = row.enabled
            alpha = if (row.enabled) 1f else 0.4f
            contentDescription = describe(row)
            if (row.enabled) setOnClickListener { choose(row) }

            row.icon?.let { icon ->
                addView(
                    TextView(activity).apply {
                        text = icon
                        textSize = 20f
                        gravity = Gravity.CENTER
                        setTextColor(activity.getColor(R.color.setup_text))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }

            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(TextView(activity).apply {
                    text = row.title
                    textSize = 16f
                    setTextColor(activity.getColor(if (row.destructive) R.color.setup_error else R.color.setup_text))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                row.description?.let { description ->
                    addView(TextView(activity).apply {
                        text = description
                        textSize = 13f
                        setTextColor(activity.getColor(R.color.setup_muted))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    })
                }
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.value?.let { value ->
                addView(
                    TextView(activity).apply {
                        text = value
                        textSize = 14f
                        setTextColor(activity.getColor(R.color.accent_text))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(12)
                    },
                )
            }
            if (row.checked) {
                addView(
                    TextView(activity).apply {
                        text = "✓"
                        textSize = 16f
                        setTextColor(activity.getColor(R.color.accent_text))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(8)
                    },
                )
            }
        }
    }

    /** Texto que o leitor de tela lê para a linha inteira: título, valor, descrição e, se marcada, "selecionado". */
    private fun describe(row: Row): String {
        val text = listOfNotNull(row.title, row.value, row.description).joinToString(", ")
        return if (row.checked) text + ", " + activity.getString(R.string.sheet_selected) else text
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), activity.resources.displayMetrics).toInt()
}

/** ScrollView que não passa de [maxHeight] pixels de altura; o conteúdo rola dentro dela. */
private class MaxHeightScrollView(context: Context, private val maxHeight: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) {
            maxHeight
        } else {
            min(View.MeasureSpec.getSize(heightMeasureSpec), maxHeight)
        }
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
    }
}
