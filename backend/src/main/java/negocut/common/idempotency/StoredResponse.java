package negocut.common.idempotency;

// 첫 요청이 성공했을 때 저장해 두었다가, 같은 요청이 다시 오면 그대로 돌려주는 응답
public record StoredResponse(int status, String contentType, byte[] body) {
}
