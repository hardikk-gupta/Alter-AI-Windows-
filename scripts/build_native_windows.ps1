$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projects = @(
  @{ Name = "llama"; Url = "https://github.com/ggml-org/llama.cpp.git"; Ref = "b11878"; Sha = "a55c16e4ee532aa36d55f5756f3ba28a21a2b62b" },
  @{ Name = "whisper"; Url = "https://github.com/ggml-org/whisper.cpp.git"; Ref = "v1.9.5"; Sha = "d1be6fde11ac6e0407606b4e42fe72d34add8037" }
)
$destination = Join-Path $PWD "build\package-resources\windows\native"
foreach ($project in $projects) {
  $source = Join-Path $PWD "build\native-source\$($project.Name)"
  git clone --depth 1 --branch $project.Ref $project.Url $source
  if ($LASTEXITCODE -ne 0) { throw "Could not fetch $($project.Name) source" }
  $actual = (git -C $source rev-parse HEAD).Trim()
  if ($actual -ne $project.Sha) { throw "Unexpected $($project.Name) source commit: $actual" }
  $build = Join-Path $PWD "build\native-build\$($project.Name)"
  if ($project.Name -eq "llama") {
    cmake -S $source -B $build -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=ON -DLLAMA_USE_PREBUILT_UI=OFF -DGGML_CUDA=OFF -DGGML_NATIVE=OFF -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
  } else {
    cmake -S $source -B $build -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_SERVER=OFF -DGGML_NATIVE=OFF -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
  }
  if ($LASTEXITCODE -ne 0) { throw "Could not configure $($project.Name)" }
  $targetName = if ($project.Name -eq "llama") { "llama-server" } else { "whisper-cli" }
  cmake --build $build --config Release --target $targetName --parallel 4
  if ($LASTEXITCODE -ne 0) { throw "Could not build $($project.Name)" }
  $binaryDir = Join-Path $build "bin\Release"
  $target = Join-Path $destination $project.Name
  New-Item -ItemType Directory -Force $target | Out-Null
  Get-ChildItem $binaryDir -File | Where-Object { $_.Extension -in @('.exe', '.dll') } | Copy-Item -Destination $target
  Copy-Item (Join-Path $source "LICENSE") -Destination (Join-Path $target "LICENSE")
  $executable = if ($project.Name -eq "llama") { "llama-server.exe" } else { "whisper-cli.exe" }
  if (-not (Test-Path (Join-Path $target $executable))) { throw "Missing $executable" }
  & (Join-Path $target $executable) --version | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "$executable did not launch on Windows" }
}
