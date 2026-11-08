#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_io_github_heavencanceller_vproxy_NativeCore_spawn(
    JNIEnv *env, jclass type, jstring engine, jstring directory,
    jstring config, jstring log, jint tun_fd) {
    (void)type;
    const char *exe = (*env)->GetStringUTFChars(env, engine, NULL);
    const char *dir = (*env)->GetStringUTFChars(env, directory, NULL);
    const char *cfg = (*env)->GetStringUTFChars(env, config, NULL);
    const char *log_path = (*env)->GetStringUTFChars(env, log, NULL);
    if (!exe || !dir || !cfg || !log_path) return -ENOMEM;
    int log_fd = open(log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    int status_pipe[2];
    pid_t pid = -1;
    int result = -errno;
    if (log_fd >= 0 && pipe2(status_pipe, O_CLOEXEC) == 0) {
        pid_t parent = getpid();
        pid = fork();
        if (pid == 0) {
            close(status_pipe[0]);
            int failure;
            if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent ||
                chdir(dir) < 0 || dup2(log_fd, STDOUT_FILENO) < 0 ||
                dup2(log_fd, STDERR_FILENO) < 0 ||
                (tun_fd >= 0 && fcntl(tun_fd, F_SETFD, 0) < 0)) {
                failure = errno ? errno : ECHILD;
            } else {
                char *const args[] = {(char *)exe, "-d", (char *)dir, "-f", (char *)cfg, NULL};
                execv(exe, args);
                failure = errno;
            }
            (void)write(status_pipe[1], &failure, sizeof(failure));
            _exit(127);
        }
        close(status_pipe[1]);
        if (pid > 0) {
            int failure = 0;
            ssize_t count;
            do { count = read(status_pipe[0], &failure, sizeof(failure)); } while (count < 0 && errno == EINTR);
            if (count == 0) result = pid;
            else {
                result = -(failure ? failure : EIO);
                if (count < 0) kill(pid, SIGKILL);
                while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
            }
        } else result = -errno;
        close(status_pipe[0]);
    }
    if (log_fd >= 0) close(log_fd);
    (*env)->ReleaseStringUTFChars(env, engine, exe);
    (*env)->ReleaseStringUTFChars(env, directory, dir);
    (*env)->ReleaseStringUTFChars(env, config, cfg);
    (*env)->ReleaseStringUTFChars(env, log, log_path);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_heavencanceller_vproxy_NativeCore_waitExit(JNIEnv *env, jclass type, jint pid) {
    (void)env; (void)type;
    int status;
    pid_t result;
    do { result = waitpid(pid, &status, 0); } while (result < 0 && errno == EINTR);
    if (result < 0) return -errno;
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

JNIEXPORT void JNICALL
Java_io_github_heavencanceller_vproxy_NativeCore_terminate(JNIEnv *env, jclass type, jint pid, jboolean force) {
    (void)env; (void)type;
    if (pid > 0) kill(pid, force ? SIGKILL : SIGTERM);
}
