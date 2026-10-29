# Jangada

**Agentes de IA de linha de comando no seu Android — Claude Code, Gemini CLI, Codex, OpenCode e outros. Sem root, sem PC, sem servidor.**

*AI command-line agents on Android — Claude Code, Gemini CLI, Codex, OpenCode and more. No root, no PC, no server.*

> Projeto em construção. Status: **Etapa 6 de 7** — Modo Pro.

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
- [x] **4. Catálogo de agentes** — instalar e atualizar na própria tela, com progresso; "Atualizar" só aparece quando há versão nova: Claude Code, Antigravity CLI (agy), Codex, OpenCode, Pi, oh-my-pi, Qwen Code, Hermes Agent, Command Code e OpenClaw.
- [x] **Extras** — caixa de texto com corretor e ditado por voz (✎); comandos com `/` ou `!` vão sem corretor e como digitação; agentes trabalham em `~/projetos`, arquivos do celular em `~/celular`.
- [x] **5. Modo Simples** — chat nativo para o Claude Code (balões, permissões com botões, respostas em tempo real, histórico de conversas, escolha do modelo) alternando com o terminal na mesma conversa; configurações (Simples/Pro, Enter envia, permissões).
- [x] **Chat para todos os agentes** — via [Agent Client Protocol](https://agentclientprotocol.com) para OpenCode, Codex, Pi, oh-my-pi, Qwen Code, Hermes e Command Code; anexos de fotos e arquivos; "Permitir sempre nesta conversa"; copiar blocos de código.
- [x] **6. Modo Pro** — abas de terminal, chaves de API com criptografia (Android Keystore), opções avançadas (processador ativo, tela ligada, teclas extras), reparar e reinstalar o Linux, chat experimental do Antigravity.
- [ ] **7. Lançamento** — temas, backup do ambiente, releases para Obtainium/F-Droid.

## Adicionar um agente

Cada agente é um arquivo JSON em `app/src/main/assets/agents/`. Para incluir
um novo, copie um dos existentes e mande um pull request:

```json
{
  "id": "meu-agente",
  "order": 110,
  "name": "Meu Agente",
  "vendor": "Quem faz",
  "command": "meuagente",
  "install": "curl -fsSL https://exemplo.com/install.sh | bash",
  "update": "meuagente update",
  "requires": ["nodejs", "npm"],
  "homepage": "https://exemplo.com",
  "npm": "meu-agente",
  "description": { "pt": "O que ele faz.", "en": "What it does." },
  "note": { "pt": "Aviso opcional.", "en": "Optional note." }
}
```

- `command` é o que abre o agente; o app usa esse nome para saber se ele está instalado.
- `install` roda dentro do Debian do app; `requires` instala pacotes do Debian antes.
- `update` é opcional; sem ele, o botão Atualizar repete a instalação.
- `npm` é opcional: o nome do pacote no npm, usado só para descobrir a versão mais nova.
- `acp` é opcional: como abrir o agente no modo [ACP](https://agentclientprotocol.com) para ter chat no app.
  Ex.: `{ "command": "opencode acp" }`, ou com adaptador separado:
  `{ "command": "pi-acp", "bin": "pi-acp", "install": "npm i -g pi-acp", "requires": ["nodejs", "npm"] }`.
  Com ele, "Atualizar" só aparece quando existe versão mais nova que a instalada.

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
