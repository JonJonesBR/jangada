<p align="center">
  <img src="docs/screenshots/1-inicio.jpg" width="22%" />
  <img src="docs/screenshots/2-chat.jpg" width="22%" />
  <img src="docs/screenshots/3-terminal.jpg" width="22%" />
  <img src="docs/screenshots/4-configuracoes.jpg" width="22%" />
</p>

# Jangada

**Agentes de IA de linha de comando no seu Android — Claude Code, Codex, OpenCode, Qwen Code e outros. Sem root, sem computador, sem servidor.**

*[English below](#english)*

O Jangada traz um Linux Debian completo para dentro do app e deixa você instalar, abrir e conversar com os principais agentes de IA de programação com um toque. Dá para usar como um chat, com corretor e ditado por voz, ou na tela completa de terminal de cada agente.

## Recursos

- **Catálogo de agentes**: Claude Code, Antigravity CLI (agy), Codex, OpenCode, Pi, oh-my-pi, Qwen Code, Hermes Agent, Command Code e OpenClaw. Instalar, atualizar (só quando há versão nova) e desinstalar com um toque, mostrando o espaço que cada um ocupa.
- **Chat**: balões com respostas em tempo real, ações do agente, cartões de permissão (permitir, recusar ou permitir sempre), plano de tarefas, tabelas e blocos de código com botão copiar. Troca de modelo, esforço e modo pelo próprio chat. Funciona com o Claude Code e com os agentes que falam o [Agent Client Protocol](https://agentclientprotocol.com).
- **Chat ⇄ Terminal**: no Claude Code, a mesma conversa nos dois modos.
- **Login guiado**: na primeira vez, o app mostra como entrar na conta de cada agente; o navegador abre sozinho.
- **Anexos**: mande fotos, prints e arquivos para o agente.
- **Terminal de verdade**: abas, teclas extras pensadas para agentes, caixa de texto com corretor e voz, links clicáveis, fontes com os símbolos que os agentes usam e esquemas de cores.
- **Segundo plano**: os agentes continuam trabalhando com a tela apagada ou em outro app.
- **Arquivos do celular**: os agentes leem e salvam em `~/celular` (Downloads, Documentos…); os projetos ficam em `~/projetos`.
- **Modo Simples e Modo Pro**, tema claro, escuro ou do sistema.
- **Chaves de API** guardadas com criptografia (Android Keystore), para quem usa os agentes com chave.
- **Backup** dos projetos e das configurações dos agentes, e **Liberar espaço** com um toque.
- **Sem anúncios e sem rastreamento.** O Jangada só acessa a internet para instalar o Linux e os agentes e para ver se há versões novas; suas conversas vão só para os serviços de IA que você escolher.

## Instalar

1. Baixe o `Jangada.apk` na página de [Releases](https://github.com/JonJonesBR/jangada/releases/latest) e instale (o Android vai pedir permissão para instalar apps desta origem).
2. Abra o app e toque em **Instalar agora**: o Linux é preparado uma única vez (cerca de 70 MB de download).
3. Escolha um agente no catálogo, toque em **Instalar** e depois em **Conversar**.

**Atualizações automáticas:** use o [Obtainium](https://github.com/ImranR98/Obtainium) e adicione o endereço `https://github.com/JonJonesBR/jangada`.

**Requisitos:** Android 8 ou mais novo, processador ARM de 64 bits e cerca de 700 MB livres (mais o espaço dos agentes que você instalar). Cada agente usa a sua própria conta ou chave de API no serviço de IA correspondente.

## Como funciona

O Jangada usa o motor de terminal do [Termux](https://github.com/termux/termux-app) e roda um Debian oficial dentro do app com [proot](https://github.com/termux/proot), sem precisar de root. Os agentes são instalados com os instaladores oficiais de cada projeto. O chat conversa com os agentes pelos protocolos que eles mesmos oferecem para integração com editores.

O app é distribuído pelo GitHub, e não pela Play Store: para executar o Linux dentro do app, ele usa `targetSdk 28`, a mesma escolha do Termux.

## Adicionar um agente

Cada agente é um arquivo JSON em [`app/src/main/assets/agents/`](app/src/main/assets/agents). Para incluir um novo, copie um dos existentes e mande um pull request:

```json
{
  "id": "meu-agente",
  "order": 110,
  "name": "Meu Agente",
  "vendor": "Quem faz",
  "command": "meuagente",
  "install": "curl -fsSL https://exemplo.com/install.sh | bash",
  "update": "meuagente update",
  "uninstall": "rm -rf ~/.meuagente",
  "requires": ["nodejs", "npm"],
  "homepage": "https://exemplo.com",
  "npm": "meu-agente",
  "acp": { "command": "meuagente acp" },
  "description": { "pt": "O que ele faz.", "en": "What it does." },
  "note": { "pt": "Aviso opcional.", "en": "Optional note." }
}
```

- `command` abre o agente; o app usa esse nome para saber se ele está instalado.
- `install` roda dentro do Debian; `requires` instala pacotes do Debian antes.
- `update` e `uninstall` são opcionais (sem `update`, atualizar repete a instalação; o app sempre apaga o comando do agente ao desinstalar).
- `npm` é opcional: o pacote no npm, usado só para descobrir a versão mais nova.
- `acp` é opcional: como abrir o agente no modo [ACP](https://agentclientprotocol.com) para ter chat no app. Com adaptador separado: `{ "command": "pi-acp", "bin": "pi-acp", "install": "npm i -g pi-acp", "requires": ["nodejs", "npm"] }`.

## Compilar

Cada push na `main` gera o APK pelo GitHub Actions (`.github/workflows/build.yml`). Para compilar localmente: JDK 17, Android SDK com NDK e Gradle 8.11.1.

```
bash scripts/fetch-proot.sh aarch64
gradle :app:assembleRelease
```

Para APKs assinados num fork, crie os secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`; sem eles, o build usa a chave de debug.

## Licença

[GPL-3.0](LICENSE). Créditos e licenças dos componentes de terceiros em [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## English

**AI command-line agents on Android — Claude Code, Codex, OpenCode, Qwen Code and more. No root, no computer, no server.**

Jangada brings a full Debian Linux inside the app and lets you install, open and talk to the main AI coding agents with one tap — as a chat (with autocorrect and voice typing) or in each agent’s full terminal screen.

**Features:** agent catalog with one-tap install, update and uninstall · native chat with live answers, permission cards, plans, tables and code blocks, plus model/effort/mode switching (Claude Code and [Agent Client Protocol](https://agentclientprotocol.com) agents) · same conversation in chat and terminal for Claude Code · guided first login · photo and file attachments · real terminal with tabs, agent-friendly extra keys and color schemes · keeps working in the background · access to your phone files · Simple and Pro modes, light/dark theme · encrypted API keys (Android Keystore) · backup and one-tap cleanup · no ads, no tracking.

**Install:** download `Jangada.apk` from [Releases](https://github.com/JonJonesBR/jangada/releases/latest), or track `https://github.com/JonJonesBR/jangada` with [Obtainium](https://github.com/ImranR98/Obtainium). Requires Android 8+, a 64-bit ARM device and about 700 MB free. Each agent uses your own account or API key with its AI provider.

**How it works:** Termux’s terminal engine plus an official Debian running through proot, without root. Distributed through GitHub (it targets SDK 28, like Termux, to run Linux inside the app).

**License:** [GPL-3.0](LICENSE) — third-party notices in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
