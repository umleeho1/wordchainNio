package dto;

public enum WordStatus {
    SUCCESS("통과!"),
    TOO_SHORT("단어가 너무 짧습니다 (2글자 이상)"),
    WRONG("글자가 이어지지 않습니다"),
    RECENTLY("이미 사용된 단어입니다"),
    NO_WORD("존재하지않는 단어입니다."),
    NONE(""); // 일반 채팅 등 판정이 필요 없을 때

    private final String msg;
    WordStatus(String msg) { this.msg = msg; }
    public String getMsg() { return msg; }
}