package com.kbrag.common;

import java.util.List;

public record Result<T>(int code, String message, T data) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(0, "ok", data);
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null);
    }

    public record Page<T>(List<T> items, long total, int page, int size) {
    }
}
