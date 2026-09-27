package negocut.common.response;

import lombok.Getter;

@Getter 
public class ApiResponse<T> {
    
    private final boolean success;
    private final T data;
    private final ErrorResult error;

    private ApiResponse(boolean success, T data, ErrorResult error){
        this.success = success;
        this.data = data;
        this.error = error;
    }

    public static <T> ApiResponse<T> success(T data){
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> success(){
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> error(ErrorResult error){
        return new ApiResponse<>(false, null, error);
    }

}
