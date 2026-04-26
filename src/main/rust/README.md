for cross compilation you must do 
    
```bash
rustup target add aarch64-unknown-linux-gnu --toolchain stable-2026-04-16
rustup target add x86_64-pc-windows-gnu --toolchain stable-2026-04-16
```
or whatever is the channel in rust-toolchain.toml is first
