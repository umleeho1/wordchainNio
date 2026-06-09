package dto;

import java.io.Serializable;
import java.util.Map;

public class Message implements Serializable {
    private static final long serialVersionUID = 1L; // 직렬화 버전 ID

    private MsgType type;      // 메시지 타입 (Enum)
    private String sender;         // 보낸 사람 이름
    private String content;        // 내용 (채팅 메시지 또는 현재 제시어)
    private Map<String, Integer> score; // 전 유저 점수 현황 (이름:점수)
    private boolean isTurn;        // 받는 사람이 현재 턴인지 여부
    private WordStatus w;          // 단어 판정 결과 (Enum)

    // 1. 일반 채팅/시스템용 생성자 (편의를 위해)
    public Message(MsgType type, String sender, String content) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.w = WordStatus.NONE;
    }

    // 2. 게임 데이터 전송용 풀 생성자
    public Message(MsgType type, String sender, String content, 
                   Map<String, Integer> score, boolean isTurn, WordStatus w) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.score = score;
        this.isTurn = isTurn;
        this.w = w;
    }

    // Getter & Setter (컨트롤러와 서버에서 값을 꺼내고 넣을 때 사용)
    public MsgType getType() { return type; }
    public void setType(MsgType type) { this.type = type; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Map<String, Integer> getScore() { return score; }
    public void setScore(Map<String, Integer> score) { this.score = score; }

    public boolean isTurn() { return isTurn; }
    public void setTurn(boolean isTurn) { this.isTurn = isTurn; }

    public WordStatus getW() { return w; }
    public void setW(WordStatus w) { this.w = w; }
}