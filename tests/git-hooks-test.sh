#!/usr/bin/env bash
# A fresh clone must reject staged Kotlin when its formatting check fails.
set -euo pipefail
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT

git init --quiet "$scratch"
cp -R "$repository_root/.githooks" "$scratch/.githooks"
mkdir "$scratch/scripts"
cp "$repository_root/scripts/install-git-hooks.sh" "$scratch/scripts/"
bash "$scratch/scripts/install-git-hooks.sh"
cat > "$scratch/gradlew" <<'GRADLE'
#!/usr/bin/env bash
printf '%s\n' "$*" > formatter-invoked
exit 1
GRADLE
chmod +x "$scratch/gradlew"
printf '%s\n' 'fun unformatted() {}' > "$scratch/Preview.kt"
git -C "$scratch" add Preview.kt
if git -C "$scratch" -c user.name='Yuri Schimke' -c user.email='yuri@schimke.ee' \
  commit --quiet -m 'test: reject unformatted Kotlin' > "$scratch/commit.log" 2>&1; then
  echo 'Formatting hook allowed a commit despite a failing formatter' >&2
  exit 1
fi
if [ ! -f "$scratch/formatter-invoked" ]; then
  cat "$scratch/commit.log" >&2
  echo 'Git did not invoke the formatting check' >&2
  exit 1
fi
grep -q 'ktfmtCheckAll' "$scratch/formatter-invoked"
echo 'Formatting hook rejects unformatted staged Kotlin after installation'
