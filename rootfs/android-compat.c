/* SPDX-License-Identifier: MIT
 * Android's app seccomp policy admits the *at filesystem syscalls used by
 * bionic, but not every legacy x86_64 syscall used by musl. Keep the POSIX
 * operation and its error semantics; do not suppress errors or change dsh.
 * This library is preloaded only into the Linux guest, never the Android app.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <ctype.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

int rename(const char *from, const char *to) {
    int result = syscall(SYS_renameat2, AT_FDCWD, from, AT_FDCWD, to, 0);
    if (result == -1 && errno == ENOSYS)
        return syscall(SYS_renameat, AT_FDCWD, from, AT_FDCWD, to);
    return result;
}
int unlink(const char *path) {
    return syscall(SYS_unlinkat, AT_FDCWD, path, 0);
}
int rmdir(const char *path) {
    return syscall(SYS_unlinkat, AT_FDCWD, path, AT_REMOVEDIR);
}
int mkdir(const char *path, mode_t mode) {
    return syscall(SYS_mkdirat, AT_FDCWD, path, mode);
}
/* dsh-fs-local publishes a synced staging file with link(), then removes its
 * staging directory. PRoot emulates that link inside the directory being
 * removed. Recognize only this private staging layout and publish with the
 * kernel's atomic no-replace rename instead. Existing targets remain intact;
 * all other link() calls retain their normal behavior. No upstream code changes.
 */
static int dsh_staged_create(const char *from, const char *to) {
    const char *name = strrchr(to, '/');
    if (!name || !name[1]) return 0;
    size_t directory = (size_t)(name - to + 1);
    name++;
    size_t length = strlen(name);
    if (strncmp(from, to, directory) != 0) return 0;
    const char *part = from + directory;
    if (*part++ != '.' || strncmp(part, name, length) != 0) return 0;
    part += length;
    if (*part++ != '.' || !isdigit((unsigned char)*part)) return 0;
    while (isdigit((unsigned char)*part)) part++;
    if (*part++ != '.') return 0;
    for (int i = 0; i < 36; i++) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (*part++ != '-') return 0;
        } else if (!isxdigit((unsigned char)*part++)) return 0;
    }
    if (strncmp(part, ".tmpdir/", 8) != 0) return 0;
    part += 8;
    return strncmp(part, name, length) == 0 && strcmp(part + length, ".tmp") == 0;
}
int link(const char *from, const char *to) {
    if (dsh_staged_create(from, to))
        return syscall(SYS_renameat2, AT_FDCWD, from, AT_FDCWD, to, 1 /* RENAME_NOREPLACE */);
    return syscall(SYS_linkat, AT_FDCWD, from, AT_FDCWD, to, 0);
}
int symlink(const char *target, const char *path) {
    return syscall(SYS_symlinkat, target, AT_FDCWD, path);
}
int chmod(const char *path, mode_t mode) {
    return syscall(SYS_fchmodat, AT_FDCWD, path, mode);
}
