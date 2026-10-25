# Windows model candidates

Use replaceable providers with the same Alter instructions. These are candidates, not Windows speed or accuracy measurements. Android LiteRT .litertlm files and Android JNI/AAR binaries are not Windows GGUF artifacts.

| Hardware | Start with | Tradeoff |
| --- | --- | --- |
| Low-end CPU / about 8 GB system RAM | [Qwen3 0.6B GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF), Q8_0 | Official 640 MB file; weaker reasoning and multilingual quality; 2048 context |
| CPU with more headroom | [Qwen3 1.7B GGUF](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF), Q4_K_M | Middle candidate; benchmark against 0.6B on actual tasks |
| Your Ryzen 7000 HS / RTX 4050 / 16 GB RAM | [Qwen3 4B GGUF](https://huggingface.co/Qwen/Qwen3-4B-GGUF), Q4_K_M | Better-quality candidate; start at 4096 context, partial GPU offload if VRAM is tight |
| Hindi/Hinglish speech | [Whisper multilingual Tiny/Base](https://github.com/ggml-org/whisper.cpp) | Base is a reasonable accuracy/latency comparison; English-only .en weights are unsuitable for Hindi |
| English live dictation | [Moonshine](https://github.com/moonshine-ai/moonshine) | Evaluate current Windows runtime and streaming model support; not a Hindi/Hinglish replacement |

All Qwen recommendations here use official Qwen repositories and Apache-2.0 model licences; verify each artifact/model card at installation time. Qwen being legacy-only in the Android LiteRT catalog does not exclude a separately validated desktop GGUF runtime. No claim is made that these outperform every newer model.

## Manual local chat setup

1. Open Settings → AI models. Download Qwen3 0.6B (low memory) or 4B (16 GB system). The app uses pinned official revisions and checks exact size and SHA-256 before activation. Weights live in `%LOCALAPPDATA%\Alter\models`, outside Git and the app ZIP.
2. Tap **Use model**. The bundled CPU llama.cpp server starts on `127.0.0.1:8080`; wait for the ready status, then chat. Windows native source is pinned and built in CI with its licence beside the binaries. There is no cloud fallback.
3. For the user's RTX 4050, obtain a compatible official Windows CUDA [llama.cpp release](https://github.com/ggml-org/llama.cpp/releases), keep its DLLs beside `llama-server.exe`, then use **Use NVIDIA GPU runtime…** in Settings → AI models to select that executable. Alter starts it with up to 99 GPU layers; reduce context or switch back to the included CPU runtime if allocation fails. Confirm actual dedicated VRAM first. The app ZIP ships a CPU runtime so low-end PCs work without CUDA. A manual CPU alternative from PowerShell is:

   ```powershell
   .\llama-server.exe -m 'C:\path\Qwen3-0.6B-Q8_0.gguf' --host 127.0.0.1 --port 8080 -c 2048 -ngl 0
   ```

   For your GPU, try the 4B quantization with `-c 4096 -ngl 99`, then reduce layers/context if allocation fails. Reserve memory for Windows, UI, KV cache and runtime workspaces; weights fitting in VRAM does not establish total memory fit. Check actual dedicated memory with Task Manager or `nvidia-smi`; shared/virtual memory is not dedicated VRAM.
4. Download Whisper Tiny or Base and select it for voice. In Alter AI, tap **Mic**, speak, then **Stop mic**. Review the transcript before sending. Hindi/Hinglish quality needs real microphone acceptance. Android accessibility insertion and assistant bubble are pending.

Compare cold start, time to first answer, tokens/second, peak system/VRAM and actual English/Hindi/Hinglish task quality. Use the small model as an explicit low-memory choice, never silent cloud fallback. Embeddings, editor model, ASR and TTS need separate acceptance; downloading a chat model does not install those features.
