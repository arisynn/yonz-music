/* SPDX-License-Identifier: GPL-3.0-or-later
 * Restricted Android command for bundled yt-dlp EJS. No std/os modules, imports,
 * process APIs, sockets, filesystem helpers or dynamically loaded native code.
 * The one input script is provided by the reviewed Python bridge in private storage.
 */
#include "quickjs.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double deadline;
static size_t written;
static double seconds_now(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (double)now.tv_sec + (double)now.tv_nsec / 1e9;
}
static int interrupted(JSRuntime *runtime, void *opaque) {
    (void)runtime; (void)opaque;
    return seconds_now() > deadline;
}
static JSValue log_line(JSContext *context, JSValueConst self, int argc, JSValueConst *argv) {
    (void)self;
    for (int i = 0; i < argc; i++) {
        size_t size = 0;
        const char *text = JS_ToCStringLen(context, &size, argv[i]);
        if (!text) return JS_EXCEPTION;
        written += size + 1;
        if (written > 1024 * 1024) {
            JS_FreeCString(context, text);
            return JS_ThrowRangeError(context, "EJS output limit exceeded");
        }
        if (i) putchar(' ');
        fwrite(text, 1, size, stdout);
        JS_FreeCString(context, text);
    }
    putchar('\n');
    return JS_UNDEFINED;
}
static int exception(JSContext *context) {
    JSValue error = JS_GetException(context);
    const char *text = JS_ToCString(context, error);
    fprintf(stderr, "%.2048s\n", text ? text : "JavaScript evaluation failed");
    JS_FreeCString(context, text); JS_FreeValue(context, error);
    return 1;
}
int main(int argc, char **argv) {
    if (argc == 2 && (!strcmp(argv[1], "--help") || !strcmp(argv[1], "--version"))) {
        printf("QuickJS-ng version %s\nYono restricted EJS runner\n", JS_GetVersion());
        return 0;
    }
    if (argc != 3 || (strcmp(argv[1], "--script") && strcmp(argv[1], "-e"))) return 2;
    char *script = NULL;
    size_t length = 0;
    if (!strcmp(argv[1], "-e")) {
        length = strlen(argv[2]);
        if (length > 4 * 1024 * 1024) return 2;
        script = strdup(argv[2]);
    } else {
        FILE *file = fopen(argv[2], "rb");
        if (!file) return 2;
        if (fseek(file, 0, SEEK_END)) { fclose(file); return 2; }
        long size = ftell(file);
        if (size < 0 || size > 4 * 1024 * 1024) { fclose(file); return 2; }
        rewind(file);
        length = (size_t)size;
        script = malloc(length + 1);
        if (!script || fread(script, 1, length, file) != length) { fclose(file); free(script); return 2; }
        script[length] = 0; fclose(file);
    }
    if (!script) return 2;
    JSRuntime *runtime = JS_NewRuntime();
    if (!runtime) { free(script); return 2; }
    JS_SetMemoryLimit(runtime, 192 * 1024 * 1024);
    JS_SetMaxStackSize(runtime, 4 * 1024 * 1024);
    deadline = seconds_now() + 20;
    JS_SetInterruptHandler(runtime, interrupted, NULL);
    JSContext *context = JS_NewContext(runtime);
    if (!context) { JS_FreeRuntime(runtime); free(script); return 2; }
    JSValue global = JS_GetGlobalObject(context);
    JSValue console = JS_NewObject(context);
    JS_SetPropertyStr(context, console, "log", JS_NewCFunction(context, log_line, "log", 1));
    JS_SetPropertyStr(context, global, "console", console);
    JS_SetPropertyStr(context, global, "print", JS_NewCFunction(context, log_line, "print", 1));
    JS_FreeValue(context, global);
    JSValue result = JS_Eval(context, script, length, "yono-ejs.js", JS_EVAL_TYPE_GLOBAL);
    free(script);
    int status = JS_IsException(result) ? exception(context) : 0;
    JS_FreeValue(context, result);
    JSContext *job_context;
    int jobs = 0;
    while (!status && JS_IsJobPending(runtime)) {
        if (++jobs > 100000 || seconds_now() > deadline) { status = 1; break; }
        if (JS_ExecutePendingJob(runtime, &job_context) < 0) status = exception(job_context);
    }
    JS_FreeContext(context); JS_FreeRuntime(runtime);
    return status;
}
