package org.example.dto;

/**
 * 统一 API 响应体（所有 REST 控制器共用）。
 * 约定：成功 success=true 且 data 有值；失败 success=false 且 errorMessage 有值。
 */
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private String message;
    private String errorMessage;
    private int code;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success = true;
        r.code = 0;
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        ApiResponse<T> r = success(data);
        r.message = message;
        return r;
    }

    public static <T> ApiResponse<T> error(String errorMessage) {
        ApiResponse<T> r = new ApiResponse<>();
        r.success = false;
        r.code = 1;
        r.errorMessage = errorMessage;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
}
