#pragma once

#include <windows.h>

#define MINDLESS_PROGRESS_PENDING 0
#define MINDLESS_PROGRESS_RUNNING 1
#define MINDLESS_PROGRESS_COMPLETE 2
#define MINDLESS_PROGRESS_FAILED 3

typedef struct MindlessAuthSharedData {
    char token[512];
    char api_url[256];
    char hwid[256];
    volatile LONG progress_sequence;
    volatile LONG progress_milli;
    volatile LONG progress_state;
    volatile LONG error_code;
    char progress_status[256];
} MindlessAuthSharedData;
