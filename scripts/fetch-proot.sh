#!/usr/bin/env bash
# Baixa o proot e as bibliotecas de que ele precisa do repositório oficial
# do Termux e coloca tudo nos assets do app (app/src/main/assets/bootstrap).
# Roda no GitHub Actions antes do build; para compilar localmente, rode
# este script uma vez antes do Gradle.
#
# Uso: scripts/fetch-proot.sh [aarch64]
set -euo pipefail

ARCH="${1:-aarch64}"
REPO="https://packages-cf.termux.dev/apt/termux-main"
DEST="app/src/main/assets/bootstrap/$ARCH"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Baixando índice de pacotes do Termux ($ARCH)..."
curl -fsSL --retry 3 "$REPO/dists/stable/main/binary-$ARCH/Packages" -o "$WORK/Packages"

# Extrai do índice o campo $2 do pacote $1 (último registro, se houver mais de um).
field() {
  awk -v pkg="$1" -v key="$2" '
    /^Package: / { cur = substr($0, 10) }
    cur == pkg && index($0, key ": ") == 1 { val = substr($0, length(key) + 3) }
    END { print val }
  ' "$WORK/Packages"
}

fetch() {
  local pkg="$1" file sha
  file="$(field "$pkg" Filename)"
  sha="$(field "$pkg" SHA256)"
  if [ -z "$file" ] || [ -z "$sha" ]; then
    echo "ERRO: pacote '$pkg' não encontrado no índice do Termux" >&2
    exit 1
  fi
  echo "-> $pkg ($(field "$pkg" Version))"
  curl -fsSL --retry 3 "$REPO/$file" -o "$WORK/$pkg.deb"
  echo "$sha  $WORK/$pkg.deb" | sha256sum -c --quiet -
  dpkg-deb -x "$WORK/$pkg.deb" "$WORK/root"
}

fetch proot
fetch libtalloc
fetch libandroid-shmem

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
