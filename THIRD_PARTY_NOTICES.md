# Código e programas de terceiros

Este arquivo lista o que vem de outros projetos. Os textos completos das licenças
estão na pasta [`licenses/`](licenses/).

## Termux — terminal-emulator e terminal-view

As pastas `terminal-emulator/` e `terminal-view/` são cópias das bibliotecas de
mesmo nome do [Termux](https://github.com/termux/termux-app) (commit
`8629e632fcb95da272221be327db653fb24befe9`), licenciadas sob a
[Apache License 2.0](licenses/Apache-2.0.txt) (também em
[`terminal-emulator/LICENSE`](terminal-emulator/LICENSE)). Elas derivam do
[Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator)
de Jack Palevich. O código-fonte Java/C dessas pastas não foi modificado; apenas
os arquivos de build (`build.gradle.kts`) foram reescritos para este projeto.

## Gradle Wrapper

A pasta `gradle/wrapper/` e os scripts `gradlew` e `gradlew.bat` são do
[Gradle](https://github.com/gradle/gradle) 8.11.1, licenciados sob a
[Apache License 2.0](licenses/Apache-2.0.txt). O `gradle-wrapper.jar` tem o SHA-256
publicado pelo Gradle (`services.gradle.org`).

## Fontes

Em `app/src/main/res/font/`, sob a SIL Open Font License 1.1 (textos em
[`licenses/`](licenses/)):

| Fonte | Origem | Texto da licença |
| --- | --- | --- |
| JetBrains Mono | https://github.com/JetBrains/JetBrainsMono | [OFL-JetBrainsMono.txt](licenses/OFL-JetBrainsMono.txt) |
| Noto Sans Symbols 2 | https://github.com/notofonts/symbols | [OFL-NotoSansSymbols2.txt](licenses/OFL-NotoSansSymbols2.txt) |
| Noto Sans Symbols | https://github.com/notofonts/symbols | [OFL-NotoSansSymbols.txt](licenses/OFL-NotoSansSymbols.txt) |

## Distribuídos dentro do APK (baixados do Termux na hora do build)

O script `scripts/fetch-proot.sh` baixa estes pacotes `.deb` do repositório oficial
do Termux, nas versões fixadas no próprio script, e confere o SHA-256 de cada um
antes de usar. Os pacotes entram no APK; o código-fonte de cada um está nos links
abaixo.

| Programa | Versão | Licença | Código-fonte | Receita (Termux) | Texto da licença |
| --- | --- | --- | --- | --- | --- |
| proot | 5.1.107.101 | GPL-2.0 | https://github.com/termux/proot | [build.sh](https://github.com/termux/termux-packages/tree/master/packages/proot) | [GPL-2.0.txt](licenses/GPL-2.0.txt) |
| libtalloc (talloc) | 2.5.0 | LGPL-3.0 ou posterior | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz (projeto: https://talloc.samba.org) | [build.sh](https://github.com/termux/termux-packages/tree/master/packages/libtalloc) | [LGPL-3.0.txt](licenses/LGPL-3.0.txt) |
| libandroid-shmem | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem | [build.sh](https://github.com/termux/termux-packages/tree/master/packages/libandroid-shmem) | [BSD-3-Clause-libandroid-shmem.txt](licenses/BSD-3-Clause-libandroid-shmem.txt) |

A receita do Termux para o talloc diz "GPL-3.0", mas o arquivo `LICENSE` do
código-fonte (versão 2.5.0) é a LGPL v3, e os cabeçalhos dos arquivos dizem
"versão 3 ou posterior"; por isso a tabela usa LGPL-3.0. O proot vem do projeto
[PRoot](https://proot-me.github.io/), e o Termux usa o próprio fork em
https://github.com/termux/proot.

## Baixado pelo app no primeiro uso (não vai dentro do APK)

O Linux é a imagem oficial `debian:trixie-slim` do Docker Hub. Ela é baixada
pelo próprio celular na instalação, direto do registro, e conferida pelo
SHA-256. A imagem não faz parte do APK. O Debian é composto por software livre
sob diversas licenças: https://www.debian.org/legal/licenses/
