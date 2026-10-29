package io.github.jonjonesbr.jangada

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity

/**
 * Base das telas do app (exceto o terminal, que é sempre escuro): aplica o
 * tema escolhido nas configurações — claro, escuro ou o do sistema.
 */
open class JangadaActivity : ComponentActivity() {

    private var createdWithTheme = themeGeneration

    override fun onResume() {
        super.onResume()
        // O tema mudou em outra tela (configurações): redesenha esta também.
        if (createdWithTheme != themeGeneration) {
            createdWithTheme = themeGeneration
            recreate()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val night = when (AppSettings(newBase).appTheme) {
            AppSettings.THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
            AppSettings.THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
            else -> null
        }
        if (night == null) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    companion object {
        /** Muda sempre que o usuário troca o tema; telas abertas se atualizam ao voltar. */
        @Volatile var themeGeneration = 0
    }
}
