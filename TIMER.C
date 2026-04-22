#include "ultra.h"

static qword timer_now_us(void)
{
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (qword)ts.tv_sec * 1000000ULL + (qword)(ts.tv_nsec / 1000);
}

static qword timer_get_zero(Timer *t)
{
    qword value;
    memcpy(&value, t->perf_zero, sizeof(value));
    return value;
}

static void timer_set_zero(Timer *t, qword value)
{
    memcpy(t->perf_zero, &value, sizeof(value));
}

void timer_reset(Timer *t)
{
    timer_set_zero(t, timer_now_us());
}

int timer_us(Timer *t)
{
    return (int)(timer_now_us() - timer_get_zero(t));
}

int timer_ms(Timer *t)
{
    return timer_us(t) / 1000;
}

int timer_usreset(Timer *t)
{
    qword now = timer_now_us();
    qword elapsed = now - timer_get_zero(t);

    timer_set_zero(t, now);
    return (int)elapsed;
}

int timer_msreset(Timer *t)
{
    return timer_usreset(t) / 1000;
}


