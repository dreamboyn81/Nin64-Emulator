#ifndef NIN64_ULTRA_WRAPPER_H
#define NIN64_ULTRA_WRAPPER_H

#include "ULTRA.H"

#undef mempage
#undef memoffs
#undef memdatar
#undef memdataw

#define mempage(x)  ((dword)((dword)(x) >> 12))
#define memoffs(x)  ((dword)((dword)(x) & 0xfff))
#define memdatar(x) ((dword *)(mem.lookupr[mempage((dword)(x))] + (dword)(x)))
#define memdataw(x) ((dword *)(mem.lookupw[mempage((dword)(x))] + (dword)(x)))

#endif
