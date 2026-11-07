#!/usr/bin/env bash
set -euo pipefail

gateway_dir=$(cd -- "$(dirname -- "$0")" && pwd)
rust_target=x86_64-unknown-linux-musl
out_dir="$gateway_dir/../dist"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$gateway_dir/rust/target}"

if [[ -n "${ZIG_BIN:-}" ]]; then
    cross_dir=$(mktemp -d)
    trap 'rm -rf -- "$cross_dir"' EXIT
    export ZIG_BIN
    cat > "$cross_dir/x86_64-linux-musl-gcc" <<'WRAPPER'
#!/usr/bin/env bash
set -euo pipefail
args=()
for arg in "$@"; do
    [[ "$arg" == "--target=x86_64-unknown-linux-musl" ]] || args+=("$arg")
done
exec "$ZIG_BIN" cc -target x86_64-linux-musl "${args[@]}"
WRAPPER
    cat > "$cross_dir/zig-ar" <<'WRAPPER'
#!/usr/bin/env bash
exec "$ZIG_BIN" ar "$@"
WRAPPER
    cat > "$cross_dir/zig-ranlib" <<'WRAPPER'
#!/usr/bin/env bash
exec "$ZIG_BIN" ranlib "$@"
WRAPPER
    chmod +x "$cross_dir/x86_64-linux-musl-gcc" "$cross_dir/zig-ar" "$cross_dir/zig-ranlib"
    export CC_x86_64_unknown_linux_musl="$cross_dir/x86_64-linux-musl-gcc"
    export AR_x86_64_unknown_linux_musl="$cross_dir/zig-ar"
    export RANLIB_x86_64_unknown_linux_musl="$cross_dir/zig-ranlib"
fi
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER="${CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_LINKER:-rust-lld}"
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS="${CARGO_TARGET_X86_64_UNKNOWN_LINUX_MUSL_RUSTFLAGS:--C linker-flavor=ld.lld}"

cargo build --release --locked --target "$rust_target" --manifest-path "$gateway_dir/rust/Cargo.toml"
mkdir -p "$out_dir"
install -m 0755 "$CARGO_TARGET_DIR/$rust_target/release/wecom-gateway" "$out_dir/wecom-gateway-linux-x86_64"
printf '%s\n' "$out_dir/wecom-gateway-linux-x86_64"
