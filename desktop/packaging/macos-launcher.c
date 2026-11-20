#include <limits.h>
#include <mach-o/dyld.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#if defined(__arm64__) || defined(__aarch64__)
#define PAYLOAD_ARCH "arm64"
#elif defined(__x86_64__)
#define PAYLOAD_ARCH "x86_64"
#else
#error Unsupported macOS architecture
#endif

int main(int argc, char *argv[]) {
    char executable[PATH_MAX];
    char resolved[PATH_MAX];
    uint32_t executable_size = sizeof(executable);
    if (_NSGetExecutablePath(executable, &executable_size) != 0) {
        fputs("OpenTune: executable path is too long\n", stderr);
        return EXIT_FAILURE;
    }
    if (!realpath(executable, resolved)) {
        perror("OpenTune: could not locate the application bundle");
        return EXIT_FAILURE;
    }

    char *name = strrchr(resolved, '/');
    if (!name || argc == 0) {
        fputs("OpenTune: invalid launcher path or arguments\n", stderr);
        return EXIT_FAILURE;
    }
    *name = '\0';

    char launcher[PATH_MAX];
    int length = snprintf(launcher, sizeof(launcher),
        "%s/../Helpers/OpenTune-%s.app/Contents/MacOS/OpenTune", resolved, PAYLOAD_ARCH);
    if (length < 0 || (size_t)length >= sizeof(launcher)) {
        fputs("OpenTune: bundled launcher path is too long\n", stderr);
        return EXIT_FAILURE;
    }

    // Keep the process and its arguments, while jpackage finds the matching JDK and jars.
    argv[0] = launcher;
    execv(launcher, argv);
    perror("OpenTune: could not start the bundled application");
    return EXIT_FAILURE;
}
