#!/usr/bin/env python3
"""Make ggml's vulkan-shaders-gen spawn glslc with vfork() instead of fork().

Inside the Gradle build on a GitHub runner, fork() of the generator fails with ENOMEM
("Cannot allocate memory" / "Failed to fork process") for hundreds of mul_mm variants: each
fork has to commit a copy of the generator's address space while the Gradle and Kotlin daemons
hold most of the RAM. The generator ignores the failure, the shaders go missing, and the link
of libwhisper_gpu.so fails on matmul_id_* symbols. vfork() commits nothing. The argv vector is
built before the call so the child only does close/dup2/execvp/_exit.
"""
import sys

path = sys.argv[1]
src = open(path).read()
argv_block = """    std::vector<char*> argv;
    for (std::string& part : command) {
        argv.push_back(part.data());
    }
    argv.push_back(nullptr);
"""
fork_line = "    pid_t pid = fork();\n"
if "vfork()" in src:
    print("already patched"); sys.exit(0)
assert src.count(fork_line) == 1 and src.count(argv_block) == 1, "vulkan-shaders-gen.cpp changed; update the patch"
src = src.replace(argv_block, "")
src = src.replace(fork_line, argv_block + "    pid_t pid = vfork();\n")
open(path, "w").write(src)
print("patched", path)
