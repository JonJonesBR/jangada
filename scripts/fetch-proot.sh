#!/usr/bin/env bash
# Baixa o proot e as bibliotecas de que ele precisa do repositório oficial
# do Termux e coloca tudo nos assets do app (app/src/main/assets/bootstrap).
# Roda no GitHub Actions antes do build; para compilar localmente, rode
# este script uma vez antes do Gradle.
#
# Uso: scripts/fetch-proot.sh [aarch64]   (ou --latest para ver versões novas)
set -euo pipefail

ARCH="${1:-aarch64}"
REPO="https://packages-cf.termux.dev/apt/termux-main"
DEST="app/src/main/assets/bootstrap/$ARCH"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Versões fixas (arquivo e SHA-256 do repositório do Termux). Assim o mesmo
# commit sempre gera o mesmo proot, e uma atualização do Termux não muda o
# app sem ninguém ver. Para atualizar: rode "scripts/fetch-proot.sh --latest",
# troque as linhas abaixo e faça um commit só disso.
PINS_aarch64=(
  "proot pool/main/p/proot/proot_5.1.107.101_aarch64.deb a28355e9be257e1f87f4e9294b3118470d4fd7a5442fed8b278f4443c83407d7"
  "libtalloc pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"
  "libandroid-shmem pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
)

# Mostra as versões mais novas do índice, no formato das linhas acima.
latest() {
  curl -fsSL --retry 3 --connect-timeout 20 --max-time 300 "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$WORK/Packages"
  for pkg in proot libtalloc libandroid-shmem; do
    awk -v pkg="$pkg" '
      /^Package: / { cur = substr($0, 10) }
      cur == pkg && /^Filename: / { file = substr($0, 11) }
      cur == pkg && /^SHA256: / { sha = substr($0, 9) }
      END { printf "  \"%s %s %s\"\n", pkg, file, sha }
    ' "$WORK/Packages"
  done
}

fetch() {
  local pkg="$1" file="$2" sha="$3"
  echo "-> $pkg (${file##*/})"
  if ! curl -fsSL --retry 3 --connect-timeout 20 --max-time 300 "$REPO/$file" -o "$WORK/$pkg.deb"; then
    echo "ERRO: $file não está mais no repositório do Termux." >&2
    echo "Versões atuais (troque os PINS deste script):" >&2
    latest >&2
    exit 1
  fi
  echo "$sha  $WORK/$pkg.deb" | sha256sum -c --quiet -
  dpkg-deb -x "$WORK/$pkg.deb" "$WORK/root"
}

if [ "$ARCH" = "--latest" ]; then
  ARCH=aarch64
  latest
  exit 0
fi

pins="PINS_$ARCH[@]"
if [ -z "${!pins+x}" ]; then
  echo "ERRO: sem versões fixadas para $ARCH" >&2
  exit 1
fi
for pin in "${!pins}"; do
  # shellcheck disable=SC2086
  fetch $pin
done

PREFIX="$WORK/root/data/data/com.termux/files/usr"
rm -rf "$DEST"
mkdir -p "$DEST"
cp -L "$PREFIX/bin/proot" "$DEST/proot"
cp -L "$PREFIX/libexec/proot/loader" "$DEST/loader"
if [ -f "$PREFIX/libexec/proot/loader32" ]; then
  cp -L "$PREFIX/libexec/proot/loader32" "$DEST/loader32"
fi

# Copia cada biblioteca que o proot (e as bibliotecas dele) pedem e que não
# fazem parte do próprio Android.
needed() { readelf -d "$1" | sed -n 's/.*Shared library: \[\(.*\)\]/\1/p'; }
queue=("$DEST/proot")
while [ "${#queue[@]}" -gt 0 ]; do
  current="${queue[0]}"
  queue=("${queue[@]:1}")
  for lib in $(needed "$current"); do
    [ -e "$DEST/$lib" ] && continue
    if [ -e "$PREFIX/lib/$lib" ]; then
      cp -L "$PREFIX/lib/$lib" "$DEST/$lib"
      queue+=("$DEST/$lib")
    fi
  done
done

for lib in $(needed "$DEST/proot"); do
  case "$lib" in
    libc.so|libdl.so|libm.so|liblog.so) ;;
    *) [ -e "$DEST/$lib" ] || { echo "ERRO: biblioteca $lib não encontrada" >&2; exit 1; } ;;
  esac
done

echo "Pronto:"
ls -la "$DEST"
