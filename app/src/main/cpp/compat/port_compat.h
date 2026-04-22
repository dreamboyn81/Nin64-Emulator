#ifndef NIN64_PORT_COMPAT_H
#define NIN64_PORT_COMPAT_H

#include <ctype.h>
#include <errno.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <time.h>
#include <unistd.h>

#ifndef __int64
#define __int64 long long
#endif

#ifndef __inline
#define __inline inline
#endif

#ifndef MAX_PATH
#define MAX_PATH 260
#endif

#ifndef TRUE
#define TRUE 1
#endif

#ifndef FALSE
#define FALSE 0
#endif

#ifndef WINAPI
#define WINAPI
#endif

#ifndef CALLBACK
#define CALLBACK
#endif

#ifndef APIENTRY
#define APIENTRY
#endif

typedef unsigned char BYTE;
typedef unsigned short WORD;
typedef unsigned int DWORD;
typedef int BOOL;
typedef long LONG;
typedef unsigned int UINT;
typedef uintptr_t WPARAM;
typedef intptr_t LPARAM;
typedef long LRESULT;
typedef char TCHAR;
typedef void *HANDLE;
typedef void *HWND;
typedef void *HINSTANCE;
typedef DWORD *LPDWORD;

typedef struct {
    long long QuadPart;
} LARGE_INTEGER;

int memicmp(const void *left, const void *right, size_t size);
int stricmp(const char *left, const char *right);
char *strlwr(char *text);

static inline void Sleep(unsigned int ms)
{
    usleep((useconds_t)ms * 1000u);
}

static inline short GetAsyncKeyState(int key)
{
    (void)key;
    return 0;
}

static inline DWORD GetLastError(void)
{
    return (DWORD)errno;
}

#endif
