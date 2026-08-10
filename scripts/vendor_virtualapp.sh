#!/usr/bin/env bash
set -euo pipefail

repo_url="https://github.com/ServenScorpion/VirtualApp.git"
revision="ae7c4275096b6614fa1aa7befe326630fd3f8833"
target_dir="third_party/virtualapp-upstream"

if [[ -e "$target_dir" ]]; then
  echo "Refusing to overwrite existing $target_dir" >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

git clone "$repo_url" "$tmp_dir/VirtualApp"
git -C "$tmp_dir/VirtualApp" checkout "$revision"

mkdir -p "$target_dir"
cp -R "$tmp_dir/VirtualApp/lib" "$target_dir/lib"
cp "$tmp_dir/VirtualApp/VAConfig.gradle" "$target_dir/VAConfig.gradle"
cp "$tmp_dir/VirtualApp/AppConfig.gradle" "$target_dir/AppConfig.gradle"
cp "$tmp_dir/VirtualApp/build.gradle" "$target_dir/build.gradle"
cp "$tmp_dir/VirtualApp/settings.gradle" "$target_dir/settings.gradle"

cat > "$target_dir/UPSTREAM.txt" <<EOF
Repository: $repo_url
Revision: $revision
Vendored by: scripts/vendor_virtualapp.sh

Only the upstream lib module and root Gradle config files are copied.
The demo app module is intentionally excluded.
EOF

find "$target_dir" -type d -name build -prune -exec rm -rf {} +
find "$target_dir" -type d -name .gradle -prune -exec rm -rf {} +

echo "Vendored VirtualApp lib to $target_dir"
