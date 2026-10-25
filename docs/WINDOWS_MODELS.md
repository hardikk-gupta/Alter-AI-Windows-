# Windows model candidates

Use replaceable providers with the same Alter instructions. These are candidates, not Windows speed or accuracy measurements. Android LiteRT .litertlm files and Android JNI/AAR binaries are not Windows GGUF artifacts.

| Hardware | Start with | Tradeoff |
| --- | --- | --- |
| Low-end CPU / about 8 GB system RAM | [Qwen3 0.6B GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF), Q4_K_M | Small/fast candidate, weaker reasoning and multilingual quality; start at 2048 context |
| CPU with more headroom | [Qwen3 1.7B GGUF](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF), Q4_K_M | Middle candidate; benchmark against 0.6B on actual tasks |
| Your Ryzen 7000 HS / RTX 4050 / 16 GB RAM | [Qwen3 4B GGUF](https://huggingface.co/Qwen/Qwen3-4B-GGUF), Q4_K_M | Better-quality candidate; start at 4096 context, partial GPU offload if VRAM is tight |
| Hindi/Hinglish speech | [Whisper multilingual Tiny/Base](https://github.com/ggml-org/whisper.cpp) | Base is a reasonable accuracy/latency comparison; English-only .en weights are unsuitable for Hindi |
| English live dictation | [Moonshine](https://github.com/moonshine-ai/moonshine) | Evaluate current Windows runtime and streaming model support; not a Hindi/Hinglish replacement |

All Qwen recommendations here use official Qwen repositories and Apache-2.0 model licences; verify each artifact/model card at installation time. Qwen being legacy-only in the Android LiteRT catalog does not exclude a separately validated desktop GGUF runtime. No claim is made that these outperform every newer model.

## Manual local chat setup

1. Obtain a current official Windows x64 [llama.cpp release](https://github.com/ggml-org/llama.cpp/releases) and follow its Windows CPU/CUDA requirements. Verify published release checksums/provenance. CUDA packages require a compatible NVIDIA driver/runtime; use CPU if unavailable.
2. Download the chosen GGUF from the official model repository after reviewing the licence. Verify its exact size and SHA-256 against the repository's LFS record at an immutable commit. Keep weights outside Git, for example `%LOCALAPPDATA%\Alter\models`. This build deliberately has no unverified automatic downloader.
3. From PowerShell, run a CPU baseline (adjust executable/model paths):

   ```powershell
   .\llama-server.exe -m 'C:\path\Qwen3-0.6B-Q4_K_M.gguf' --host 127.0.0.1 --port 8080 -c 2048 -ngl 0
   ```

   For your GPU, try the 4B quantization with `-c 4096 -ngl 99`, then reduce layers/context if allocation fails. Reserve memory for Windows, UI, KV cache and runtime workspaces; weights fitting in VRAM does not establish total memory fit. Check actual dedicated memory with Task Manager or `nvidia-smi`; shared/virtual memory is not dedicated VRAM.
4. Start Alter, open Settings → AI models and save the same local port. Send a short prompt in Alter AI. Test Stop, a failed/stopped server, persisted replies after restart and fully offline operation. Non-thinking mode is requested, bounded output is used, and thought blocks are removed before persistence.

Compare cold start, time to first answer, tokens/second, peak system/VRAM and actual English/Hindi/Hinglish task quality. Use the small model as an explicit low-memory choice, never silent cloud fallback. Embeddings, editor model, ASR and TTS need separate acceptance; downloading a chat model does not install those features.
