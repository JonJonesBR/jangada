# Código e programas de terceiros

## Termux — terminal-emulator e terminal-view

As pastas `terminal-emulator/` e `terminal-view/` são cópias das bibliotecas de
mesmo nome do [Termux](https://github.com/termux/termux-app) (commit
`8629e632fcb95da272221be327db653fb24befe9`), licenciadas sob a
[Apache License 2.0](terminal-emulator/LICENSE). Elas derivam do
[Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)
de Jack Palevich. O código-fonte Java/C dessas pastas não foi modificado; apenas
os arquivos de build (`build.gradle.kts`) foram reescritos para este projeto.

## Fontes

Em `app/src/main/res/font/`, sob a SIL Open Font License 1.1 (textos em `licenses/`):

| Fonte | Origem |
| --- | --- |
| JetBrains Mono | https://github.com/JetBrains/JetBrainsMono |
| Noto Sans Symbols 2 | https://github.com/notofonts/symbols |
| Noto Sans Symbols | https://github.com/notofonts/symbols |

## Distribuídos dentro do APK (baixados do Termux na hora do build)

O script `scripts/fetch-proot.sh` baixa os pacotes compilados pelo Termux,
conferindo o SHA-256 publicado no índice oficial:

| Programa | Licença | Código-fonte |
| --- | --- | --- |
| proot | GPL-2.0 | https://github.com/termux/proot |
| talloc | GNU LGPL/GPL v3 | https://talloc.samba.org |
| libandroid-shmem | BSD 3-Clause | https://github.com/termux/libandroid-shmem |

## Baixado pelo app no primeiro uso

O Linux é a imagem oficial `debian:trixie-slim` do Docker Hub, baixada
diretamente do registro e conferida pelo SHA-256. O Debian é composto por
software livre sob diversas licenças: https://www.debian.org/legal/licenses/
