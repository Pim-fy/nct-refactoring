package negocut.common.idempotency;

import java.util.Optional;

// 요청 지문 저장소. 지금은 인메모리 구현만 있고, 서버를 여러 대로 늘리면 DB·Redis 구현으로 바꿀 수 있다.
public interface RequestFingerprintStore {

    // 처음 보는 지문(또는 만료된 지문)이면 처리 중으로 등록하고 true. 이미 있으면 false.
    // 동시에 같은 지문이 들어와도 정확히 한 요청만 true를 받아야 한다.
    boolean tryStart(String fingerprint);

    // 이미 끝난 요청의 저장된 응답. 아직 처리 중이거나 응답을 저장하지 않았으면 비어 있다.
    Optional<StoredResponse> findResponse(String fingerprint);

    // 처리가 성공으로 끝났을 때 응답을 저장한다. 이후 일정 시간 동안 같은 요청에 이 응답을 돌려준다.
    void complete(String fingerprint, StoredResponse response);

    // 지문을 지운다. 실패했거나 저장할 수 없는 응답이면 같은 요청을 다시 처리할 수 있게 한다.
    void remove(String fingerprint);
}
