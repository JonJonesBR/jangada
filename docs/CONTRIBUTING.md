# Contribuir com o Jangada

*[English below](#english)*

Este guia é para quem desenvolve o Jangada. Para usar o app, veja o [README](../README.md).

## Adicionar um agente

Cada agente é um arquivo JSON em [`app/src/main/assets/agents/`](../app/src/main/assets/agents). Para incluir um novo, copie um dos existentes e mande um pull request:

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

Cada push na `main` gera o APK pelo GitHub Actions (`.github/workflows/build.yml`). Para compilar localmente: JDK 17 e Android SDK com NDK. O Gradle 8.11.1 já vem com o projeto (`./gradlew`).

```
bash scripts/fetch-proot.sh aarch64
./gradlew :app:assembleRelease
```

Para APKs assinados num fork, crie os secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`; sem eles, o build usa a chave de debug.

## Publicar uma versão

1. Suba `versionCode` e `versionName` em `app/build.gradle.kts`.
2. Crie `fastlane/metadata/android/pt-BR/changelogs/<versionCode>.txt` e `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, e atualize o `CHANGELOG.md`.
3. Crie a tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.

O workflow só publica a tag se o número bater com o `versionName`, se os dois changelogs existirem e se o APK for assinado com a chave do projeto. As notas da versão saem dos dois changelogs (pt-BR e en-US).

---

## English

This guide is for developers of Jangada. To use the app, see the [README](../README.md).

## Adding an agent

Each agent is a JSON file in [`app/src/main/assets/agents/`](../app/src/main/assets/agents). To add one, copy an existing file and open a pull request:

```json
{
  "id": "my-agent",
  "order": 110,
  "name": "My Agent",
  "vendor": "Who makes it",
  "command": "myagent",
  "install": "curl -fsSL https://example.com/install.sh | bash",
  "update": "myagent update",
  "uninstall": "rm -rf ~/.myagent",
  "requires": ["nodejs", "npm"],
  "homepage": "https://example.com",
  "npm": "my-agent",
  "acp": { "command": "myagent acp" },
  "description": { "pt": "O que ele faz.", "en": "What it does." },
  "note": { "pt": "Aviso opcional.", "en": "Optional note." }
}
```

- `command` opens the agent; the app uses this name to know whether it is installed.
- `install` runs inside Debian; `requires` installs Debian packages first.
- `update` and `uninstall` are optional (without `update`, updating reruns the install; the app always deletes the agent command when uninstalling).
- `npm` is optional: the npm package, used only to find the latest version.
- `acp` is optional: how to open the agent in [ACP](https://agentclientprotocol.com) mode to get chat in the app. With a separate adapter: `{ "command": "pi-acp", "bin": "pi-acp", "install": "npm i -g pi-acp", "requires": ["nodejs", "npm"] }`.

## Building

Every push to `main` builds the APK with GitHub Actions (`.github/workflows/build.yml`). To build locally: JDK 17 and Android SDK with NDK. Gradle 8.11.1 comes with the project (`./gradlew`).

```
bash scripts/fetch-proot.sh aarch64
./gradlew :app:assembleRelease
```

For signed APKs in a fork, create the secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`; without them, the build uses the debug key.

## Publishing a version

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Create `fastlane/metadata/android/pt-BR/changelogs/<versionCode>.txt` and `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, and update `CHANGELOG.md`.
3. Create the tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.

The workflow publishes the tag only if the number matches `versionName`, if both changelogs exist, and if the APK is signed with the project key. The release notes are built from both changelogs (pt-BR and en-US).
