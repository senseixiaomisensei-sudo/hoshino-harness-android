/* SPDX-License-Identifier: MIT -- diagnostic offsets only, no process data. */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unwind.h>
static _Unwind_Reason_Code hs_frame(struct _Unwind_Context *context, void *unused) {
    (void)unused;
    uintptr_t pc=_Unwind_GetIP(context);
    Dl_info info;
    if(pc && dladdr((void *)pc,&info) && info.dli_fname && strstr(info.dli_fname,"libproot"))
        dprintf(2,"HS_PROOT_OFFSET:%zx\n",(size_t)(pc-(uintptr_t)info.dli_fbase));
    return _URC_NO_REASON;
}
static void hs_backtrace(void) { _Unwind_Backtrace(hs_frame, NULL); }
