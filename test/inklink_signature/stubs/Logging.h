#pragma once
#include <cstdio>
#define LOG_ERR(tag, fmt, ...) fprintf(stderr, "[ERR] [%s] " fmt "\n", tag, ##__VA_ARGS__)
#define LOG_INF(tag, fmt, ...) fprintf(stderr, "[INF] [%s] " fmt "\n", tag, ##__VA_ARGS__)
#define LOG_DBG(tag, fmt, ...) ((void)0)
