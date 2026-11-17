package com.chaosforge.medins.adapter;

/** 取数视图定义读取异常：视图不存在／无权限、SQL 失败、文本为空等一律显式报错。 */
public class ViewReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ViewReadException(String message) {
        super(message);
    }

    public ViewReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
