# Jangada

**Agentes de IA de linha de comando no seu Android — Claude Code, Gemini CLI, Codex, OpenCode e outros. Sem root, sem PC, sem servidor.**

*AI command-line agents on Android — Claude Code, Gemini CLI, Codex, OpenCode and more. No root, no PC, no server.*

> Projeto em construção. Status: **Etapa 3 de 7** — segundo plano e login fácil.

## Como funciona

O Jangada traz um terminal de verdade (o mesmo motor do Termux) e, no
primeiro uso, instala um Linux Debian dentro do app com um toque, via
`proot`. Os agentes de IA rodam nesse Linux exatamente como num PC.

O app terá dois modos:

- **Simples** (padrão): primeiro uso guiado, conversa em balões com ditado
  por voz e botões grandes para permitir ou recusar ações. Para os agentes,
  duas formas de usar: o chat arrumado ou a própria tela do agente, adaptada
  ao celular com botões.
- **Pro**: terminal completo, abas, acesso direto ao Debian e configurações
  avançadas.

## Roteiro

- [x] **1. Terminal base** — teclas extras pensadas para agentes, links clicáveis, pinça para a fonte, copiar e colar, APK assinado.
- [x] **2. Linux dentro do app** — Debian instalado com um toque, com progresso, mensagens claras e retomada se a internet cair.
- [x] **3. Segundo plano e login fácil** — sessões continuam rodando fora da tela; os agentes abrem o navegador sozinhos (login sem copiar e colar); fonte com os símbolos que os agentes usam.
- [ ] **4. Catálogo de agentes** — instalar, atualizar e abrir com um toque. Cada agente é uma receita em JSON; adicionar um novo é um pull request.
- [ ] **5. Modo Simples** — chat nativo e tela do agente adaptada, alternando na mesma conversa.
- [ ] **6. Modo Pro** — abas, chaves de API cifradas, pastas do celular, configurações avançadas.
- [ ] **7. Lançamento** — temas, backup do ambiente, releases para Obtainium/F-Droid.

## Baixar

APK da última compilação: aba **Releases** → *Build mais recente*.
Requer Android 8 ou mais novo, processador ARM de 64 bits e cerca de 700 MB livres.

## Compilar

O caminho oficial é o GitHub Actions (`.github/workflows/build.yml`): cada
push na `main` gera o APK. Localmente: JDK 17, Android SDK + NDK, Gradle
8.11.1, e então:

```
bash scripts/fetch-proot.sh aarch64
gradle :app:assembleRelease
```

Para APKs assinados no seu fork, crie os secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`. Sem eles, o build usa a
chave de debug.

### Por que `targetSdk 28`?

A partir do Android 10, apps com `targetSdk` 29+ não podem executar
programas gravados na própria pasta de dados — e é exatamente isso que um
ambiente Linux faz. O Termux toma a mesma decisão. Consequência: o Jangada
não pode ir para a Play Store, e é distribuído pelo GitHub.

## Licença

[GPL-3.0](LICENSE). Usa as bibliotecas de terminal do Termux (Apache 2.0) e
o proot do Termux (GPL-2.0) — detalhes em [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
