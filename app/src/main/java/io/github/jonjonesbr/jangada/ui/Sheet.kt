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
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import io.github.jonjonesbr.jangada.R
import kotlin.math.max
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
    private var contentView: View? = null
    private val items = mutableListOf<Row>()
    private var onDismissAction: (() -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    fun title(text: CharSequence): Sheet = apply { titleText = text }

    fun message(text: CharSequence): Sheet = apply { messageText = text }

    /** Uma view própria (ex.: uma barra) logo abaixo do título e acima da mensagem. Ela cuida das próprias margens. */
    fun content(view: View): Sheet = apply { contentView = view }

    fun row(row: Row): Sheet = apply { items += row }

    fun rows(list: List<Row>): Sheet = apply { items += list }

    /** Roda quando o painel some sem uma linha escolhida (cancelar, voltar, toque fora). */
    fun onDismiss(action: () -> Unit): Sheet = apply { onDismissAction = action }

    /** Mostra o painel. Devolve null (e não mostra) se a tela está fechando. */
    fun show(): Dialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        var picked = false
        // A tela foi destruída com o painel aberto: fecha sem rodar nada (evita janela vazada).
        var gone = false
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        val owner = activity as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                gone = true
                dialog.dismiss()
            }
        }
        owner?.lifecycle?.addObserver(observer)
        dialog.setOnDismissListener {
            owner?.lifecycle?.removeObserver(observer)
            if (!picked && !gone) onDismissAction?.invoke()
        }
        // Fecha o painel e só então roda a ação (ela pode abrir outro painel ou diálogo).
        val choose: (Row) -> Unit = { row ->
            if (!picked) {
                picked = true
                dialog.dismiss()
                handler.post { if (!activity.isFinishing && !activity.isDestroyed) row.action() }
            }
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

        // Arrastar para baixo fecha o painel. O root recebe só os toques que nenhum filho consumiu
        // (alça, título, mensagem); as linhas clicáveis e a lista com rolagem continuam como estão.
        val config = ViewConfiguration.get(activity)
        val slop = config.scaledTouchSlop
        val minFling = config.scaledMinimumFlingVelocity
        var downRawY = 0f
        var dragging = false
        var tracker: VelocityTracker? = null
        // O painel anda junto com o dedo: a velocidade usa a posição na tela (raw), não a do painel.
        fun track(t: VelocityTracker, ev: MotionEvent) {
            val copy = MotionEvent.obtain(ev)
            copy.setLocation(ev.rawX, ev.rawY)
            t.addMovement(copy)
            copy.recycle()
        }
        root.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = ev.rawY
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().also { track(it, ev) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.let { track(it, ev) }
                    // Só para baixo: puxar para cima não move o painel.
                    val offset = max(0f, ev.rawY - downRawY)
                    if (!dragging && offset > slop) dragging = true
                    if (dragging) root.translationY = offset
                    true
                }
                MotionEvent.ACTION_UP -> {
                    tracker?.let { track(it, ev) }
                    // Toque sem arrasto: é um clique no painel (o leitor de tela também precisa saber disso).
                    if (!dragging) root.performClick()
                    if (dragging) {
                        val offset = max(0f, ev.rawY - downRawY)
                        tracker?.computeCurrentVelocity(1000)
                        val velocityY = tracker?.yVelocity ?: 0f
                        // Fecha se passou de 1/3 da altura ou se foi um arrasto rápido para baixo.
                        if (offset > root.height / 3f || velocityY > minFling * 4) {
                            root.animate().translationY(root.height.toFloat()).setDuration(150)
                                .withEndAction { dialog.cancel() } // roda o onDismiss (não é uma linha escolhida)
                        } else {
                            root.animate().translationY(0f).setDuration(150)
                        }
                    }
                    tracker?.recycle()
                    tracker = null
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) root.animate().translationY(0f).setDuration(150)
                    tracker?.recycle()
                    tracker = null
                    true
                }
                else -> true
            }
        }

        // Alça: a área de toque tem 24 dp de altura para o dedo; a barrinha fica no centro.
        // Decorativa, fora da leitura de tela.
        root.addView(
            FrameLayout(activity).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(
                    View(activity).apply {
                        background = GradientDrawable().apply {
                            cornerRadius = dp(2).toFloat()
                            setColor(activity.getColor(R.color.setup_muted))
                        }
                        alpha = 0.4f
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    FrameLayout.LayoutParams(dp(36), dp(4), Gravity.CENTER),
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)),
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
        contentView?.let { v ->
            root.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        messageText?.let { m ->
            root.addView(TextView(activity).apply {
                text = m
                textSize = 14f
                setTextColor(activity.getColor(R.color.setup_muted_text))
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

        // O título da janela é anunciado pelo leitor de tela quando o painel aparece (API 28+).
        if (Build.VERSION.SDK_INT >= 28) titleText?.let { root.accessibilityPaneTitle = it }
        // Ação "Fechar" no menu de ações do leitor de tela: sem o arrasto, que não existe para quem não toca na tela.
        ViewCompat.addAccessibilityAction(root, activity.getString(R.string.session_close)) { _, _ ->
            dialog.cancel() // roda o onDismiss (não é uma linha escolhida)
            true
        }

        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Depois do conteúdo: o tema de diálogo não pode impor moldura, fundo nem largura menor.
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setWindowAnimations(android.R.style.Animation_InputMethod)
        }
        dialog.show()
        if (Build.VERSION.SDK_INT < 28) titleText?.let { root.announceForAccessibility(it) }
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
            // Anunciada como botão (a linha é um LinearLayout).
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            })

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
                    setTextColor(activity.getColor(if (row.destructive) R.color.setup_error_text else R.color.setup_text))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                row.description?.let { description ->
                    addView(TextView(activity).apply {
                        text = description
                        textSize = 13f
                        setTextColor(activity.getColor(R.color.setup_muted_text))
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
                        // O valor nunca passa de 40% da tela: o título (com peso) fica com o resto.
                        maxWidth = activity.resources.displayMetrics.widthPixels * 40 / 100
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
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
