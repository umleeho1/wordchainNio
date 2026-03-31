package dto;

public enum MsgType {
    SYSTEM,     // 시스템 공지 (입장, 퇴장, 게임 시작 알림)
    CHAT,       // 일반 유저 채팅
    GAME_DATA,  // 게임 실시간 정보 (점수, 턴, 제시어 갱신)
    ERROR,       // 각종 경고 및 단어 실패 메시지
    LEAVE       // 정상 퇴장
}