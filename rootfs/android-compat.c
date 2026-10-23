/* SPDX-License-Identifier: MIT
 * Android's app seccomp policy admits the *at filesystem syscalls used by
 * bionic, but not every legacy x86_64 syscall used by musl. Keep the POSIX
 * operation and its error semantics; do not suppress errors or change dsh.
 * This library is preloaded only into the Linux guest, never the Android app.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
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
int link(const char *from, const char *to) {
    return syscall(SYS_linkat, AT_FDCWD, from, AT_FDCWD, to, 0);
}
int symlink(const char *target, const char *path) {
    return syscall(SYS_symlinkat, target, AT_FDCWD, path);
}
int chmod(const char *path, mode_t mode) {
    return syscall(SYS_fchmodat, AT_FDCWD, path, mode);
}
