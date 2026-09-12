FROM rust:1-bookworm

ENV RUSTUP_TOOLCHAIN=stable \
    XWIN_ACCEPT_LICENSE=1 \
    XWIN_CACHE_DIR=/usr/local/cargo/xwin-cache

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3-pip cabextract \
 && rm -rf /var/lib/apt/lists/*

RUN pip3 install --break-system-packages cargo-zigbuild ziglang==0.13.0
RUN cargo install --locked cargo-xwin

RUN rustup target add \
      aarch64-unknown-linux-gnu \
      x86_64-pc-windows-msvc \
      aarch64-pc-windows-msvc \
      x86_64-apple-darwin \
      aarch64-apple-darwin

RUN cargo new --lib --vcs none /tmp/xwin-prewarm \
 && cd /tmp/xwin-prewarm \
 && cargo xwin build --release --target x86_64-pc-windows-msvc \
 && cargo xwin build --release --target aarch64-pc-windows-msvc \
 && rm -rf /tmp/xwin-prewarm

WORKDIR /work
